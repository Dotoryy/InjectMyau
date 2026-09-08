#include <windows.h>
#include <jni.h>
#include <jvmti.h>

#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace {

std::string toUtf8(const std::wstring &text);

JavaVM *g_vm = nullptr;
jvmtiEnv *g_jvmti = nullptr;
jclass g_bridge = nullptr;
jmethodID g_transform = nullptr;
std::wstring g_dllDir;
HMODULE g_module = nullptr;
constexpr int IDR_EMBEDDED_JAR = 101;
std::wstring g_dllPath;
std::wstring g_logPath;

void logf(const char *format, ...) {
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);

    std::wstring path = g_logPath.empty() ? L"myau-native.log" : g_logPath;
    FILE *f = nullptr;
    if (_wfopen_s(&f, path.c_str(), L"a") == 0 && f) {
        fprintf(f, "[myau-native] %s\n", message);
        fclose(f);
    }
    OutputDebugStringA(message);
}

JavaVM *findRunningVm() {
    HMODULE jvmModule = GetModuleHandleW(L"jvm.dll");
    if (!jvmModule) {
        logf("jvm.dll is not loaded in this process -- not a Java process?");
        return nullptr;
    }
    typedef jint(JNICALL * GetCreatedVms)(JavaVM **, jsize, jsize *);
    GetCreatedVms getCreatedVms =
            (GetCreatedVms)GetProcAddress(jvmModule, "JNI_GetCreatedJavaVMs");
    if (!getCreatedVms) {
        logf("jvm.dll has no JNI_GetCreatedJavaVMs export");
        return nullptr;
    }
    JavaVM *vm = nullptr;
    jsize count = 0;
    if (getCreatedVms(&vm, 1, &count) != JNI_OK || count == 0 || !vm) {
        logf("no JVM created in this process yet");
        return nullptr;
    }
    return vm;
}
JNIEnv *attachThread() {
    JNIEnv *env = nullptr;
    jint result = g_vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = (char *)"myau-inject";
        args.group = nullptr;
        if (g_vm->AttachCurrentThread((void **)&env, &args) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}
bool clearPendingException(JNIEnv *env, const char *what) {
    if (!env->ExceptionCheck()) {
        return false;
    }
    logf("java exception during %s", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}
std::string classNameOf(JNIEnv *env, jobject object) {
    if (!object) {
        return "null";
    }
    jclass objectClass = env->GetObjectClass(object);
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    jstring name = (jstring)env->CallObjectMethod(objectClass, getName);
    std::string out = "?";
    if (name) {
        const char *chars = env->GetStringUTFChars(name, nullptr);
        if (chars) {
            out = chars;
            env->ReleaseStringUTFChars(name, chars);
        }
        env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(objectClass);
    env->DeleteLocalRef(classClass);
    return out;
}
jobject findGameClassLoader(JNIEnv *env) {
    jint count = 0;
    jclass *classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) {
        logf("GetLoadedClasses failed");
        return nullptr;
    }
    jobject byName = nullptr;
    std::vector<jobject> loaders;
    std::vector<int> counts;
    for (jint i = 0; i < count; i++) {
        jobject loader = nullptr;
        if (g_jvmti->GetClassLoader(classes[i], &loader) != JVMTI_ERROR_NONE || !loader) {
            continue;
        }
        if (!byName) {
            char *signature = nullptr;
            if (g_jvmti->GetClassSignature(classes[i], &signature, nullptr) == JVMTI_ERROR_NONE
                    && signature) {
                if (strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0) {
                    byName = env->NewGlobalRef(loader);
                }
                g_jvmti->Deallocate((unsigned char *)signature);
            }
        }

        bool seen = false;
        for (size_t k = 0; k < loaders.size(); k++) {
            if (env->IsSameObject(loaders[k], loader)) {
                counts[k]++;
                seen = true;
                break;
            }
        }
        if (!seen) {
            loaders.push_back(env->NewGlobalRef(loader));
            counts.push_back(1);
        }
        env->DeleteLocalRef(loader);
    }
    g_jvmti->Deallocate((unsigned char *)classes);
    jobject chosen = nullptr;
    if (byName) {
        logf("game classloader found by name (net.minecraft.client.Minecraft)");
        chosen = byName;
    } else {
        int best = -1;
        for (size_t k = 0; k < loaders.size(); k++) {
            if (best < 0 || counts[k] > counts[best]) {
                best = (int)k;
            }
        }
        for (size_t k = 0; k < loaders.size(); k++) {
            if (counts[k] < 50) {
                continue;
            }
            logf("  loader candidate: %-56s %d classes%s",
                 classNameOf(env, loaders[k]).c_str(), counts[k],
                 (int)k == best ? "   <-- chosen" : "");
        }
        if (best >= 0) {
            chosen = env->NewGlobalRef(loaders[best]);
        }
    }
    for (size_t k = 0; k < loaders.size(); k++) {
        env->DeleteGlobalRef(loaders[k]);
    }
    if (!chosen) {
        logf("no game classloader found");
    }
    return chosen;
}
std::wstring extractEmbeddedJar() {
    HRSRC found = FindResourceW(g_module, MAKEINTRESOURCEW(IDR_EMBEDDED_JAR), MAKEINTRESOURCEW(10));
    if (!found) {
        return L"";
    }
    HGLOBAL loaded = LoadResource(g_module, found);
    if (!loaded) {
        return L"";
    }
    const void *bytes = LockResource(loaded);
    DWORD size = SizeofResource(g_module, found);
    if (!bytes || size == 0) {
        return L"";
    }
    wchar_t tempDir[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, tempDir)) {
        return L"";
    }
    wchar_t name[64];
    wsprintfW(name, L"myau_client_%lu.jar", GetCurrentProcessId());
    std::wstring path = std::wstring(tempDir) + name;
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                                CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        if (GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES) {
            logf("reusing the jar already unpacked for this process");
            return path;
        }
        logf("could not open %S to unpack the jar (error %lu)", path.c_str(), GetLastError());
        return L"";
    }
    DWORD written = 0;
    BOOL ok = WriteFile(handle, bytes, size, &written, nullptr);
    CloseHandle(handle);
    if (!ok || written != size) {
        logf("short write unpacking the jar (error %lu)", GetLastError());
        DeleteFileW(path.c_str());
        return L"";
    }
    logf("unpacked the embedded jar: %lu bytes", (unsigned long)size);
    return path;
}

