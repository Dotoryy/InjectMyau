package myau.util.font;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlyphFontRenderer implements RavenFontRenderer {
    private static final int FIRST_GLYPH = 0;
    private static final int LAST_GLYPH = 255;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int GLYPH_MARGIN = 4;
    private static final float RENDER_SCALE = 2.0F;
    private static final String ALPHABET = "ABCDEFGHOKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final Font renderFont;
    private final boolean antiAlias;
    private final FontRenderContext fontRenderContext;
    private final GlyphData[] defaultGlyphs = new GlyphData[LAST_GLYPH + 1];
    private final Map<Character, GlyphData> extendedGlyphs = new ConcurrentHashMap<Character, GlyphData>();
    private final float drawScale;
    private final float rawScale;
    private final float rawTextTop;
    private final float rawTextBottom;
    private final float fontHeight;
    private final float lineHeight;
    private boolean destroyed;
    public GlyphFontRenderer(Font sourceFont, boolean antiAlias) {
        float renderScale = RENDER_SCALE;
        this.drawScale = 1.0F / renderScale;
        this.rawScale = renderScale;
        this.renderFont = sourceFont.deriveFont(
                sourceFont.getStyle(), Math.max(1.0F, sourceFont.getSize2D() * renderScale));
        this.antiAlias = antiAlias;
        this.fontRenderContext = new FontRenderContext(new AffineTransform(), antiAlias, true);
        for (int codePoint = FIRST_GLYPH; codePoint <= LAST_GLYPH; codePoint++) {
            this.defaultGlyphs[codePoint] = this.createGlyph((char) codePoint);
        }
        this.rawTextTop = this.computeRawTextTop();
        this.rawTextBottom = this.computeRawTextBottom();
        this.fontHeight = Math.max(1.0F, (this.rawTextBottom - this.rawTextTop) * this.drawScale);
        this.lineHeight = this.computeLineHeight();
    }
    @Override
    public int drawString(String text, float x, float y, int color, boolean shadow) {
        if (this.destroyed || text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        if (shadow) {
            width = this.drawInternal(text, x + 1.0F, y + 1.0F, color, true);
        }
        return Math.max(width, this.drawInternal(text, x, y, color, false));
    }
    @Override
    public int getStringWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        float width = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (FontColors.isMalformedSectionPrefix(text, i)) {
                continue;
            }
            if (character == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (character == '\n') {
                continue;
            }
            width += this.getGlyph(character).advance;
        }
        return Math.round(width);
    }
    @Override
    public int getFontHeight() {
        return Math.round(this.fontHeight);
    }

    @Override
    public int getLineHeight() {
        return Math.round(this.lineHeight);
    }
    @Override
    public int getTextTopOffset() {
        return 0;
    }
    @Override
    public int getTextBottomOffset() {
        return Math.round(this.fontHeight);
    }
    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        deleteGlyphTextures(this.defaultGlyphs);
        for (GlyphData glyph : this.extendedGlyphs.values()) {
            deleteGlyphTexture(glyph);
        }
        this.extendedGlyphs.clear();
    }
    private int drawInternal(String text, float x, float y, int color, boolean shadowPass) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        int activeColor = shadowPass ? FontColors.shadow(color) : FontColors.withAlpha(color, alpha);
        float startX = x * this.rawScale;
        float drawX = startX;
        float drawY = (y * this.rawScale) - this.rawTextTop;
        GL11.glPushMatrix();
        try {
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableTexture2D();
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.scale(this.drawScale, this.drawScale, 1.0F);
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (FontColors.isMalformedSectionPrefix(text, i)) {
                    continue;
                }

                if (character == '§' && i + 1 < text.length()) {
                    char formatCode = Character.toLowerCase(text.charAt(++i));
                    int colorIndex = FontColors.CODES.indexOf(formatCode);
                    if (colorIndex >= 0) {
                        if (colorIndex < 16) {
                            activeColor = FontColors.minecraft(colorIndex, alpha, shadowPass);
                        } else if (formatCode == 'r') {
                            activeColor = shadowPass ? FontColors.shadow(color) : FontColors.withAlpha(color, alpha);
                        }
                    }
                    continue;
                }
                if (character == '\n') {
                    drawX = startX;
                    drawY += this.lineHeight * this.rawScale;
                    continue;
                }
                GlyphData glyph = this.getGlyph(character);
                if (glyph.textureId != 0 && glyph.textureWidth > 0.0F && glyph.textureHeight > 0.0F) {
                    renderGlyph(glyph, drawX - GLYPH_MARGIN, drawY, activeColor);
                }
                drawX += glyph.rawAdvance;
            }
        } finally {
            GlStateManager.bindTexture(0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        }
        return Math.round((drawX - startX) * this.drawScale);
    }
    private static void renderGlyph(GlyphData glyph, float x, float y, int color) {
        GlStateManager.bindTexture(glyph.textureId);
        float alpha = ((color >>> 24) & 0xFF) / 255.0F;
        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        GlStateManager.color(red, green, blue, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(x, y + glyph.textureHeight);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f(x + glyph.textureWidth, y + glyph.textureHeight);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f(x + glyph.textureWidth, y);
        GL11.glEnd();
    }
    private GlyphData getGlyph(char character) {
        if (character >= FIRST_GLYPH && character <= LAST_GLYPH) {
            GlyphData glyph = this.defaultGlyphs[character];
            if (glyph != null) {
                return glyph;
            }
        }
        return this.extendedGlyphs.computeIfAbsent(character, this::createGlyph);
    }
    private GlyphData createGlyph(char character) {
        if (this.destroyed) {
            return new GlyphData(0, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0);
        }
        if (Character.isISOControl(character) && character != '\n') {
            return new GlyphData(0, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0);
        }
        String glyphText = String.valueOf(character);
        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metricsGraphics = metricsImage.createGraphics();
        try {
            metricsGraphics.setFont(this.renderFont);
            this.applyRenderHints(metricsGraphics);
            FontMetrics metrics = metricsGraphics.getFontMetrics();
            Rectangle2D bounds = metrics.getStringBounds(glyphText, metricsGraphics);
            float rawAdvance = Math.max(1.0F, (float) bounds.getWidth());
            int textureWidth = Math.max(1, (int) Math.ceil(rawAdvance) + GLYPH_MARGIN * 2);
            int textureHeight = Math.max(1, metrics.getHeight());
            BufferedImage glyphImage = new BufferedImage(textureWidth, textureHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D glyphGraphics = glyphImage.createGraphics();
            try {
                glyphGraphics.setFont(this.renderFont);
                glyphGraphics.setBackground(new Color(255, 255, 255, 0));
                glyphGraphics.clearRect(0, 0, textureWidth, textureHeight);
                glyphGraphics.setColor(Color.WHITE);
                this.applyRenderHints(glyphGraphics);
                glyphGraphics.drawString(glyphText, GLYPH_MARGIN, metrics.getAscent());
            } finally {
                glyphGraphics.dispose();
            }
            int textureId = uploadTexture(glyphImage);
            int[] visibleBounds = findVisibleRowBounds(glyphImage);
            return new GlyphData(textureId, textureWidth, textureHeight,
                    rawAdvance, rawAdvance * this.drawScale, visibleBounds[0], visibleBounds[1]);
        } finally {
            metricsGraphics.dispose();
        }
    }
    private static int uploadTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, new int[width * height], 0, width);
        ByteBuffer byteBuffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[x + y * width];
                byteBuffer.put((byte) ((pixel >> 16) & CHANNEL_MASK));
                byteBuffer.put((byte) ((pixel >> 8) & CHANNEL_MASK));
                byteBuffer.put((byte) (pixel & CHANNEL_MASK));
                byteBuffer.put((byte) ((pixel >> 24) & CHANNEL_MASK));
            }
        }
        byteBuffer.flip();
        int textureId = GL11.glGenTextures();
        GlStateManager.bindTexture(textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, byteBuffer);
        return textureId;
    }
    private float computeRawTextTop() {
        float minTop = Float.MAX_VALUE;
        for (int i = 0; i < ALPHABET.length(); i++) {
            GlyphData glyph = this.getGlyph(ALPHABET.charAt(i));
            if (!glyph.hasVisiblePixels()) {
                continue;
            }
            minTop = Math.min(minTop, glyph.visibleTop);
        }
        return minTop == Float.MAX_VALUE ? 0.0F : minTop;
    }

    private float computeRawTextBottom() {
        float maxBottom = 0.0F;
        for (int i = 0; i < ALPHABET.length(); i++) {
            GlyphData glyph = this.getGlyph(ALPHABET.charAt(i));
            if (!glyph.hasVisiblePixels()) {
                continue;
            }
            maxBottom = Math.max(maxBottom, glyph.visibleBottom);
        }
        if (maxBottom <= 0.0F) {
            return Math.max(1.0F, this.renderFont.getSize2D());
        }
        return maxBottom;
    }

    private float computeLineHeight() {
        return Math.max(this.fontHeight,
                (float) this.renderFont.getStringBounds(ALPHABET, this.fontRenderContext).getHeight() * this.drawScale);
    }
    private static int[] findVisibleRowBounds(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((image.getRGB(x, y) >>> 24) & CHANNEL_MASK) != 0) {
                    if (top == -1) {
                        top = y;
                    }
                    bottom = y + 1;
                    break;
                }
            }
        }
        if (top == -1) {
            return new int[]{0, 0};
        }
        return new int[]{top, bottom};
    }
    private void applyRenderHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, this.antiAlias
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, this.antiAlias
                ? RenderingHints.VALUE_ANTIALIAS_ON
                : RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
    private static void deleteGlyphTextures(GlyphData[] glyphs) {
        for (GlyphData glyph : glyphs) {
            deleteGlyphTexture(glyph);
        }
    }

    private static void deleteGlyphTexture(GlyphData glyph) {
        if (glyph != null && glyph.textureId != 0) {
            GL11.glDeleteTextures(glyph.textureId);
        }
    }

    private static final class GlyphData {
        private final int textureId;
        private final float textureWidth;
        private final float textureHeight;
        private final float rawAdvance;
        private final float advance;
        private final int visibleTop;
        private final int visibleBottom;
        private GlyphData(int textureId, float textureWidth, float textureHeight,
                          float rawAdvance, float advance, int visibleTop, int visibleBottom) {
            this.textureId = textureId;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.rawAdvance = rawAdvance;
            this.advance = advance;
            this.visibleTop = visibleTop;
            this.visibleBottom = visibleBottom;
        }
        private boolean hasVisiblePixels() {
            return this.visibleBottom > this.visibleTop;
        }
    }
}
