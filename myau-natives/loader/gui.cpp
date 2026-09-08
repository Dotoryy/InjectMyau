#include <windows.h>
#include <windowsx.h>
#include <gdiplus.h>
#include <objidl.h>
#include <dwmapi.h>
#include <shellapi.h>

#include <stdarg.h>
#include <stdio.h>

#include <atomic>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "download.h"
#include "inject.h"
#include "payload_url.h"
#include "resource.h"

namespace {

constexpr UINT WM_LOG_LINE = WM_APP + 1;
constexpr UINT WM_INJECT_DONE = WM_APP + 2;
constexpr UINT WM_PROGRESS = WM_APP + 3;
constexpr UINT WM_CHANGELOG = WM_APP + 4;
constexpr UINT_PTR TIMER_RESET = 1;
constexpr UINT_PTR TIMER_TARGET = 2;
constexpr UINT RESET_DELAY_MS = 6000;
constexpr UINT TARGET_POLL_MS = 1000;

constexpr int WINDOW_WIDTH = 980;
constexpr int WINDOW_HEIGHT = 628;

constexpr int HEADER_CENTRE_Y = 36;
constexpr int LOGO_HEIGHT = 32;
constexpr int DIVIDER_HEIGHT = 28;
constexpr int DIVIDER_GAP = 15;

constexpr int PANEL_TOP = 80;
constexpr int PANEL_BOTTOM = 562;
constexpr int LEFT_X = 30;
constexpr int LEFT_WIDTH = 436;
constexpr int RIGHT_X = 490;
constexpr int RIGHT_WIDTH = 460;

constexpr int HEADING_TOP = 74;
constexpr int CHANGELOG_TOP = 100;
constexpr int CHANGELOG_LINE = 18;
constexpr int BUTTON_TOP = 448;
constexpr int BUTTON_HEIGHT = 54;
constexpr int CHANNEL_LABEL_TOP = 512;
constexpr int CHANNEL_TOP = 530;
constexpr int CHANNEL_HEIGHT = 32;
constexpr int CHANNEL_ROW = 30;
constexpr int PROGRESS_TOP = 578;
constexpr int PROGRESS_HEIGHT = 4;
constexpr int FOOTER_TOP = 594;

constexpr int CONSOLE_STRIP = 32;
constexpr int CONSOLE_PAD = 14;
constexpr int CONSOLE_LINE = 17;
constexpr size_t CONSOLE_MAX_LINES = 400;

constexpr int CLOSE_WIDTH = 48;
constexpr int CLOSE_HEIGHT = 36;
constexpr int DRAG_HEIGHT = 66;

constexpr int CTRL_HEIGHT = 28;
constexpr int CTRL_PAD = 13;
constexpr int CTRL_GAP = 10;
constexpr int CTRL_DOT = 16;
constexpr int CONTROLS_RIGHT = WINDOW_WIDTH - CLOSE_WIDTH - 12;

constexpr int SIZE_TITLE = 26;
constexpr int SIZE_HEADING = 12;
constexpr int SIZE_ENTRY = 14;
constexpr int SIZE_BUTTON = 18;
constexpr int SIZE_SMALL = 12;
constexpr int SIZE_CONSOLE = 13;

constexpr double DOWNLOAD_SHARE = 0.9;

const wchar_t TITLE[] = L"Myau Injector";
const wchar_t LINK[] = L"https://github.com/Dotoryy/InjectMyau";

const wchar_t *const CHANGELOG_FALLBACK[] = {
    L"Improve Displace",
    L"Improve AutoBlock Module",
    L"Improve Hypixel Autoblock",
    L"Improve Optimization on Inject Client",
    L"Fixed AutoDefender Silent Flag",
    L"Fixed Inventory Move \"Watchdog\" Flagging some times",
    L"Improve Hypixel brand Disabler on Lunar does not work sometimes",
    L"Added More bridge module",
    L"Added BackTrack",
    L"Fixed Autoblock module cant calculate Hurt Time",
    L"Fixed BadPacket",
    L"Fixed Badlion Inject issue",
    L"Fixed RightClickMouseEvent does not cancel in BL/LC",
    L"Fixed some ASM not loaded in code",
    L"Improve Hypixel Bypass",
    L"Fixed Backtrack not working",
    L"Unpatched Watchdog Autoblock",
    L"Added FakeLag",
};

const COLORREF COLOUR_BACKGROUND = RGB(10, 10, 13);
const COLORREF COLOUR_CONSOLE = RGB(6, 6, 8);
const COLORREF COLOUR_BORDER = RGB(28, 29, 36);
const COLORREF COLOUR_RULE = RGB(52, 54, 62);
const COLORREF COLOUR_TEXT = RGB(236, 238, 244);
const COLORREF COLOUR_BODY = RGB(176, 181, 193);
const COLORREF COLOUR_MUTED = RGB(138, 142, 154);
const COLORREF COLOUR_DIM = RGB(92, 95, 105);
const COLORREF COLOUR_FAINT = RGB(64, 67, 75);
const COLORREF COLOUR_TRACK = RGB(30, 31, 38);
const COLORREF COLOUR_LAMP = RGB(42, 44, 52);
const COLORREF COLOUR_GREEN = RGB(46, 160, 67);
const COLORREF COLOUR_GREEN_HOT = RGB(63, 185, 80);
const COLORREF COLOUR_GREEN_DOWN = RGB(35, 134, 54);
const COLORREF COLOUR_GREEN_OFF = RGB(30, 42, 33);
const COLORREF COLOUR_GREEN_INK_OFF = RGB(104, 118, 108);
const COLORREF COLOUR_RED = RGB(214, 68, 72);
const COLORREF COLOUR_WHITE = RGB(255, 255, 255);

Gdiplus::Color gp(COLORREF colour) {
    return Gdiplus::Color(255, GetRValue(colour), GetGValue(colour), GetBValue(colour));
}

enum class Stage {
    IDLE,
    WORKING,
    DONE_OK,
    DONE_FAILED
};

enum class Ink {
    NORMAL,
    GOOD,
    BAD,
    DEBUG
};

struct Line {
    std::wstring text;
    Ink ink = Ink::NORMAL;
};

HWND g_window = nullptr;
Gdiplus::Bitmap *g_logo = nullptr;
Gdiplus::Rect g_logoContent;
ULONG_PTR g_gdiplusToken = 0;

HDC g_memory = nullptr;
HBITMAP g_memoryBitmap = nullptr;
HGDIOBJ g_memoryOld = nullptr;
HBRUSH g_backgroundBrush = nullptr;
HANDLE g_fontResource = nullptr;
std::wstring g_family = L"Segoe UI";
HFONT g_fontTitle = nullptr;
HFONT g_fontHeading = nullptr;
HFONT g_fontEntry = nullptr;
HFONT g_fontButton = nullptr;
HFONT g_fontSmall = nullptr;
HFONT g_fontConsole = nullptr;

Stage g_stage = Stage::IDLE;
bool g_buttonEnabled = false;
bool g_armed = false;
bool g_buttonHot = false;
bool g_buttonDown = false;
bool g_closeHot = false;
bool g_linkHot = false;
SIZE g_linkSize = {0, 0};
bool g_advanced = false;
bool g_advancedHot = false;
const wchar_t *const CHANNEL_NAMES[] = {L"Latest", L"Beta"};
int g_channel = 0;
bool g_channelOpen = false;
bool g_channelBoxHot = false;
int g_channelHot = -1;
SIZE g_szAdvanced = {0, 0};
SIZE g_szLatest = {0, 0};
SIZE g_szBeta = {0, 0};
std::vector<Line> g_console;
std::vector<std::wstring> g_changelog;
int g_changelogScroll = 0;
std::atomic<unsigned> g_changelogGeneration{0};
void useFallbackChangelog() {
    g_changelog.clear();
    for (const wchar_t *line : CHANGELOG_FALLBACK) {
        g_changelog.push_back(line);
    }
}
double g_progress = -1.0;
DWORD g_targetPid = 0;
std::wstring g_targetLauncher;

void enterStage(Stage stage);
DWORD WINAPI injectThread(LPVOID);

std::wstring format(const wchar_t *pattern, ...) {
    wchar_t buffer[512];
    va_list args;
    va_start(args, pattern);
    _vsnwprintf_s(buffer, _TRUNCATE, pattern, args);
    va_end(args);
    return buffer;
}

struct Payload {
    const void *bytes = nullptr;
    DWORD size = 0;
};

Payload payload(int id) {
    Payload out;
    HRSRC found = FindResourceW(nullptr, MAKEINTRESOURCEW(id), MAKEINTRESOURCEW(10));
    if (!found) {
        return out;
    }
    HGLOBAL loaded = LoadResource(nullptr, found);
    if (!loaded) {
        return out;
    }
    out.bytes = LockResource(loaded);
    out.size = SizeofResource(nullptr, found);
    return out;
}

std::wstring sidecarOr(const wchar_t *fileName, const wchar_t *fallback) {
    wchar_t exePath[MAX_PATH];
    DWORD length = GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    if (length > 0 && length < MAX_PATH) {
        std::wstring path(exePath, length);
        size_t slash = path.find_last_of(L"\\/");
        if (slash != std::wstring::npos) {
            path = path.substr(0, slash + 1) + fileName;
            HANDLE handle = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (handle != INVALID_HANDLE_VALUE) {
                char raw[8192];
                DWORD read = 0;
                BOOL ok = ReadFile(handle, raw, sizeof(raw) - 1, &read, nullptr);
                CloseHandle(handle);
                if (ok && read > 0) {
                    raw[read] = 0;
                    int wide = MultiByteToWideChar(CP_UTF8, 0, raw, (int)read, nullptr, 0);
                    std::wstring text(wide, 0);
                    MultiByteToWideChar(CP_UTF8, 0, raw, (int)read, &text[0], wide);
                    size_t first = text.find_first_not_of(L" \t\r\n");
                    size_t last = text.find_last_not_of(L" \t\r\n");
                    if (first != std::wstring::npos) {
                        return text.substr(first, last - first + 1);
                    }
                }
            }
        }
    }
    return fallback;
}
std::wstring payloadUrl() {
    if (g_channel == 1) {
        return sidecarOr(L"myau_beta_url.txt", MYAU_BETA_URL);
    }
    return sidecarOr(L"myau_url.txt", MYAU_PAYLOAD_URL);
}
std::wstring changelogUrl(int channel) {
    std::wstring url = channel == 1
            ? sidecarOr(L"myau_changelog_beta_url.txt", MYAU_BETA_CHANGELOG_URL)
            : sidecarOr(L"myau_changelog_url.txt", MYAU_CHANGELOG_URL);
    const std::wstring host = L"pastebin.com/";
    size_t at = url.find(host);
    if (at != std::wstring::npos) {
        size_t id = at + host.size();
        if (url.compare(id, 4, L"raw/") != 0) {
            url.insert(id, L"raw/");
        }
    }
    return url;
}
std::wstring cacheDllPath() {
    wchar_t tempDir[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, tempDir)) {
        return L"";
    }
    return std::wstring(tempDir)
            + (g_channel == 1 ? L"myau_native_beta_cache.bin" : L"myau_native_cache.bin");
}
std::wstring cacheMetaPath() {
    std::wstring dll = cacheDllPath();
    return dll.empty() ? L"" : dll + L".meta";
}
bool readFileBytes(const std::wstring &path, std::vector<BYTE> &out) {
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        return false;
    }
    LARGE_INTEGER size = {};
    if (!GetFileSizeEx(handle, &size) || size.QuadPart <= 0) {
        CloseHandle(handle);
        return false;
    }
    out.resize((size_t)size.QuadPart);
    DWORD read = 0;
    BOOL ok = ReadFile(handle, out.data(), (DWORD)out.size(), &read, nullptr);
    CloseHandle(handle);
    if (!ok || read != out.size()) {
        out.clear();
        return false;
    }
    return true;
}
bool writeFileBytes(const std::wstring &path, const std::vector<BYTE> &data) {
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        return false;
    }
    DWORD written = 0;
    BOOL ok = WriteFile(handle, data.data(), (DWORD)data.size(), &written, nullptr);
    CloseHandle(handle);
    return ok && written == data.size();
}
bool readCacheMeta(std::wstring &etag, std::wstring &lastModified) {
    std::wstring path = cacheMetaPath();
    if (path.empty()) {
        return false;
    }
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        return false;
    }
    char raw[1024] = {0};
    DWORD read = 0;
    BOOL ok = ReadFile(handle, raw, sizeof(raw) - 1, &read, nullptr);
    CloseHandle(handle);
    if (!ok || read == 0) {
        return false;
    }
    raw[read] = 0;
    int wide = MultiByteToWideChar(CP_UTF8, 0, raw, (int)read, nullptr, 0);
    std::wstring text(wide, 0);
    MultiByteToWideChar(CP_UTF8, 0, raw, (int)read, &text[0], wide);
    size_t split = text.find(L'\n');
    if (split == std::wstring::npos) {
        return false;
    }
    etag = text.substr(0, split);
    lastModified = text.substr(split + 1);
    auto trim = [](std::wstring &s) {
        size_t last = s.find_last_not_of(L" \t\r\n");
        s = last == std::wstring::npos ? L"" : s.substr(0, last + 1);
    };
    trim(etag);
    trim(lastModified);
    return true;
}
void writeCacheMeta(const std::wstring &etag, const std::wstring &lastModified) {
    std::wstring path = cacheMetaPath();
    if (path.empty()) {
        return;
    }
    std::wstring text = etag + L"\n" + lastModified;
    int size = WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int)text.size(), nullptr, 0,
                                   nullptr, nullptr);
    std::string utf8(size, 0);
    WideCharToMultiByte(CP_UTF8, 0, text.c_str(), (int)text.size(), &utf8[0], size, nullptr,
                        nullptr);
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        return;
    }
    DWORD written = 0;
    WriteFile(handle, utf8.data(), (DWORD)utf8.size(), &written, nullptr);
    CloseHandle(handle);
}

