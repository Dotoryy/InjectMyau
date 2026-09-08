#pragma once

#include <windows.h>

#include <functional>
#include <string>
#include <vector>

using ProgressSink = std::function<void(unsigned long long, unsigned long long)>;

bool downloadToMemory(const std::wstring &url, std::vector<BYTE> &out,
                      const ProgressSink &progress, std::wstring &error);

enum class FetchStatus { Downloaded, NotModified, Failed };

using NoticeSink = std::function<void()>;

FetchStatus downloadWithCache(const std::wstring &url, std::vector<BYTE> &out,
                              std::wstring &etag, std::wstring &lastModified,
                              const ProgressSink &progress, const NoticeSink &onNewVersion,
                              std::wstring &error);
