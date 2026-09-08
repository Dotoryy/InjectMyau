#include "inject.h"

#include <psapi.h>
#include <stdarg.h>
#include <stdio.h>

#include <string>
#include <vector>

#include "target.h"

namespace {

std::wstring format(const wchar_t *pattern, ...) {
    wchar_t buffer[1024];
    va_list args;
    va_start(args, pattern);
    _vsnwprintf_s(buffer, _TRUNCATE, pattern, args);
    va_end(args);
    return buffer;
}

bool is64Bit(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) {
        return true;
    }
    BOOL wow64 = FALSE;
    IsWow64Process(process, &wow64);
    CloseHandle(process);
    return !wow64;
}

bool inject(DWORD pid, const std::wstring &dllPath, const Logger &log, const Logger &debug) {
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION
                                         | PROCESS_VM_OPERATION | PROCESS_VM_WRITE
                                         | PROCESS_VM_READ,
                                 FALSE, pid);
    if (!process) {
        log(format(L"cannot open process %lu (error %lu)", pid, GetLastError()));
        log(L"if the game runs elevated, run this the same way");
        return false;
    }
    debug(format(L"opened process %lu, handle %p", pid, (void *)process));
    SIZE_T bytes = (dllPath.size() + 1) * sizeof(wchar_t);
    void *remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE,
                                  PAGE_READWRITE);
    if (!remote) {
        log(format(L"VirtualAllocEx failed (error %lu)", GetLastError()));
        CloseHandle(process);
        return false;
    }
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote, dllPath.c_str(), bytes, &written)
            || written != bytes) {
        log(format(L"WriteProcessMemory failed (error %lu)", GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    debug(format(L"wrote dll path to remote %p (%zu bytes)", remote, (size_t)bytes));
    HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
    LPTHREAD_START_ROUTINE loadLibrary =
            (LPTHREAD_START_ROUTINE)GetProcAddress(kernel32, "LoadLibraryW");
    debug(format(L"LoadLibraryW at %p", (void *)loadLibrary));
    HANDLE thread = CreateRemoteThread(process, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (!thread) {
        log(format(L"CreateRemoteThread failed (error %lu)", GetLastError()));
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return false;
    }
    debug(format(L"remote thread %p started, waiting", (void *)thread));
    WaitForSingleObject(thread, 15000);
    DWORD result = 0;
    GetExitCodeThread(thread, &result);
    debug(format(L"LoadLibraryW returned module 0x%08lx in the target", result));
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);
    if (result == 0) {
        log(L"LoadLibraryW returned null in the target -- the DLL did not load.");
        log(L"Usually an architecture mismatch: this builds x64, and the game");
        log(L"must be 64-bit too.");
        return false;
    }
    return true;
}
bool writeFile(const std::wstring &path, const void *bytes, size_t size, const Logger &log,
               bool quiet = false) {
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        if (!quiet) {
            log(format(L"cannot write %s (error %lu)", path.c_str(), GetLastError()));
        }
        return false;
    }
    DWORD written = 0;
    BOOL ok = WriteFile(handle, bytes, (DWORD)size, &written, nullptr);
    CloseHandle(handle);
    if (!ok || written != size) {
        log(format(L"short write to %s (error %lu)", path.c_str(), GetLastError()));
        return false;
    }
    return true;
}
bool stageForInjection(const void *dllBytes, size_t dllSize,
                       const void *jarBytes, size_t jarSize,
                       DWORD pid, std::wstring &stagedDll, std::wstring &stagedJarOut,
                       const Logger &log, const Logger &debug) {
    wchar_t tempDir[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, tempDir)) {
        return false;
    }
    std::wstring base(tempDir);
    for (const wchar_t *pattern : {L"myau_native_*.dll", L"myau_client_*.jar",
                                   L"myau_native_*.dll.txt"}) {
        WIN32_FIND_DATAW data;
        HANDLE handle = FindFirstFileW((base + pattern).c_str(), &data);
        if (handle == INVALID_HANDLE_VALUE) {
            continue;
        }
        do {
            DeleteFileW((base + data.cFileName).c_str());
        } while (FindNextFileW(handle, &data));
        FindClose(handle);
    }
    wchar_t suffix[64];
    std::wstring stagedJar;
    bool staged = false;
    for (int attempt = 0; attempt < 32 && !staged; attempt++) {
        if (attempt == 0) {
            wsprintfW(suffix, L"%lu", pid);
        } else {
            wsprintfW(suffix, L"%lu_%d", pid, attempt);
        }
        stagedDll = base + L"myau_native_" + suffix + L".dll";
        stagedJar = base + L"myau_client_" + suffix + L".jar";
        bool last = attempt == 31;
        bool jarOk = jarSize == 0 || writeFile(stagedJar, jarBytes, jarSize, log, !last);
        if (writeFile(stagedDll, dllBytes, dllSize, log, !last) && jarOk) {
            staged = true;
        }
    }
    if (!staged) {
        return false;
    }
    debug(format(L"staged dll: %s (%zu bytes)", stagedDll.c_str(), dllSize));
    if (jarSize == 0) {
        stagedJarOut.clear();
        debug(L"jar is embedded in the dll -- nothing staged for it");
        return true;
    }
    stagedJarOut = stagedJar;
    int size = WideCharToMultiByte(CP_UTF8, 0, stagedJar.c_str(), (int)stagedJar.size(),
                                   nullptr, 0, nullptr, nullptr);
    std::string utf8(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, stagedJar.c_str(), (int)stagedJar.size(),
                        &utf8[0], size, nullptr, nullptr);
    return writeFile(stagedDll + L".txt", utf8.data(), utf8.size(), log);
}