std::wstring findClientJar() {
    std::wstring sidecar = g_dllPath + L".txt";
    HANDLE handle = CreateFileW(sidecar.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle != INVALID_HANDLE_VALUE) {
        char buffer[MAX_PATH * 4] = {};
        DWORD read = 0;
        ReadFile(handle, buffer, sizeof(buffer) - 1, &read, nullptr);
        CloseHandle(handle);
        if (read > 0) {
            int size = MultiByteToWideChar(CP_UTF8, 0, buffer, (int)read, nullptr, 0);
            std::wstring path(size, 0);
            MultiByteToWideChar(CP_UTF8, 0, buffer, (int)read, &path[0], size);
            if (GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES) {
                return path;
            }
            logf("the sidecar names a jar that is not there");
        }
    }
    std::wstring embedded = extractEmbeddedJar();
    if (!embedded.empty()) {
        return embedded;
    }
    std::wstring pattern = g_dllDir + L"\\*.jar";
    WIN32_FIND_DATAW data;
    HANDLE find = FindFirstFileW(pattern.c_str(), &data);
    if (find == INVALID_HANDLE_VALUE) {
        return L"";
    }
    std::wstring found = g_dllDir + L"\\" + data.cFileName;
    FindClose(find);
    return found;
}
std::string toUtf8(const std::wstring &text) {
    if (text.empty()) {
        return "";
    }
    int size = WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int)text.size(),
                                   nullptr, 0, nullptr, nullptr);
    std::string out(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int)text.size(),
                        &out[0], size, nullptr, nullptr);
    return out;
}