void loadFont() {
    Payload ttf = payload(IDR_FONT);
    if (!ttf.bytes || ttf.size == 0) {
        return;
    }
    DWORD installed = 0;
    g_fontResource = AddFontMemResourceEx(const_cast<void *>(ttf.bytes), ttf.size, nullptr,
                                          &installed);
    if (!g_fontResource || installed == 0) {
        return;
    }
    Gdiplus::PrivateFontCollection collection;
    if (collection.AddMemoryFont(ttf.bytes, (INT)ttf.size) != Gdiplus::Ok) {
        return;
    }
    INT count = collection.GetFamilyCount();
    if (count <= 0) {
        return;
    }
    std::unique_ptr<Gdiplus::FontFamily[]> families(new Gdiplus::FontFamily[count]);
    INT found = 0;
    if (collection.GetFamilies(count, families.get(), &found) != Gdiplus::Ok || found <= 0) {
        return;
    }
    wchar_t name[LF_FACESIZE] = {0};
    if (families[0].GetFamilyName(name) == Gdiplus::Ok) {
        g_family = name;
    }
}

HFONT createFont(const wchar_t *family, int pixels) {
    return CreateFontW(-pixels, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                       OUT_TT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                       DEFAULT_PITCH | FF_DONTCARE, family);
}
void createFonts() {
    g_fontTitle = createFont(g_family.c_str(), SIZE_TITLE);
    g_fontHeading = createFont(g_family.c_str(), SIZE_HEADING);
    g_fontEntry = createFont(g_family.c_str(), SIZE_ENTRY);
    g_fontButton = createFont(g_family.c_str(), SIZE_BUTTON);
    g_fontSmall = createFont(g_family.c_str(), SIZE_SMALL);
    g_fontConsole = createFont(L"Consolas", SIZE_CONSOLE);
}
void destroyFonts() {
    for (HFONT *font : {&g_fontTitle, &g_fontHeading, &g_fontEntry, &g_fontButton, &g_fontSmall,
                        &g_fontConsole}) {
        if (*font) {
            DeleteObject(*font);
            *font = nullptr;
        }
    }
}

