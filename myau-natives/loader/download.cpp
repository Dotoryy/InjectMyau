#include "download.h"

#include <winhttp.h>

#include <stdio.h>

namespace {

std::wstring describe(const wchar_t *what, DWORD error) {
    wchar_t buffer[256];
    _snwprintf_s(buffer, _TRUNCATE, L"%s (error %lu)", what, error);
    return buffer;
}

struct Handle {
    HINTERNET value = nullptr;

    ~Handle() {
        if (value) {
            WinHttpCloseHandle(value);
        }
    }
};

}

bool downloadToMemory(const std::wstring &url, std::vector<BYTE> &out,
                      const ProgressSink &progress, std::wstring &error) {
    out.clear();

    wchar_t host[256] = {0};
    wchar_t path[4096] = {0};
    wchar_t query[4096] = {0};
    URL_COMPONENTS parts = {sizeof(parts)};
    parts.lpszHostName = host;
    parts.dwHostNameLength = _countof(host);
    parts.lpszUrlPath = path;
    parts.dwUrlPathLength = _countof(path);
    parts.lpszExtraInfo = query;
    parts.dwExtraInfoLength = _countof(query);
    if (!WinHttpCrackUrl(url.c_str(), (DWORD)url.size(), 0, &parts)) {
        error = describe(L"the download address is not a URL this can read", GetLastError());
        return false;
    }

    Handle session;
    session.value = WinHttpOpen(L"Myau", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session.value) {
        error = describe(L"no network session", GetLastError());
        return false;
    }
    DWORD timeout = 30000;
    WinHttpSetTimeouts(session.value, timeout, timeout, timeout, timeout);

    Handle connection;
    connection.value = WinHttpConnect(session.value, host, parts.nPort, 0);
    if (!connection.value) {
        error = describe(L"cannot reach the server", GetLastError());
        return false;
    }

    std::wstring resource = std::wstring(path) + query;
    DWORD flags = parts.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0;
    Handle request;
    request.value = WinHttpOpenRequest(connection.value, L"GET", resource.c_str(), nullptr,
                                       WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request.value) {
        error = describe(L"cannot open the request", GetLastError());
        return false;
    }
    if (!WinHttpSendRequest(request.value, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0)
            || !WinHttpReceiveResponse(request.value, nullptr)) {
        error = describe(L"the download did not start", GetLastError());
        return false;
    }

    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    if (WinHttpQueryHeaders(request.value,
                            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                            WINHTTP_NO_HEADER_INDEX)
            && status != 200) {
        wchar_t buffer[160];
        _snwprintf_s(buffer, _TRUNCATE,
                     status == 403 || status == 404
                             ? L"the server answered %lu -- the download link has probably expired"
                             : L"the server answered %lu",
                     status);
        error = buffer;
        return false;
    }

    unsigned long long total = 0;
    wchar_t lengthText[64] = {0};
    DWORD lengthSize = sizeof(lengthText);
    if (WinHttpQueryHeaders(request.value, WINHTTP_QUERY_CONTENT_LENGTH,
                            WINHTTP_HEADER_NAME_BY_INDEX, lengthText, &lengthSize,
                            WINHTTP_NO_HEADER_INDEX)) {
        total = _wcstoui64(lengthText, nullptr, 10);
    }
    if (total > 0) {
        out.reserve((size_t)total);
    }

    for (;;) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request.value, &available)) {
            error = describe(L"the download stopped early", GetLastError());
            out.clear();
            return false;
        }
        if (available == 0) {
            break;
        }
        size_t start = out.size();
        out.resize(start + available);
        DWORD read = 0;
        if (!WinHttpReadData(request.value, out.data() + start, available, &read)) {
            error = describe(L"the download stopped early", GetLastError());
            out.clear();
            return false;
        }
        out.resize(start + read);
        if (read == 0) {
            break;
        }
        if (progress) {
            progress(out.size(), total);
        }
    }

    if (out.empty()) {
        error = L"the server sent an empty file";
        return false;
    }
    if (total > 0 && out.size() != total) {
        error = L"the download came up short -- try again";
        out.clear();
        return false;
    }
    return true;
}

