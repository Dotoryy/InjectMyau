#pragma once

#include <windows.h>

#include <functional>
#include <string>

using Logger = std::function<void(const std::wstring &)>;

bool findTarget(DWORD &pid, std::wstring &launcher);
bool runInjection(const void *dllBytes, size_t dllSize,
                  const void *jarBytes, size_t jarSize,
                  DWORD explicitPid, const Logger &log, const Logger &debug);