SIZE measureText(HDC dc, HFONT font, const wchar_t *text) {
    HGDIOBJ previous = SelectObject(dc, font);
    SIZE size = {0, 0};
    GetTextExtentPoint32W(dc, text, (int)wcslen(text), &size);
    SelectObject(dc, previous);
    return size;
}
void drawText(HDC dc, HFONT font, COLORREF colour, int x, int y, const wchar_t *text) {
    HGDIOBJ previous = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, colour);
    TextOutW(dc, x, y, text, (int)wcslen(text));
    SelectObject(dc, previous);
}
void fillRect(HDC dc, RECT box, COLORREF colour) {
    HBRUSH brush = CreateSolidBrush(colour);
    FillRect(dc, &box, brush);
    DeleteObject(brush);
}

int changelogRoom() {
    int room = (BUTTON_TOP - 12 - CHANGELOG_TOP) / CHANGELOG_LINE;
    return room < 1 ? 1 : room;
}

void clampChangelogScroll() {
    int maxScroll = (int)g_changelog.size() - changelogRoom();
    if (maxScroll < 0) {
        maxScroll = 0;
    }
    if (g_changelogScroll > maxScroll) {
        g_changelogScroll = maxScroll;
    }
    if (g_changelogScroll < 0) {
        g_changelogScroll = 0;
    }
}

bool scrollChangelog(int lines) {
    int before = g_changelogScroll;
    g_changelogScroll += lines;
    clampChangelogScroll();
    return g_changelogScroll != before;
}

void drawTextIn(HDC dc, HFONT font, COLORREF colour, RECT box, const wchar_t *text) {
    HGDIOBJ previous = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, colour);
    DrawTextW(dc, text, -1, &box,
              DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX | DT_END_ELLIPSIS);
    SelectObject(dc, previous);
}

Gdiplus::Bitmap *loadLogo() {
    Payload png = payload(IDR_LOGO);
    if (!png.bytes || png.size == 0) {
        return nullptr;
    }
    HGLOBAL buffer = GlobalAlloc(GMEM_MOVEABLE, png.size);
    if (!buffer) {
        return nullptr;
    }
    void *target = GlobalLock(buffer);
    memcpy(target, png.bytes, png.size);
    GlobalUnlock(buffer);
    IStream *stream = nullptr;
    if (CreateStreamOnHGlobal(buffer, TRUE, &stream) != S_OK) {
        GlobalFree(buffer);
        return nullptr;
    }
    Gdiplus::Bitmap *image = Gdiplus::Bitmap::FromStream(stream);
    stream->Release();
    if (image && image->GetLastStatus() != Gdiplus::Ok) {
        delete image;
        return nullptr;
    }
    return image;
}
Gdiplus::Rect trimToContent(Gdiplus::Bitmap *image) {
    Gdiplus::Rect whole(0, 0, (INT)image->GetWidth(), (INT)image->GetHeight());
    Gdiplus::BitmapData data;
    if (image->LockBits(&whole, Gdiplus::ImageLockModeRead, PixelFormat32bppARGB, &data)
            != Gdiplus::Ok) {
        return whole;
    }
    int left = whole.Width;
    int top = whole.Height;
    int right = -1;
    int bottom = -1;
    for (int y = 0; y < whole.Height; y++) {
        const BYTE *row = (const BYTE *)data.Scan0 + (INT_PTR)y * data.Stride;
        for (int x = 0; x < whole.Width; x++) {
            if (row[x * 4 + 3] <= 16) {
                continue;
            }
            if (x < left) left = x;
            if (x > right) right = x;
            if (y < top) top = y;
            if (y > bottom) bottom = y;
        }
    }
    image->UnlockBits(&data);
    if (right < left || bottom < top) {
        return whole;
    }
    return Gdiplus::Rect(left, top, right - left + 1, bottom - top + 1);
}

void addRoundedRect(Gdiplus::GraphicsPath &path, float x, float y, float width, float height,
                    float radius) {
    if (radius * 2.0f > height) radius = height / 2.0f;
    if (radius * 2.0f > width) radius = width / 2.0f;
    if (radius <= 0.0f) {
        path.AddRectangle(Gdiplus::RectF(x, y, width, height));
        return;
    }
    float diameter = radius * 2.0f;
    path.AddArc(x, y, diameter, diameter, 180, 90);
    path.AddArc(x + width - diameter, y, diameter, diameter, 270, 90);
    path.AddArc(x + width - diameter, y + height - diameter, diameter, diameter, 0, 90);
    path.AddArc(x, y + height - diameter, diameter, diameter, 90, 90);
    path.CloseFigure();
}

RECT closeRect() {
    RECT box = {WINDOW_WIDTH - CLOSE_WIDTH, 0, WINDOW_WIDTH, CLOSE_HEIGHT};
    return box;
}
RECT buttonRect() {
    RECT box = {LEFT_X, BUTTON_TOP, LEFT_X + LEFT_WIDTH, BUTTON_TOP + BUTTON_HEIGHT};
    return box;
}
RECT progressRect() {
    RECT box = {LEFT_X, PROGRESS_TOP, LEFT_X + LEFT_WIDTH, PROGRESS_TOP + PROGRESS_HEIGHT};
    return box;
}
RECT linkRect() {
    RECT box = {LEFT_X, FOOTER_TOP, LEFT_X + (int)g_linkSize.cx, FOOTER_TOP + (int)g_linkSize.cy};
    return box;
}
RECT consoleRect() {
    RECT box = {RIGHT_X, PANEL_TOP, RIGHT_X + RIGHT_WIDTH, PANEL_BOTTOM};
    return box;
}
RECT advancedRect() {
    const int top = HEADER_CENTRE_Y - CTRL_HEIGHT / 2;
    int advWidth = (int)g_szAdvanced.cx + CTRL_PAD * 2 + CTRL_DOT;
    return {CONTROLS_RIGHT - advWidth, top, CONTROLS_RIGHT, top + CTRL_HEIGHT};
}
RECT channelBoxRect() {
    return {LEFT_X, CHANNEL_TOP, LEFT_X + LEFT_WIDTH, CHANNEL_TOP + CHANNEL_HEIGHT};
}
RECT channelOptionRect(int index) {
    int top = CHANNEL_TOP + CHANNEL_HEIGHT + index * CHANNEL_ROW;
    return {LEFT_X, top, LEFT_X + LEFT_WIDTH, top + CHANNEL_ROW};
}
RECT channelRegionRect() {
    return {LEFT_X, CHANNEL_LABEL_TOP, LEFT_X + LEFT_WIDTH,
            CHANNEL_TOP + CHANNEL_HEIGHT + 2 * CHANNEL_ROW + 2};
}
const wchar_t *buttonLabel() {
    switch (g_stage) {
        case Stage::WORKING:
            return L"Working";
        case Stage::DONE_OK:
            return L"Loaded";
        default:
            return g_armed ? L"Waiting for game" : L"Inject";
    }
}
struct ButtonLayout {
    float iconLeft;
    int textLeft;
    float centreY;
};
ButtonLayout buttonLayout(HDC dc) {
    RECT box = buttonRect();
    SIZE label = measureText(dc, g_fontButton, buttonLabel());
    const float iconWidth = 13.0f;
    const float gap = 12.0f;
    float width = (float)(box.right - box.left);
    float left = box.left + (width - (iconWidth + gap + label.cx)) / 2.0f;
    ButtonLayout layout;
    layout.iconLeft = left;
    layout.textLeft = (int)(left + iconWidth + gap + 0.5f);
    layout.centreY = (box.top + box.bottom) / 2.0f;
    return layout;
}
float logoWidthFor(float height) {
    if (!g_logo || g_logoContent.Width <= 0 || g_logoContent.Height <= 0) {
        return 0.0f;
    }
    return (float)(g_logoContent.Width * (height / g_logoContent.Height));
}