void spawnReaper(DWORD pid, const std::wstring &dll, const std::wstring &txt,
                 const std::wstring &jar, const std::wstring &embedded, const Logger &debug) {
    std::wstring files;
    for (const std::wstring &path : {dll, txt, jar, embedded}) {
        if (!path.empty()) {
            if (!files.empty()) {
                files += L",";
            }
            files += L"'" + path + L"'";
        }
    }
    if (files.empty()) {
        return;
    }
    wchar_t pidText[16];
    wsprintfW(pidText, L"%lu", pid);
    std::wstring script =
            L"Wait-Process -Id " + std::wstring(pidText) + L" -ErrorAction SilentlyContinue; "
            L"$f=@(" + files + L"); "
            L"for($i=0;$i -lt 30;$i++){"
            L"Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue; "
            L"if(-not (Test-Path -LiteralPath '" + dll + L"')){break}; "
            L"Start-Sleep -Seconds 1}";
    std::wstring command =
            L"powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command \""
            + script + L"\"";
    std::vector<wchar_t> buffer(command.begin(), command.end());
    buffer.push_back(L'\0');
    STARTUPINFOW si = {sizeof(si)};
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi = {};
    if (CreateProcessW(nullptr, buffer.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                       nullptr, nullptr, &si, &pi)) {
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        debug(format(L"detached cleanup waiting on pid %lu", pid));
    }
}
}
bool findTarget(DWORD &pid, std::wstring &launcher) {
    const Candidate *best = nullptr;
    int ties = 0;
    std::vector<Candidate> all = findCandidates();
    for (const Candidate &candidate : all) {
        if (candidate.score <= 0) {
            continue;
        }
        if (!best || candidate.score > best->score) {
            best = &candidate;
            ties = 1;
        } else if (candidate.score == best->score) {
            ties++;
        }
    }
    if (!best || ties > 1) {
        return false;
    }
    pid = best->pid;
    launcher = launcherOf(best->commandLine);
    return true;
}
bool runInjection(const void *dllBytes, size_t dllSize,
                  const void *jarBytes, size_t jarSize,
                  DWORD explicitPid, const Logger &log, const Logger &debug) {
    DWORD pid = explicitPid;
    if (pid == 0) {
        std::vector<Candidate> all = findCandidates();
        const Candidate *best = nullptr;
        int ties = 0;
        for (const Candidate &c : all) {
            if (c.score <= 0) {
                continue;
            }
            if (!best || c.score > best->score) {
                best = &c;
                ties = 1;
            } else if (c.score == best->score) {
                ties++;
            }
        }
        log(L"java processes:");
        if (all.empty()) {
            log(L"  (none running)");
        }
        for (const Candidate &c : all) {
            std::wstring line = c.commandLine.substr(0, 90);
            log(format(L"  %6lu  score %4d  %s", c.pid, c.score,
                       line.empty() ? L"(command line unreadable)" : line.c_str()));
        }

        if (!best) {
            log(L"None of these look like Minecraft. Start the game first.");
            return false;
        }
        if (ties > 1) {
            log(format(L"%d processes score the same -- cannot choose between them.", ties));
            return false;
        }
        pid = best->pid;
        log(format(L"chosen: %lu (score %d)", pid, best->score));
    }
    debug(format(L"payload: %zu bytes of dll, jar %s", dllSize,
                 jarSize == 0 ? L"embedded" : L"separate"));
    debug(format(L"checking architecture of pid %lu", pid));
    if (!is64Bit(pid)) {
        log(format(L"process %lu is 32-bit and this DLL is x64 -- they cannot mix.", pid));
        return false;
    }
    debug(L"target is 64-bit, staging payload");
    std::wstring staged;
    std::wstring stagedJar;
    if (!stageForInjection(dllBytes, dllSize, jarBytes, jarSize, pid, staged, stagedJar,
                           log, debug)) {
        return false;
    }
    log(format(L"injecting into %lu", pid));
    if (!inject(pid, staged, log, debug)) {
        return false;
    }
    wchar_t tempDir[MAX_PATH];
    std::wstring embeddedJar;
    if (GetTempPathW(MAX_PATH, tempDir)) {
        wchar_t name[64];
        wsprintfW(name, L"myau_client_%lu.jar", pid);
        embeddedJar = std::wstring(tempDir) + name;
    }
    spawnReaper(pid, staged, staged + L".txt", stagedJar, embeddedJar, debug);
    debug(L"cleanup waits for the game to exit, then removes the temp files");
    log(L"loaded -- details in %TEMP%\\myau-native.log");
    return true;
}