FetchStatus downloadWithCache(const std::wstring &url, std::vector<BYTE> &out,
                              std::wstring &etag, std::wstring &lastModified,
                              const ProgressSink &progress, const NoticeSink &onNewVersion,
                              std::wstring &error) {
    out.clear();
    const bool conditional = !etag.empty() || !lastModified.empty();

    wchar_t host[256] = {0};
    wchar_t path[4096] = {0};
    wchar_t query[4096] = {0};
    URL_COMPONENTS parts = {sizeof(parts)};
    parts.lpszHostName = host;
    parts.dwHostNameLength = _countof(host);
    parts.lpszUrlPath = path;
    parts.dwUrlPathLength = _countof(path);
    parts.lpszExtraInfo = query;
    parts.dwExtraInfoLength = _countof(query);
    if (!WinHttpCrackUrl(url.c_str(), (DWORD)url.size(), 0, &parts)) {
        error = describe(L"the download address is not a URL this can read", GetLastError());
        return FetchStatus::Failed;
    }

    Handle session;
    session.value = WinHttpOpen(L"Myau", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session.value) {
        error = describe(L"no network session", GetLastError());
        return FetchStatus::Failed;
    }
    DWORD timeout = 30000;
    WinHttpSetTimeouts(session.value, timeout, timeout, timeout, timeout);

    Handle connection;
    connection.value = WinHttpConnect(session.value, host, parts.nPort, 0);
    if (!connection.value) {
        error = describe(L"cannot reach the server", GetLastError());
        return FetchStatus::Failed;
    }

    std::wstring resource = std::wstring(path) + query;
    DWORD flags = parts.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0;
    Handle request;
    request.value = WinHttpOpenRequest(connection.value, L"GET", resource.c_str(), nullptr,
                                       WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request.value) {
        error = describe(L"cannot open the request", GetLastError());
        return FetchStatus::Failed;
    }

    if (!etag.empty()) {
        std::wstring header = L"If-None-Match: " + etag;
        WinHttpAddRequestHeaders(request.value, header.c_str(), (DWORD)-1,
                                 WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_ADD_IF_NEW);
    }
    if (!lastModified.empty()) {
        std::wstring header = L"If-Modified-Since: " + lastModified;
        WinHttpAddRequestHeaders(request.value, header.c_str(), (DWORD)-1,
                                 WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_ADD_IF_NEW);
    }

    if (!WinHttpSendRequest(request.value, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0, 0, 0)
            || !WinHttpReceiveResponse(request.value, nullptr)) {
        error = describe(L"the download did not start", GetLastError());
        return FetchStatus::Failed;
    }

    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    WinHttpQueryHeaders(request.value, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                       WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                       WINHTTP_NO_HEADER_INDEX);

    if (status == 304) {
        return FetchStatus::NotModified;
    }
    if (status == 200 && conditional && onNewVersion) {
        onNewVersion();
    }
    if (status != 200) {
        wchar_t buffer[160];
        _snwprintf_s(buffer, _TRUNCATE,
                     status == 403 || status == 404
                             ? L"the server answered %lu -- the download link has probably expired"
                             : L"the server answered %lu",
                     status);
        error = buffer;
        return FetchStatus::Failed;
    }

    unsigned long long total = 0;
    wchar_t lengthText[64] = {0};
    DWORD lengthSize = sizeof(lengthText);
    if (WinHttpQueryHeaders(request.value, WINHTTP_QUERY_CONTENT_LENGTH,
                            WINHTTP_HEADER_NAME_BY_INDEX, lengthText, &lengthSize,
                            WINHTTP_NO_HEADER_INDEX)) {
        total = _wcstoui64(lengthText, nullptr, 10);
    }
    if (total > 0) {
        out.reserve((size_t)total);
    }

    for (;;) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request.value, &available)) {
            error = describe(L"the download stopped early", GetLastError());
            out.clear();
            return FetchStatus::Failed;
        }
        if (available == 0) {
            break;
        }
        size_t start = out.size();
        out.resize(start + available);
        DWORD read = 0;
        if (!WinHttpReadData(request.value, out.data() + start, available, &read)) {
            error = describe(L"the download stopped early", GetLastError());
            out.clear();
            return FetchStatus::Failed;
        }
        out.resize(start + read);
        if (read == 0) {
            break;
        }
        if (progress) {
            progress(out.size(), total);
        }
    }

    if (out.empty()) {
        error = L"the server sent an empty file";
        return FetchStatus::Failed;
    }
    if (total > 0 && out.size() != total) {
        error = L"the download came up short -- try again";
        out.clear();
        return FetchStatus::Failed;
    }

    wchar_t etagBuffer[256] = {0};
    DWORD etagSize = sizeof(etagBuffer);
    if (WinHttpQueryHeaders(request.value, WINHTTP_QUERY_ETAG, WINHTTP_HEADER_NAME_BY_INDEX,
                            etagBuffer, &etagSize, WINHTTP_NO_HEADER_INDEX)) {
        etag = etagBuffer;
    } else {
        etag.clear();
    }
    wchar_t modifiedBuffer[256] = {0};
    DWORD modifiedSize = sizeof(modifiedBuffer);
    if (WinHttpQueryHeaders(request.value, WINHTTP_QUERY_LAST_MODIFIED,
                            WINHTTP_HEADER_NAME_BY_INDEX, modifiedBuffer, &modifiedSize,
                            WINHTTP_NO_HEADER_INDEX)) {
        lastModified = modifiedBuffer;
    } else {
        lastModified.clear();
    }

    return FetchStatus::Downloaded;
}