void paintShapes(HDC dc) {
    ButtonLayout layout = buttonLayout(dc);

    Gdiplus::Graphics graphics(dc);
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
    graphics.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
    graphics.SetCompositingQuality(Gdiplus::CompositingQualityHighQuality);

    float logoHeight = (float)LOGO_HEIGHT;
    float logoWidth = logoWidthFor(logoHeight);
    const float widest = LOGO_HEIGHT * 1.6f;
    if (logoWidth > widest) {
        logoHeight = (float)(g_logoContent.Height * (widest / g_logoContent.Width));
        logoWidth = widest;
    }
    if (logoWidth > 0.0f) {
        graphics.DrawImage(g_logo,
                           Gdiplus::RectF((float)LEFT_X, HEADER_CENTRE_Y - logoHeight / 2.0f,
                                          logoWidth, logoHeight),
                           (Gdiplus::REAL)g_logoContent.X, (Gdiplus::REAL)g_logoContent.Y,
                           (Gdiplus::REAL)g_logoContent.Width, (Gdiplus::REAL)g_logoContent.Height,
                           Gdiplus::UnitPixel);
        Gdiplus::SolidBrush rule(gp(COLOUR_RULE));
        graphics.FillRectangle(&rule, LEFT_X + logoWidth + DIVIDER_GAP,
                               HEADER_CENTRE_Y - DIVIDER_HEIGHT / 2.0f,
                               1.0f, (float)DIVIDER_HEIGHT);
    }

    RECT close = closeRect();
    if (g_closeHot) {
        Gdiplus::GraphicsPath path;
        addRoundedRect(path, (float)close.left, (float)close.top,
                       (float)(close.right - close.left), (float)(close.bottom - close.top), 8.0f);
        Gdiplus::SolidBrush hot(gp(COLOUR_RED));
        graphics.FillPath(&hot, &path);
    }
    Gdiplus::Pen cross(gp(g_closeHot ? COLOUR_WHITE : COLOUR_MUTED), 1.4f);
    float crossX = (close.left + close.right) / 2.0f;
    float crossY = (close.top + close.bottom) / 2.0f;
    const float arm = 5.0f;
    graphics.DrawLine(&cross, crossX - arm, crossY - arm, crossX + arm, crossY + arm);
    graphics.DrawLine(&cross, crossX + arm, crossY - arm, crossX - arm, crossY + arm);

    RECT button = buttonRect();
    Gdiplus::GraphicsPath face;
    addRoundedRect(face, (float)button.left, (float)button.top,
                   (float)(button.right - button.left), (float)(button.bottom - button.top), 11.0f);
    COLORREF faceColour = !g_buttonEnabled
            ? COLOUR_GREEN_OFF
            : (g_buttonDown ? COLOUR_GREEN_DOWN : (g_buttonHot ? COLOUR_GREEN_HOT : COLOUR_GREEN));
    Gdiplus::SolidBrush faceInk(gp(faceColour));
    graphics.FillPath(&faceInk, &face);

    const float iconHeight = 15.0f;
    Gdiplus::PointF play[3] = {
        Gdiplus::PointF(layout.iconLeft, layout.centreY - iconHeight / 2.0f),
        Gdiplus::PointF(layout.iconLeft, layout.centreY + iconHeight / 2.0f),
        Gdiplus::PointF(layout.iconLeft + 13.0f, layout.centreY),
    };
    Gdiplus::SolidBrush iconInk(gp(g_buttonEnabled ? COLOUR_WHITE : COLOUR_GREEN_INK_OFF));
    graphics.FillPolygon(&iconInk, play, 3);

    if (g_progress >= 0.0) {
        RECT bar = progressRect();
        float x = (float)bar.left;
        float y = (float)bar.top;
        float width = (float)(bar.right - bar.left);
        float height = (float)(bar.bottom - bar.top);
        Gdiplus::GraphicsPath track;
        addRoundedRect(track, x, y, width, height, height / 2.0f);
        Gdiplus::SolidBrush trackInk(gp(COLOUR_TRACK));
        graphics.FillPath(&trackInk, &track);
        double fraction = g_progress > 1.0 ? 1.0 : g_progress;
        if (fraction > 0.0) {
            float filled = (float)(width * fraction);
            if (filled < height) {
                filled = height;
            }
            Gdiplus::GraphicsPath done;
            addRoundedRect(done, x, y, filled, height, height / 2.0f);
            Gdiplus::SolidBrush doneInk(
                    gp(g_stage == Stage::DONE_FAILED ? COLOUR_RED : COLOUR_GREEN_HOT));
            graphics.FillPath(&doneInk, &done);
        }
    }

    RECT console = consoleRect();
    float cx = (float)console.left;
    float cy = (float)console.top;
    float cw = (float)(console.right - console.left);
    float ch = (float)(console.bottom - console.top);
    Gdiplus::GraphicsPath frame;
    addRoundedRect(frame, cx + 0.5f, cy + 0.5f, cw - 1.0f, ch - 1.0f, 10.0f);
    Gdiplus::SolidBrush back(gp(COLOUR_CONSOLE));
    graphics.FillPath(&back, &frame);
    Gdiplus::Pen border(gp(COLOUR_BORDER), 1.0f);
    graphics.DrawPath(&border, &frame);
    Gdiplus::SolidBrush lamp(gp(COLOUR_LAMP));
    for (int i = 0; i < 3; i++) {
        graphics.FillEllipse(&lamp, cx + 15.0f + i * 15.0f, cy + CONSOLE_STRIP / 2.0f - 4.0f,
                             8.0f, 8.0f);
    }
    Gdiplus::SolidBrush strip(gp(COLOUR_BORDER));
    graphics.FillRectangle(&strip, cx + 1.0f, cy + CONSOLE_STRIP, cw - 2.0f, 1.0f);

    RECT adv = advancedRect();
    Gdiplus::GraphicsPath advFrame;
    addRoundedRect(advFrame, (float)adv.left + 0.5f, (float)adv.top + 0.5f,
                   (float)(adv.right - adv.left) - 1.0f, (float)(adv.bottom - adv.top) - 1.0f, 8.0f);
    Gdiplus::SolidBrush advBack(gp(g_advanced ? COLOUR_GREEN
                                              : (g_advancedHot ? COLOUR_TRACK : COLOUR_CONSOLE)));
    graphics.FillPath(&advBack, &advFrame);
    Gdiplus::Pen advBorder(gp(g_advanced ? COLOUR_GREEN : COLOUR_BORDER), 1.0f);
    graphics.DrawPath(&advBorder, &advFrame);
    float dotCx = adv.left + CTRL_PAD + 5.0f;
    float dotCy = (adv.top + adv.bottom) / 2.0f;
    Gdiplus::SolidBrush dotInk(gp(g_advanced ? COLOUR_WHITE : COLOUR_DIM));
    graphics.FillEllipse(&dotInk, dotCx - 3.0f, dotCy - 3.0f, 6.0f, 6.0f);
}

