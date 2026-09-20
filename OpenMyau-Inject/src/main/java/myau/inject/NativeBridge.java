package myau.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class NativeBridge {

    private static final boolean DUMP_TARGETS = false;
    private static final ThreadLocal<Boolean> REMAPPING = new ThreadLocal<Boolean>();
    private static HookTransformer transformer;

    public static HookTransformer transformer() {
        return transformer;
    }
    private static volatile Set<String> ourClasses;
    private static boolean installed;
    private NativeBridge() {
    }
    public static synchronized void install() {
        if (installed) {
            log("already installed, ignoring");
            return;
        }
        installed = true;
        try {
            Hooks.register();
            log("hooks registered: " + HookRegistry.size());

            transformer = new HookTransformer(new Class<?>[0]);
            warmUp();
            checkCallbacks();
        } catch (Throwable t) {
            Log.throwable("install failed", t);
        }
    }
    public static synchronized void retransformLoaded() {
        if (transformer == null) {
            return;
        }
        try {
            List<String> owners = new ArrayList<String>();
            for (String owner : HookRegistry.owners()) {
                owners.add(owner);
                String notch = MappingBridge.notchClass(owner);
                if (notch != null) {
                    owners.add(notch);
                }
            }
            log("asking for " + owners.size() + " already-loaded names: " + owners);
            retransform(owners.toArray(new String[owners.size()]));
            for (String line : transformer.drain()) {
                log("  " + line);
            }
            List<String> unresolved = new ArrayList<String>(MappingBridge.failures());
            if (!unresolved.isEmpty()) {
                log("unresolved mappings (" + unresolved.size() + "):");
                for (String failure : unresolved) {
                    log("  " + failure);
                }
            }
        } catch (Throwable t) {
            Log.throwable("retransform failed", t);
        }
    }

    public static synchronized void reportUnapplied(String phase) {
        HookTransformer active = transformer;
        if (active == null) {
            return;
        }
        try {
            java.util.List<HookRegistry.Hook> all = HookRegistry.allHooks();
            Set<String> applied = active.appliedKeys();
            Set<String> patchedOwners = active.patchedOwners();
            java.util.List<String> missingRequired = new ArrayList<String>();
            java.util.List<String> missingOptional = new ArrayList<String>();
            for (HookRegistry.Hook hook : all) {
                if (applied.contains(HookRegistry.keyOf(hook))) {
                    continue;
                }
                String line = HookRegistry.keyOf(hook)
                        + (patchedOwners.contains(hook.owner) ? "" : "  [class never transformed]");
                if (hook.required) {
                    missingRequired.add(line);
                } else {
                    missingOptional.add(line);
                }
            }
            log("=== hook report (" + phase + ") ===");
            log("applied " + applied.size() + "/" + all.size()
                    + ", required missing " + missingRequired.size()
                    + ", optional missing " + missingOptional.size());
            for (String line : missingRequired) {
                log("  MISSING(required) " + line);
            }
            for (String line : missingOptional) {
                log("  MISSING(optional) " + line);
            }
            java.util.List<String> failures = active.failures();
            if (!failures.isEmpty()) {
                log("transform failures (" + failures.size() + "):");
                for (String failure : failures) {
                    log("  " + failure);
                }
            }
            log("=== end hook report (" + phase + ") ===");
        } catch (Throwable t) {
            Log.throwable("hook report failed", t);
        }
    }

    private static void checkCallbacks() {
        int checked = 0;
        int wrong = 0;
        for (String owner : HookRegistry.owners()) {
            for (HookRegistry.Hook hook : HookRegistry.forOwner(owner)) {
                checked++;
                String problem = describeMismatch(hook);
                if (problem != null) {
                    wrong++;
                    log("BAD CALLBACK " + hook.owner + "." + hook.method
                            + " @" + hook.position + " -> " + problem);
                }
            }
        }
        log("callbacks checked: " + checked + (wrong == 0 ? ", all present" : ", " + wrong + " WRONG"));
    }
    private static final Map<String, Set<String>> CALLBACK_MEMBERS =
            new HashMap<String, Set<String>>();
    private static Set<String> membersOf(String internalName) {
        Set<String> known = CALLBACK_MEMBERS.get(internalName);
        if (known != null) {
            return known;
        }
        known = new HashSet<String>();
        byte[] bytes = readOwn(internalName + ".class");
        if (bytes != null) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE
                        | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (Object raw : node.methods) {
                    MethodNode method = (MethodNode) raw;
                    if ((method.access & Opcodes.ACC_STATIC) != 0) {
                        known.add(method.name + method.desc);
                    }
                }
            } catch (Throwable t) {
                log("cannot read " + internalName + " out of our own jar: " + t);
            }
        }
        CALLBACK_MEMBERS.put(internalName, known);
        return known;
    }
    private static String describeMismatch(HookRegistry.Hook hook) {
        Set<String> members = membersOf(hook.callbackOwner);
        if (members.isEmpty()) {
            return hook.callbackOwner + " is not in this jar";
        }
        if (members.contains(hook.callbackName + hook.callbackDescriptor)) {
            return null;
        }
        StringBuilder sameName = new StringBuilder();
        for (String member : members) {
            if (member.startsWith(hook.callbackName + "(")) {
                sameName.append(" saw ").append(member.substring(hook.callbackName.length()));
            }
        }
        return hook.callbackOwner + "." + hook.callbackName + hook.callbackDescriptor
                + " is not a static method there;"
                + (sameName.length() == 0 ? " nothing of that name" : sameName);
    }
    private static void warmUp() {
        try {
            ourClasses = readOwnJar();
            Obfuscation.prepare();
            byte[] sample = readOwn("myau/inject/Log.class");
            if (sample != null) {
                transform("myau/inject/Log", sample);
                transformer.warmUp(sample);
            }
            log("warm-up complete: " + (ourClasses == null ? 0 : ourClasses.size())
                    + " own classes known");
        } catch (Throwable t) {
            Log.throwable("warm-up failed", t);
        }
    }
    private static byte[] readOwn(String entry) {
        JarFile jar = null;
        try {
            jar = new JarFile(ownJarPath());
            JarEntry found = jar.getJarEntry(entry);
            if (found == null) {
                return null;
            }
            java.io.InputStream in = jar.getInputStream(found);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
                }
            }
        }
    }
    public static void flushTransformLog() {
        HookTransformer active = transformer;
        if (active == null || !active.hasLog()) {
            return;
        }
        for (String line : active.drain()) {
            log("  " + line);
        }
    }

    public static void start() {
        Bootstrap.requestStart();
        log("client construction queued for the next tick");
    }
    public static byte[] transform(String internalName, byte[] classfile) {
        try {
            if (isOurs(internalName)) {
                if (Boolean.TRUE.equals(REMAPPING.get())) {
                    return classfile;
                }
                REMAPPING.set(Boolean.TRUE);
                try {
                    if (!Obfuscation.needsRemapping() || skipRemap(internalName)) {
                        return classfile;
                    }
                    return Obfuscation.remap(internalName, classfile);
                } finally {
                    REMAPPING.remove();
                }
            }
            if (transformer == null) {
                return classfile;
            }
            byte[] patched = transformer.transform(null, internalName, null, null, classfile);
            if (DUMP_TARGETS && patched != null) {
                dump(internalName + ".in", classfile);
                dump(internalName + ".out", patched);
            }
            return patched == null ? classfile : patched;
        } catch (Throwable t) {
            log("transform failed for " + internalName + ": " + t);
            return classfile;
        }
    }
    private static boolean isOurs(String internalName) {
        Set<String> known = ourClasses;
        return known != null && known.contains(internalName);
    }
    private static File ownJarPath() throws Exception {
        String location = NativeBridge.class.getProtectionDomain()
                .getCodeSource().getLocation().toString();
        if (location.startsWith("jar:")) {
            int bang = location.indexOf("!/");
            location = location.substring(4, bang < 0 ? location.length() : bang);
        }
        return new File(new java.net.URI(location));
    }
    private static Set<String> readOwnJar() {
        Set<String> found = new HashSet<String>();
        JarFile jar = null;
        try {
            jar = new JarFile(ownJarPath());
            int shaded = 0;
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                name = name.substring(0, name.length() - ".class".length());
                if (name.startsWith("myau/shadow/") || name.startsWith("org/")) {
                    shaded++;
                    continue;
                }
                found.add(name);
            }
            log("own classes: " + found.size() + " (" + shaded + " shaded, left alone)");
        } catch (Throwable t) {
            Log.throwable("cannot read own jar -- nothing will be remapped", t);
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
                }
            }
        }
        return found;
    }
    private static void dump(String name, byte[] bytes) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "myau-dump");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File out = new File(dir, name.replace('/', '.') + ".class");
            java.io.FileOutputStream stream = new java.io.FileOutputStream(out);
            try {
                stream.write(bytes);
            } finally {
                stream.close();
            }
            log("dumped " + out.getName() + " (" + bytes.length + " bytes)");
        } catch (Throwable t) {
            log("could not dump " + name + ": " + t);
        }
    }
    private static boolean skipRemap(String internalName) {
        return internalName.contains("$$Lambda") || internalName.contains("/0x");
    }
    private static native void retransform(String[] classNames);
    static void log(String message) {
        Log.line(message);
    }
}
