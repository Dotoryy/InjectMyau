package myau.util.font;

final class FontColors {
    static final String CODES = "0123456789abcdefklmnor";
    private FontColors() {
    }

    static int minecraft(int colorIndex, int alpha, boolean shadow) {
        int offset = (colorIndex >> 3 & 1) * 85;
        int red = (colorIndex >> 2 & 1) * 170 + offset;
        int green = (colorIndex >> 1 & 1) * 170 + offset;
        int blue = (colorIndex & 1) * 170 + offset;
        if (colorIndex == 6) {
            red += 85;
        }
        if (shadow) {
            red /= 4;
            green /= 4;
            blue /= 4;
        }
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    static int shadow(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        return (alpha << 24) | 0x3F3F3F;
    }
    static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }
    static boolean isMalformedSectionPrefix(String text, int index) {
        if (!isFormattingArtifact(text.charAt(index))) {
            return false;
        }
        for (int i = index + 1; i < text.length(); i++) {
            char next = text.charAt(i);
            if (next == '§') {
                return true;
            }
            if (!isFormattingArtifact(next)) {
                return false;
            }
        }
        return false;
    }
    static boolean isFormattingArtifact(char character) {
        return character == 'Â' || character == 'Ã'
                || character == '' || character == '‚';
    }
}