void paintText(HDC dc) {
    float logoHeight = (float)LOGO_HEIGHT;
    float logoWidth = logoWidthFor(logoHeight);
    const float widest = LOGO_HEIGHT * 1.6f;
    if (logoWidth > widest) {
        logoWidth = widest;
    }
    int titleLeft = LEFT_X;
    if (logoWidth > 0.0f) {
        titleLeft = (int)(LEFT_X + logoWidth + DIVIDER_GAP * 2.0f + 1.0f + 0.5f);
    }
    SIZE title = measureText(dc, g_fontTitle, TITLE);
    drawText(dc, g_fontTitle, COLOUR_TEXT, titleLeft, HEADER_CENTRE_Y - title.cy / 2, TITLE);

    drawText(dc, g_fontHeading, COLOUR_DIM, LEFT_X, HEADING_TOP, L"CHANGELOG");
    SIZE mark = measureText(dc, g_fontEntry, L"+");
    const int indent = mark.cx + 9;
    const int room = changelogRoom();
    const int total = (int)g_changelog.size();
    clampChangelogScroll();
    const int clFirst = g_changelogScroll;
    int clLast = clFirst + room;
    if (clLast > total) {
        clLast = total;
    }
    int y = CHANGELOG_TOP;
    for (int i = clFirst; i < clLast; i++) {
        RECT markBox = {LEFT_X, y, LEFT_X + indent, y + CHANGELOG_LINE};
        drawTextIn(dc, g_fontEntry, COLOUR_GREEN_HOT, markBox, L"+");
        RECT lineBox = {LEFT_X + indent, y, LEFT_X + LEFT_WIDTH, y + CHANGELOG_LINE};
        drawTextIn(dc, g_fontEntry, COLOUR_BODY, lineBox, g_changelog[i].c_str());
        y += CHANGELOG_LINE;
    }
    if (total > room) {
        const int trackTop = CHANGELOG_TOP + 2;
        const int trackHeight = room * CHANGELOG_LINE - 4;
        const int barX = LEFT_X + LEFT_WIDTH - 3;
        int barHeight = trackHeight * room / total;
        if (barHeight < 18) {
            barHeight = 18;
        }
        const int span = trackHeight - barHeight;
        const int maxScroll = total - room;
        const int barY = trackTop + (maxScroll > 0 ? span * clFirst / maxScroll : 0);
        RECT track = {barX, trackTop, barX + 3, trackTop + trackHeight};
        fillRect(dc, track, COLOUR_TRACK);
        RECT bar = {barX, barY, barX + 3, barY + barHeight};
        fillRect(dc, bar, COLOUR_FAINT);
    }

    ButtonLayout layout = buttonLayout(dc);
    SIZE label = measureText(dc, g_fontButton, buttonLabel());
    drawText(dc, g_fontButton, g_buttonEnabled ? COLOUR_WHITE : COLOUR_GREEN_INK_OFF,
             layout.textLeft, (int)(layout.centreY - label.cy / 2.0f + 0.5f), buttonLabel());

    RECT adv = advancedRect();
    RECT advText = {adv.left + CTRL_PAD + CTRL_DOT, adv.top, adv.right - CTRL_PAD + 4, adv.bottom};
    drawTextIn(dc, g_fontSmall, g_advanced ? COLOUR_WHITE : COLOUR_MUTED, advText, L"Advanced");

    COLORREF linkInk = g_linkHot ? COLOUR_BODY : COLOUR_DIM;
    drawText(dc, g_fontSmall, linkInk, LEFT_X, FOOTER_TOP, LINK);
    if (g_linkHot) {
        RECT rule = {LEFT_X, FOOTER_TOP + (int)g_linkSize.cy - 1,
                     LEFT_X + (int)g_linkSize.cx, FOOTER_TOP + (int)g_linkSize.cy};
        HBRUSH brush = CreateSolidBrush(linkInk);
        FillRect(dc, &rule, brush);
        DeleteObject(brush);
    }

    RECT console = consoleRect();
    drawText(dc, g_fontSmall, COLOUR_DIM, console.left + 72,
             console.top + CONSOLE_STRIP / 2 - SIZE_SMALL / 2 - 1, L"log");

    const int textTop = console.top + CONSOLE_STRIP + CONSOLE_PAD;
    const int textLeft = console.left + CONSOLE_PAD + 2;
    const int textRight = console.right - CONSOLE_PAD - 2;
    const int visible = (console.bottom - CONSOLE_PAD - textTop) / CONSOLE_LINE;
    if (visible <= 0 || g_console.empty()) {
        return;
    }
    size_t first = g_console.size() > (size_t)visible ? g_console.size() - visible : 0;
    int lineY = textTop;
    for (size_t i = first; i < g_console.size(); i++) {
        const Line &line = g_console[i];
        COLORREF colour = line.ink == Ink::GOOD ? COLOUR_GREEN_HOT
                        : line.ink == Ink::BAD ? COLOUR_RED
                        : line.ink == Ink::DEBUG ? COLOUR_FAINT : COLOUR_MUTED;
        RECT box = {textLeft, lineY, textRight, lineY + CONSOLE_LINE};
        drawTextIn(dc, g_fontConsole, colour, box, (L"> " + line.text).c_str());
        lineY += CONSOLE_LINE;
    }
}

void paintDropdown(HDC dc) {
    RECT box = channelBoxRect();
    {
        Gdiplus::Graphics graphics(dc);
        graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);

        Gdiplus::GraphicsPath frame;
        addRoundedRect(frame, (float)box.left + 0.5f, (float)box.top + 0.5f,
                       (float)(box.right - box.left) - 1.0f,
                       (float)(box.bottom - box.top) - 1.0f, 9.0f);
        Gdiplus::SolidBrush back(gp(g_channelBoxHot || g_channelOpen ? COLOUR_TRACK
                                                                     : COLOUR_CONSOLE));
        graphics.FillPath(&back, &frame);
        Gdiplus::Pen border(gp(g_channelOpen ? COLOUR_GREEN : COLOUR_BORDER), 1.0f);
        graphics.DrawPath(&border, &frame);

        float chevronX = (float)(box.right - 18);
        float chevronY = (box.top + box.bottom) / 2.0f;
        Gdiplus::Pen chevron(gp(COLOUR_MUTED), 1.6f);
        if (g_channelOpen) {
            graphics.DrawLine(&chevron, chevronX - 4.0f, chevronY + 2.0f, chevronX, chevronY - 2.0f);
            graphics.DrawLine(&chevron, chevronX, chevronY - 2.0f, chevronX + 4.0f, chevronY + 2.0f);
        } else {
            graphics.DrawLine(&chevron, chevronX - 4.0f, chevronY - 2.0f, chevronX, chevronY + 2.0f);
            graphics.DrawLine(&chevron, chevronX, chevronY + 2.0f, chevronX + 4.0f, chevronY - 2.0f);
        }

        if (g_channelOpen) {
            RECT list = {LEFT_X, box.bottom, LEFT_X + LEFT_WIDTH,
                         channelOptionRect(1).bottom};
            Gdiplus::GraphicsPath listFrame;
            addRoundedRect(listFrame, (float)list.left + 0.5f, (float)list.top + 0.5f,
                           (float)(list.right - list.left) - 1.0f,
                           (float)(list.bottom - list.top) - 1.0f, 9.0f);
            Gdiplus::SolidBrush listBack(gp(COLOUR_CONSOLE));
            graphics.FillPath(&listBack, &listFrame);
            for (int i = 0; i < 2; i++) {
                if (i == g_channelHot) {
                    RECT opt = channelOptionRect(i);
                    Gdiplus::GraphicsPath hot;
                    addRoundedRect(hot, (float)opt.left + 3.0f, (float)opt.top + 2.0f,
                                   (float)(opt.right - opt.left) - 6.0f,
                                   (float)(opt.bottom - opt.top) - 4.0f, 6.0f);
                    Gdiplus::SolidBrush hotInk(gp(COLOUR_TRACK));
                    graphics.FillPath(&hotInk, &hot);
                }
            }
            Gdiplus::Pen listBorder(gp(COLOUR_BORDER), 1.0f);
            graphics.DrawPath(&listBorder, &listFrame);
        }
    }

    drawText(dc, g_fontHeading, COLOUR_DIM, LEFT_X, CHANNEL_LABEL_TOP, L"RELEASE CHANNEL");
    RECT value = {box.left + 14, box.top, box.right - 24, box.bottom};
    drawTextIn(dc, g_fontEntry, COLOUR_TEXT, value, CHANNEL_NAMES[g_channel]);
    if (g_channelOpen) {
        for (int i = 0; i < 2; i++) {
            RECT opt = channelOptionRect(i);
            RECT text = {opt.left + 14, opt.top, opt.right - 14, opt.bottom};
            COLORREF ink = i == g_channel ? COLOUR_GREEN_HOT
                         : i == g_channelHot ? COLOUR_TEXT : COLOUR_BODY;
            drawTextIn(dc, g_fontEntry, ink, text, CHANNEL_NAMES[i]);
        }
    }
}