bool addJarToLoader(JNIEnv *env, jobject loader, const std::wstring &jarPath) {
    std::string utf8Path = toUtf8(jarPath);
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem = env->GetStaticMethodID(classLoaderClass, "getSystemClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    jobject systemLoader = getSystem
            ? env->CallStaticObjectMethod(classLoaderClass, getSystem)
            : nullptr;
    clearPendingException(env, "getSystemClassLoader");
    if (systemLoader && env->IsSameObject(loader, systemLoader)) {
        jvmtiError error = g_jvmti->AddToSystemClassLoaderSearch(utf8Path.c_str());
        if (error == JVMTI_ERROR_NONE) {
            logf("jar appended to the system class loader search: %s", utf8Path.c_str());
            return true;
        }
        logf("AddToSystemClassLoaderSearch failed (error %d)", error);
        return false;
    }
    jclass fileClass = env->FindClass("java/io/File");
    jclass uriClass = env->FindClass("java/net/URI");
    jclass urlClassLoaderClass = env->FindClass("java/net/URLClassLoader");
    if (!fileClass || !uriClass || !urlClassLoaderClass) {
        clearPendingException(env, "resolving java.io.File / java.net.URI");
        return false;
    }
    if (!env->IsInstanceOf(loader, urlClassLoaderClass)) {
        logf("game classloader is %s -- neither the system loader nor a URLClassLoader,",
             classNameOf(env, loader).c_str());
        logf("so there is no supported way to put the jar in front of it.");
        return false;
    }
    jmethodID fileCtor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jmethodID addUrl = env->GetMethodID(urlClassLoaderClass, "addURL", "(Ljava/net/URL;)V");
    if (!fileCtor || !toUri || !toUrl || !addUrl) {
        clearPendingException(env, "resolving addURL");
        return false;
    }
    jstring jPath = env->NewStringUTF(utf8Path.c_str());
    jobject file = env->NewObject(fileClass, fileCtor, jPath);
    jobject uri = env->CallObjectMethod(file, toUri);
    jobject url = env->CallObjectMethod(uri, toUrl);
    if (clearPendingException(env, "building the jar URL") || !url) {
        return false;
    }
    env->CallVoidMethod(loader, addUrl, url);
    if (clearPendingException(env, "addURL")) {
        return false;
    }
    logf("jar added to the game classloader: %s", utf8Path.c_str());
    return true;
}
jclass loadThroughLoader(JNIEnv *env, jobject loader, const char *className) {
    jclass loaderClass = env->GetObjectClass(loader);
    jmethodID loadClass = env->GetMethodID(loaderClass, "loadClass",
                                           "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass) {
        clearPendingException(env, "resolving loadClass");
        return nullptr;
    }
    jstring name = env->NewStringUTF(className);
    jclass found = (jclass)env->CallObjectMethod(loader, loadClass, name);
    if (clearPendingException(env, className) || !found) {
        return nullptr;
    }
    return (jclass)env->NewGlobalRef(found);
}
void JNICALL classFileLoadHook(jvmtiEnv *jvmti, JNIEnv *env, jclass ,
                               jobject , const char *name,
                               jobject , jint length,
                               const unsigned char *data, jint *newLength,
                               unsigned char **newData) {
    if (!g_bridge || !g_transform || !name || !env) {
        return;
    }

    if (strncmp(name, "java/", 5) == 0 || strncmp(name, "javax/", 6) == 0
            || strncmp(name, "sun/", 4) == 0 || strncmp(name, "jdk/", 4) == 0
            || strncmp(name, "org/objectweb/asm/", 18) == 0) {
        return;
    }
    jbyteArray original = env->NewByteArray(length);
    if (!original) {
        return;
    }
    env->SetByteArrayRegion(original, 0, length, (const jbyte *)data);
    jstring className = env->NewStringUTF(name);
    jbyteArray result = (jbyteArray)env->CallStaticObjectMethod(
            g_bridge, g_transform, className, original);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(original);
        env->DeleteLocalRef(className);
        return;
    }
    if (result && result != original) {
        jint size = env->GetArrayLength(result);
        unsigned char *buffer = nullptr;
        if (jvmti->Allocate(size, &buffer) == JVMTI_ERROR_NONE && buffer) {
            env->GetByteArrayRegion(result, 0, size, (jbyte *)buffer);
            *newData = buffer;
            *newLength = size;
        }
    }
    env->DeleteLocalRef(original);
    env->DeleteLocalRef(className);
    if (result) {
        env->DeleteLocalRef(result);
    }
}
const char *describe(jvmtiError error) {
    switch (error) {
        case JVMTI_ERROR_INVALID_CLASS_FORMAT:
            return "the rewritten class is malformed";
        case JVMTI_ERROR_FAILS_VERIFICATION:
            return "the rewritten class does not verify";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED:
            return "a method was added, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED:
            return "the fields changed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED:
            return "the hierarchy changed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED:
            return "a method was removed, which retransformation forbids";
        case JVMTI_ERROR_UNSUPPORTED_VERSION:
            return "unsupported class file version";
        case JVMTI_ERROR_NAMES_DONT_MATCH:
            return "the class name changed";
        case JVMTI_ERROR_UNMODIFIABLE_CLASS:
            return "the class cannot be modified at all";
        default:
            return "refused";
    }
}
void JNICALL nativeRetransform(JNIEnv *env, jclass , jobjectArray names) {
    if (!g_jvmti || !names) {
        return;
    }
    jint wanted = env->GetArrayLength(names);
    jint count = 0;
    jclass *classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE || !classes) {
        return;
    }
    std::vector<jclass> targets;
    for (jint w = 0; w < wanted; w++) {
        jstring name = (jstring)env->GetObjectArrayElement(names, w);
        const char *chars = env->GetStringUTFChars(name, nullptr);
        if (!chars) {
            continue;
        }
        std::string signature = "L";
        for (const char *c = chars; *c; c++) {
            signature += (*c == '.') ? '/' : *c;
        }
        signature += ';';
        env->ReleaseStringUTFChars(name, chars);
        for (jint i = 0; i < count; i++) {
            char *actual = nullptr;
            if (g_jvmti->GetClassSignature(classes[i], &actual, nullptr) != JVMTI_ERROR_NONE
                    || !actual) {
                continue;
            }
            bool hit = signature == actual;
            g_jvmti->Deallocate((unsigned char *)actual);
            if (hit) {
                targets.push_back(classes[i]);
                logf("  already loaded: %s", signature.c_str() + 1);
                break;
            }
        }
    }
    if (targets.empty()) {
        logf("none of the hook targets are loaded yet -- they will be patched as they load");
        g_jvmti->Deallocate((unsigned char *)classes);
        return;
    }

    int patched = 0;
    for (size_t i = 0; i < targets.size(); i++) {
        jvmtiError error = g_jvmti->RetransformClasses(1, &targets[i]);
        if (error == JVMTI_ERROR_NONE) {
            patched++;
            continue;
        }
        char *signature = nullptr;
        const char *shown = "?";
        if (g_jvmti->GetClassSignature(targets[i], &signature, nullptr) == JVMTI_ERROR_NONE
                && signature) {
            shown = signature + 1;
        }
        logf("  REFUSED %s -- %s (%d)", shown, describe(error), error);
        if (signature) {
            g_jvmti->Deallocate((unsigned char *)signature);
        }
    }
    logf("retransformed %d of %d already-loaded classes", patched, (int)targets.size());
    g_jvmti->Deallocate((unsigned char *)classes);
}
DWORD WINAPI bootstrap(LPVOID) {
    logf("---- injected ----");
    g_vm = findRunningVm();
    if (!g_vm) {
        return 0;
    }
    JNIEnv *env = attachThread();
    if (!env) {
        logf("could not attach to the JVM");
        return 0;
    }
    if (g_vm->GetEnv((void **)&g_jvmti, JVMTI_VERSION_1_1) != JNI_OK || !g_jvmti) {
        logf("no JVMTI environment available");
        return 0;
    }
    jvmtiCapabilities capabilities = {};
    capabilities.can_generate_all_class_hook_events = 1;
    capabilities.can_retransform_classes = 1;
    capabilities.can_redefine_classes = 1;
    jvmtiError error = g_jvmti->AddCapabilities(&capabilities);
    if (error != JVMTI_ERROR_NONE) {
        logf("AddCapabilities failed (error %d)", error);
        return 0;
    }
    std::wstring jar = findClientJar();
    if (jar.empty()) {
        logf("no .jar next to the DLL -- put the client jar in the same folder");
        return 0;
    }
    jobject loader = findGameClassLoader(env);
    if (!loader) {
        return 0;
    }
    if (!addJarToLoader(env, loader, jar)) {
        return 0;
    }
    g_bridge = loadThroughLoader(env, loader, "myau.inject.NativeBridge");
    if (!g_bridge) {
        logf("could not load myau.inject.NativeBridge from the game classloader");
        return 0;
    }
    g_transform = env->GetStaticMethodID(g_bridge, "transform",
                                         "(Ljava/lang/String;[B)[B");
    if (!g_transform) {
        clearPendingException(env, "resolving NativeBridge.transform");
        return 0;
    }
    JNINativeMethod natives[] = {
            {(char *)"retransform", (char *)"([Ljava/lang/String;)V", (void *)&nativeRetransform}};
    if (env->RegisterNatives(g_bridge, natives, 1) != JNI_OK) {
        clearPendingException(env, "RegisterNatives");
        return 0;
    }

    jmethodID install = env->GetStaticMethodID(g_bridge, "install", "()V");
    if (!install) {
        clearPendingException(env, "resolving NativeBridge.install");
        return 0;
    }
    env->CallStaticVoidMethod(g_bridge, install);
    if (clearPendingException(env, "NativeBridge.install")) {
        return 0;
    }
    jvmtiEventCallbacks callbacks = {};
    callbacks.ClassFileLoadHook = classFileLoadHook;
    if (g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE
            || g_jvmti->SetEventNotificationMode(
                       JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr)
                    != JVMTI_ERROR_NONE) {
        logf("could not enable ClassFileLoadHook");
        return 0;
    }
    logf("ClassFileLoadHook active");
    jmethodID retransformLoaded = env->GetStaticMethodID(g_bridge, "retransformLoaded", "()V");
    if (retransformLoaded) {
        env->CallStaticVoidMethod(g_bridge, retransformLoaded);
        clearPendingException(env, "NativeBridge.retransformLoaded");
    }
    jmethodID start = env->GetStaticMethodID(g_bridge, "start", "()V");
    if (start) {
        env->CallStaticVoidMethod(g_bridge, start);
        clearPendingException(env, "NativeBridge.start");
    }
    logf("---- installed ----");
    return 0;
}
}
BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason != DLL_PROCESS_ATTACH) {
        return TRUE;
    }
    DisableThreadLibraryCalls(module);
    g_module = module;
    wchar_t path[MAX_PATH];
    if (GetModuleFileNameW(module, path, MAX_PATH)) {
        g_dllPath = path;
        size_t slash = g_dllPath.find_last_of(L'\\');
        g_dllDir = (slash == std::wstring::npos) ? L"." : g_dllPath.substr(0, slash);
    }

    wchar_t tempDir[MAX_PATH];
    if (GetTempPathW(MAX_PATH, tempDir)) {
        g_logPath = std::wstring(tempDir) + L"myau-native.log";
    }
    CreateThread(nullptr, 0, bootstrap, nullptr, 0, nullptr);
    return TRUE;
}