Ink inkFor(const std::wstring &text) {
    static const wchar_t *const bad[] = {L"error", L"cannot", L"failed", L"expired", L"did not",
                                         L"stopped", L"short", L"not a Windows", L"empty file",
                                         L"32-bit", L"None of these"};
    for (const wchar_t *needle : bad) {
        if (text.find(needle) != std::wstring::npos) {
            return Ink::BAD;
        }
    }
    if (text.find(L"loaded") != std::wstring::npos) {
        return Ink::GOOD;
    }
    return Ink::NORMAL;
}
void addLine(const std::wstring &text, bool replaceLast, bool isDebug) {
    Ink ink = inkFor(text);
    if (isDebug && ink != Ink::BAD) {
        ink = Ink::DEBUG;
    }
    if (replaceLast && !g_console.empty()) {
        g_console.back() = Line{text, ink};
    } else {
        g_console.push_back(Line{text, ink});
        if (g_console.size() > CONSOLE_MAX_LINES) {
            g_console.erase(g_console.begin(),
                            g_console.begin() + (g_console.size() - CONSOLE_MAX_LINES));
        }
    }
    if (g_window) {
        RECT box = consoleRect();
        InvalidateRect(g_window, &box, FALSE);
    }
}
void postLine(const std::wstring &line) {
    PostMessageW(g_window, WM_LOG_LINE, 0, (LPARAM) new std::wstring(line));
}
void postSameLine(const std::wstring &line) {
    PostMessageW(g_window, WM_LOG_LINE, 1, (LPARAM) new std::wstring(line));
}
void postDebug(const std::wstring &line) {
    if (!g_advanced) {
        return;
    }
    PostMessageW(g_window, WM_LOG_LINE, 2, (LPARAM) new std::wstring(line));
}
void postProgress(double fraction) {
    if (fraction < 0.0) fraction = 0.0;
    if (fraction > 1.0) fraction = 1.0;
    PostMessageW(g_window, WM_PROGRESS, (WPARAM)(fraction * 1000.0 + 0.5), 0);
}
void refreshTarget() {
    if (g_stage == Stage::WORKING || g_stage == Stage::DONE_OK) {
        return;
    }
    DWORD pid = 0;
    std::wstring launcher;
    if (!findTarget(pid, launcher)) {
        if (g_targetPid != 0) {
            g_targetPid = 0;
            g_targetLauncher.clear();
            addLine(g_armed ? L"the game is gone -- still waiting"
                            : L"the game is gone -- waiting for another", false, false);
        }
        return;
    }
    if (pid == g_targetPid && launcher == g_targetLauncher) {
        return;
    }
    g_targetPid = pid;
    g_targetLauncher = launcher;
    addLine(format(L"found %s -- pid %lu", launcher.c_str(), pid), false, false);
    if (g_armed) {
        g_armed = false;
        addLine(L"game detected -- injecting", false, false);
        enterStage(Stage::WORKING);
        CloseHandle(CreateThread(nullptr, 0, injectThread, nullptr, 0, nullptr));
        return;
    }
    RECT box = buttonRect();
    InvalidateRect(g_window, &box, FALSE);
}
DWORD WINAPI injectThread(LPVOID) {
    std::wstring url = payloadUrl();
    std::vector<BYTE> library;
    std::wstring error;
    postProgress(0.0);
    postDebug(L"advanced logging on");
    postDebug(g_channel == 1 ? L"channel: Beta" : L"channel: Latest");
    postDebug(format(L"payload url: %s", url.substr(0, 78).c_str()));
    postDebug(format(L"target: pid %lu (%s)", g_targetPid, g_targetLauncher.c_str()));

    std::wstring cachePath = cacheDllPath();
    std::vector<BYTE> cached;
    bool haveCache = !cachePath.empty() && readFileBytes(cachePath, cached);
    std::wstring etag, lastModified;
    if (haveCache) {
        readCacheMeta(etag, lastModified);
    }
    postDebug(format(L"cached copy: %s", haveCache ? L"present" : L"none"));
    postLine(L"connecting...");

    int lastPercent = -1;
    bool newVersion = false;
    FetchStatus status = downloadWithCache(
            url, library, etag, lastModified,
            [&lastPercent](unsigned long long done, unsigned long long total) {
                if (total == 0) {
                    return;
                }
                int percent = (int)(done * 100 / total);
                if (percent == lastPercent) {
                    return;
                }
                bool first = lastPercent < 0;
                lastPercent = percent;
                postProgress(DOWNLOAD_SHARE * done / (double)total);
                std::wstring line = format(L"downloading  %3d%%   %.1f / %.1f MB", percent,
                                           done / 1048576.0, total / 1048576.0);
                if (first) {
                    postLine(line);
                } else {
                    postSameLine(line);
                }
            },
            [&newVersion]() {
                newVersion = true;
                postLine(L"New Version Detected, Downloading Latest Version..");
            },
            error);

    if (status == FetchStatus::Downloaded) {
        if (!cachePath.empty()) {
            writeFileBytes(cachePath, library);
            writeCacheMeta(etag, lastModified);
        }
    } else if (status == FetchStatus::NotModified) {
        if (haveCache) {
            library = cached;
            postDebug(L"server says unchanged -- reusing cached copy");
            postLine(L"cached copy is up to date");
        } else {
            error = L"server says not modified but no cached copy exists";
            status = FetchStatus::Failed;
        }
    }
    if (status == FetchStatus::Failed) {
        if (haveCache) {
            postDebug(format(L"download failed (%s) -- falling back to cached copy",
                             error.c_str()));
            postLine(L"download failed -- using cached copy");
            library = cached;
        } else {
            postLine(error);
            PostMessageW(g_window, WM_INJECT_DONE, FALSE, 0);
            return 0;
        }
    }
    if (library.size() < 2 || library[0] != 'M' || library[1] != 'Z') {
        postLine(L"what came back is not a Windows library -- check the download address");
        PostMessageW(g_window, WM_INJECT_DONE, FALSE, 0);
        return 0;
    }
    postProgress(DOWNLOAD_SHARE);
    postLine(format(newVersion ? L"updated -- got %.1f MB" : L"got %.1f MB",
                    library.size() / 1048576.0));
    postDebug(format(L"using %zu bytes, MZ header ok", library.size()));

    bool injected = runInjection(library.data(), library.size(), nullptr, 0, g_targetPid,
                                 postLine, postDebug);
    postProgress(injected ? 1.0 : DOWNLOAD_SHARE);
    PostMessageW(g_window, WM_INJECT_DONE, injected ? TRUE : FALSE, 0);
    return 0;
}
std::vector<std::wstring> parseChangelog(const std::vector<BYTE> &bytes) {
    std::vector<std::wstring> lines;
    if (bytes.empty()) {
        return lines;
    }
    const char *raw = (const char *)bytes.data();
    int size = (int)bytes.size();
    if (size >= 3 && (BYTE)raw[0] == 0xEF && (BYTE)raw[1] == 0xBB && (BYTE)raw[2] == 0xBF) {
        raw += 3;
        size -= 3;
    }
    int wide = MultiByteToWideChar(CP_UTF8, 0, raw, size, nullptr, 0);
    if (wide <= 0) {
        return lines;
    }
    std::wstring text(wide, 0);
    MultiByteToWideChar(CP_UTF8, 0, raw, size, &text[0], wide);

    size_t start = 0;
    for (;;) {
        size_t end = text.find(L'\n', start);
        std::wstring line = text.substr(start, end == std::wstring::npos ? std::wstring::npos
                                                                         : end - start);
        size_t first = line.find_first_not_of(L" \t\r");
        size_t last = line.find_last_not_of(L" \t\r");
        if (first != std::wstring::npos) {
            line = line.substr(first, last - first + 1);
            if (line[0] == L'+' || line[0] == L'-' || line[0] == L'*') {
                line.erase(0, 1);
                size_t after = line.find_first_not_of(L" \t");
                line.erase(0, after == std::wstring::npos ? line.size() : after);
            }
            if (!line.empty()) {
                lines.push_back(line);
            }
        }
        if (end == std::wstring::npos) {
            break;
        }
        start = end + 1;
    }
    return lines;
}
struct ChangelogRequest {
    unsigned generation;
    int channel;
};
DWORD WINAPI changelogThread(LPVOID parameter) {
    std::unique_ptr<ChangelogRequest> request((ChangelogRequest *)parameter);
    std::vector<BYTE> bytes;
    std::wstring error;
    std::vector<std::wstring> lines;
    if (downloadToMemory(changelogUrl(request->channel), bytes, nullptr, error)) {
        lines = parseChangelog(bytes);
    }
    PostMessageW(g_window, WM_CHANGELOG, (WPARAM)request->generation,
                 lines.empty() ? 0
                               : (LPARAM) new std::vector<std::wstring>(std::move(lines)));
    return 0;
}
void requestChangelog(int channel) {
    ChangelogRequest *request = new ChangelogRequest{++g_changelogGeneration, channel};
    HANDLE thread = CreateThread(nullptr, 0, changelogThread, request, 0, nullptr);
    if (thread) {
        CloseHandle(thread);
    } else {
        delete request;
    }
}
void enterStage(Stage stage) {
    g_stage = stage;
    switch (stage) {
        case Stage::WORKING:
            g_buttonEnabled = false;
            g_progress = 0.0;
            break;
        case Stage::DONE_OK:
            g_buttonEnabled = false;
            g_progress = 1.0;
            SetTimer(g_window, TIMER_RESET, RESET_DELAY_MS, nullptr);
            break;
        case Stage::DONE_FAILED:
            g_armed = false;
            g_buttonEnabled = true;
            g_targetPid = 0;
            refreshTarget();
            break;
        default:
            g_progress = -1.0;
            g_armed = false;
            g_buttonEnabled = true;
            g_targetPid = 0;
            refreshTarget();
            break;
    }
    InvalidateRect(g_window, nullptr, FALSE);
}
void ensureBuffer(HDC reference) {
    if (g_memory) {
        return;
    }
    g_memory = CreateCompatibleDC(reference);
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(info.bmiHeader);
    info.bmiHeader.biWidth = WINDOW_WIDTH;
    info.bmiHeader.biHeight = -WINDOW_HEIGHT;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    g_memoryBitmap = CreateDIBSection(reference, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    g_memoryOld = SelectObject(g_memory, g_memoryBitmap);
}
LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            g_console.push_back(Line{L"myau injector ready", Ink::NORMAL});
            g_console.push_back(Line{L"press Inject -- the game can start after", Ink::NORMAL});
            g_buttonEnabled = true;
            SetTimer(window, TIMER_TARGET, TARGET_POLL_MS, nullptr);
            return 0;
        }
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT: {
            PAINTSTRUCT paint;
            HDC dc = BeginPaint(window, &paint);
            ensureBuffer(dc);
            RECT all = {0, 0, WINDOW_WIDTH, WINDOW_HEIGHT};
            FillRect(g_memory, &all, g_backgroundBrush);
            paintShapes(g_memory);
            paintText(g_memory);
            paintDropdown(g_memory);
            BitBlt(dc, paint.rcPaint.left, paint.rcPaint.top,
                   paint.rcPaint.right - paint.rcPaint.left,
                   paint.rcPaint.bottom - paint.rcPaint.top,
                   g_memory, paint.rcPaint.left, paint.rcPaint.top, SRCCOPY);
            EndPaint(window, &paint);
            return 0;
        }
        case WM_NCHITTEST: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            ScreenToClient(window, &cursor);
            RECT close = closeRect();
            RECT adv = advancedRect();
            if (PtInRect(&close, cursor) || PtInRect(&adv, cursor)) {
                return HTCLIENT;
            }
            return cursor.y < DRAG_HEIGHT ? HTCAPTION : HTCLIENT;
        }
        case WM_TIMER: {
            if (wParam == TIMER_RESET) {
                KillTimer(window, TIMER_RESET);
                enterStage(Stage::IDLE);
            } else if (wParam == TIMER_TARGET) {
                refreshTarget();
            }
            return 0;
        }
        case WM_MOUSEWHEEL: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            ScreenToClient(window, &cursor);
            RECT box = {LEFT_X, CHANGELOG_TOP, LEFT_X + LEFT_WIDTH, BUTTON_TOP - 12};
            if (PtInRect(&box, cursor)) {
                int notches = GET_WHEEL_DELTA_WPARAM(wParam) / WHEEL_DELTA;
                if (scrollChangelog(-notches * 3)) {
                    RECT area = {LEFT_X, HEADING_TOP, LEFT_X + LEFT_WIDTH, BUTTON_TOP};
                    InvalidateRect(window, &area, FALSE);
                }
            }
            return 0;
        }
        case WM_MOUSEMOVE: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            RECT button = buttonRect();
            RECT close = closeRect();
            RECT link = linkRect();
            RECT adv = advancedRect();
            RECT channelBox = channelBoxRect();
            RECT channelRegion = channelRegionRect();
            bool buttonHot = g_buttonEnabled && PtInRect(&button, cursor);
            bool closeHot = PtInRect(&close, cursor) != FALSE;
            bool linkHot = !g_channelOpen && PtInRect(&link, cursor) != FALSE;
            bool advHot = PtInRect(&adv, cursor) != FALSE;
            bool boxHot = PtInRect(&channelBox, cursor) != FALSE;
            int channelHot = -1;
            if (g_channelOpen) {
                for (int i = 0; i < 2; i++) {
                    RECT opt = channelOptionRect(i);
                    if (PtInRect(&opt, cursor)) {
                        channelHot = i;
                        break;
                    }
                }
            }
            if (advHot != g_advancedHot) {
                g_advancedHot = advHot;
                InvalidateRect(window, &adv, FALSE);
            }
            if (boxHot != g_channelBoxHot || channelHot != g_channelHot) {
                g_channelBoxHot = boxHot;
                g_channelHot = channelHot;
                InvalidateRect(window, &channelRegion, FALSE);
            }
            if (linkHot != g_linkHot) {
                g_linkHot = linkHot;
                InvalidateRect(window, &link, FALSE);
            }
            if (buttonHot != g_buttonHot) {
                g_buttonHot = buttonHot;
                InvalidateRect(window, &button, FALSE);
            }
            if (closeHot != g_closeHot) {
                g_closeHot = closeHot;
                InvalidateRect(window, &close, FALSE);
            }
            TRACKMOUSEEVENT track = {sizeof(track), TME_LEAVE, window, 0};
            TrackMouseEvent(&track);
            return 0;
        }
        case WM_MOUSELEAVE: {
            if (g_buttonHot || g_buttonDown) {
                g_buttonHot = false;
                g_buttonDown = false;
                RECT button = buttonRect();
                InvalidateRect(window, &button, FALSE);
            }
            if (g_closeHot) {
                g_closeHot = false;
                RECT close = closeRect();
                InvalidateRect(window, &close, FALSE);
            }
            if (g_linkHot) {
                g_linkHot = false;
                RECT link = linkRect();
                InvalidateRect(window, &link, FALSE);
            }
            if (g_advancedHot) {
                g_advancedHot = false;
                RECT adv = advancedRect();
                InvalidateRect(window, &adv, FALSE);
            }
            if (g_channelBoxHot || g_channelHot >= 0) {
                g_channelBoxHot = false;
                g_channelHot = -1;
                RECT channelRegion = channelRegionRect();
                InvalidateRect(window, &channelRegion, FALSE);
            }
            return 0;
        }
        case WM_SETCURSOR: {
            if (LOWORD(lParam) == HTCLIENT
                    && (g_linkHot || g_advancedHot || g_channelBoxHot || g_channelHot >= 0)) {
                SetCursor(LoadCursor(nullptr, IDC_HAND));
                return TRUE;
            }
            break;
        }
        case WM_LBUTTONDOWN: {
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            RECT close = closeRect();
            if (PtInRect(&close, cursor)) {
                PostMessageW(window, WM_CLOSE, 0, 0);
                return 0;
            }
            RECT channelRegion = channelRegionRect();
            if (g_channelOpen) {
                int picked = -1;
                for (int i = 0; i < 2; i++) {
                    RECT opt = channelOptionRect(i);
                    if (PtInRect(&opt, cursor)) {
                        picked = i;
                        break;
                    }
                }
                if (picked >= 0 && picked != g_channel) {
                    g_channel = picked;
                    g_changelog.assign(1, L"Loading changelog...");
                    g_changelogScroll = 0;
                    RECT area = {LEFT_X, HEADING_TOP, LEFT_X + LEFT_WIDTH, BUTTON_TOP};
                    InvalidateRect(window, &area, FALSE);
                    requestChangelog(g_channel);
                }
                g_channelOpen = false;
                InvalidateRect(window, &channelRegion, FALSE);
                return 0;
            }
            RECT link = linkRect();
            if (PtInRect(&link, cursor)) {
                ShellExecuteW(window, L"open", LINK, nullptr, nullptr, SW_SHOWNORMAL);
                return 0;
            }
            RECT adv = advancedRect();
            if (PtInRect(&adv, cursor)) {
                g_advanced = !g_advanced;
                addLine(g_advanced ? L"advanced logging enabled"
                                   : L"advanced logging disabled", false, false);
                InvalidateRect(window, &adv, FALSE);
                return 0;
            }
            RECT channelBox = channelBoxRect();
            if (PtInRect(&channelBox, cursor)) {
                g_channelOpen = true;
                InvalidateRect(window, &channelRegion, FALSE);
                return 0;
            }
            RECT button = buttonRect();
            if (g_buttonEnabled && PtInRect(&button, cursor)) {
                g_buttonDown = true;
                SetCapture(window);
                InvalidateRect(window, &button, FALSE);
            }
            return 0;
        }
        case WM_LBUTTONUP: {
            if (!g_buttonDown) {
                return 0;
            }
            g_buttonDown = false;
            ReleaseCapture();
            POINT cursor = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            RECT button = buttonRect();
            InvalidateRect(window, &button, FALSE);
            if (g_buttonEnabled && PtInRect(&button, cursor) && g_stage != Stage::WORKING) {
                if (g_armed) {
                    g_armed = false;
                    addLine(L"stopped waiting", false, false);
                    InvalidateRect(window, &button, FALSE);
                    return 0;
                }
                if (g_targetPid != 0) {
                    enterStage(Stage::WORKING);
                    CloseHandle(CreateThread(nullptr, 0, injectThread, nullptr, 0, nullptr));
                } else {
                    g_armed = true;
                    addLine(L"waiting for the game to start...", false, false);
                    InvalidateRect(window, &button, FALSE);
                }
            }
            return 0;
        }
        case WM_LOG_LINE: {
            std::wstring *line = (std::wstring *)lParam;
            addLine(*line, (wParam & 1) != 0, (wParam & 2) != 0);
            delete line;
            return 0;
        }
        case WM_PROGRESS: {
            g_progress = (double)wParam / 1000.0;
            RECT box = progressRect();
            InvalidateRect(window, &box, FALSE);
            return 0;
        }
        case WM_CHANGELOG: {
            std::vector<std::wstring> *lines = (std::vector<std::wstring> *)lParam;
            if ((unsigned)wParam != g_changelogGeneration.load()) {
                delete lines;
                return 0;
            }
            if (lines) {
                g_changelog = std::move(*lines);
                delete lines;
            } else {
                useFallbackChangelog();
            }
            g_changelogScroll = 0;
            RECT box = {LEFT_X, HEADING_TOP, LEFT_X + LEFT_WIDTH, BUTTON_TOP};
            InvalidateRect(window, &box, FALSE);
            return 0;
        }
        case WM_INJECT_DONE: {
            enterStage(wParam ? Stage::DONE_OK : Stage::DONE_FAILED);
            return 0;
        }
        case WM_DESTROY: {
            g_window = nullptr;
            PostQuitMessage(0);
            return 0;
        }
        default:
            break;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    Gdiplus::GdiplusStartupInput startup;
    Gdiplus::GdiplusStartup(&g_gdiplusToken, &startup, nullptr);
    loadFont();
    createFonts();
    HDC screen = GetDC(nullptr);
    if (screen) {
        g_linkSize = measureText(screen, g_fontSmall, LINK);
        g_szAdvanced = measureText(screen, g_fontSmall, L"Advanced");
        g_szLatest = measureText(screen, g_fontSmall, L"Latest");
        g_szBeta = measureText(screen, g_fontSmall, L"Beta");
        ReleaseDC(nullptr, screen);
    }
    g_logo = loadLogo();
    if (g_logo) {
        g_logoContent = trimToContent(g_logo);
    }
    useFallbackChangelog();
    g_backgroundBrush = CreateSolidBrush(COLOUR_BACKGROUND);
    HICON icon = LoadIconW(instance, MAKEINTRESOURCEW(IDI_APP));
    WNDCLASSEXW cls = {sizeof(cls)};
    cls.lpfnWndProc = windowProc;
    cls.hInstance = instance;
    cls.hCursor = LoadCursor(nullptr, IDC_ARROW);
    cls.hbrBackground = nullptr;
    cls.lpszClassName = L"MyauLoader";
    cls.hIcon = icon;
    cls.hIconSm = icon;
    RegisterClassExW(&cls);
    RECT wanted = {0, 0, WINDOW_WIDTH, WINDOW_HEIGHT};
    AdjustWindowRect(&wanted, WS_POPUP, FALSE);
    g_window = CreateWindowExW(WS_EX_APPWINDOW, cls.lpszClassName, L"Myau Injector", WS_POPUP,
                               CW_USEDEFAULT, CW_USEDEFAULT,
                               wanted.right - wanted.left, wanted.bottom - wanted.top,
                               nullptr, nullptr, instance, nullptr);
    if (!g_window) {
        return 1;
    }
    RECT work;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    SetWindowPos(g_window, nullptr,
                 work.left + (work.right - work.left - WINDOW_WIDTH) / 2,
                 work.top + (work.bottom - work.top - WINDOW_HEIGHT) / 2,
                 WINDOW_WIDTH, WINDOW_HEIGHT, SWP_NOZORDER);
    BOOL dark = TRUE;
    if (FAILED(DwmSetWindowAttribute(g_window, 20, &dark, sizeof(dark)))) {
        DwmSetWindowAttribute(g_window, 19, &dark, sizeof(dark));
    }
    DWORD corner = 2;
    DwmSetWindowAttribute(g_window, 33, &corner, sizeof(corner));
    ShowWindow(g_window, show);
    UpdateWindow(g_window);
    requestChangelog(g_channel);
    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    if (g_memory) {
        SelectObject(g_memory, g_memoryOld);
        DeleteObject(g_memoryBitmap);
        DeleteDC(g_memory);
    }
    destroyFonts();
    if (g_fontResource) {
        RemoveFontMemResourceEx(g_fontResource);
    }
    DeleteObject(g_backgroundBrush);
    delete g_logo;
    Gdiplus::GdiplusShutdown(g_gdiplusToken);
    return 0;
}
