package myau.util.font;

import myau.util.shader.ShaderProgram;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;

public final class MsdfFontRenderer implements RavenFontRenderer {
    private static final float SHADOW_SCALE = 0.125F;
    private final MsdfAtlas atlas;
    private final float size;
    private final float inkHeight;
    public MsdfFontRenderer(MsdfAtlas atlas, float size) {
        this.atlas = atlas;
        this.size = size;
        this.inkHeight = Math.max(1.0F, (atlas.inkTop - atlas.inkBottom) * size);
    }
    private float shadowOffset() {
        return Math.max(0.5F, this.size * SHADOW_SCALE);
    }
    @Override
    public int drawString(String text, float x, float y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        if (shadow) {
            float offset = this.shadowOffset();
            width = this.draw(text, x + offset, y + offset, color, true);
        }
        return Math.max(width, this.draw(text, x, y, color, false));
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
            width += this.atlas.lookup(character).advance * this.size;
        }
        return Math.round(width);
    }

    @Override
    public int getFontHeight() {
        return Math.round(this.inkHeight);
    }
    @Override
    public int getLineHeight() {
        return Math.round(this.atlas.lineHeight * this.size);
    }
    @Override
    public int getTextTopOffset() {
        return 0;
    }
    @Override
    public int getTextBottomOffset() {
        return Math.round(this.inkHeight);
    }

    private int draw(String text, float x, float y, int color, boolean shadowPass) {
        ShaderProgram shader = MsdfShader.get();
        int texture = this.atlas.texture();
        if (shader == null || texture == 0) {
            return 0;
        }
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }
        int activeColor = shadowPass ? FontColors.shadow(color) : FontColors.withAlpha(color, alpha);
        float startX = x;
        float penX = x;
        float baseline = y + this.atlas.inkTop * this.size;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableTexture2D();

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(texture);
        shader.init();
        shader.setUniformi("atlas", 0);
        shader.setUniformf("atlasSize", this.atlas.width, this.atlas.height);
        setColor(shader, activeColor);
        GL11.glBegin(GL11.GL_QUADS);
        try {
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (FontColors.isMalformedSectionPrefix(text, i)) {
                    continue;
                }

                if (character == '§' && i + 1 < text.length()) {
                    char formatCode = Character.toLowerCase(text.charAt(++i));
                    int colorIndex = FontColors.CODES.indexOf(formatCode);
                    if (colorIndex >= 0) {
                        int changed = activeColor;
                        if (colorIndex < 16) {
                            changed = FontColors.minecraft(colorIndex, alpha, shadowPass);
                        } else if (formatCode == 'r') {
                            changed = shadowPass
                                    ? FontColors.shadow(color)
                                    : FontColors.withAlpha(color, alpha);
                        }
                        if (changed != activeColor) {
                            activeColor = changed;

                            GL11.glEnd();
                            setColor(shader, activeColor);
                            GL11.glBegin(GL11.GL_QUADS);
                        }
                    }
                    continue;
                }
                if (character == '\n') {
                    penX = startX;
                    baseline += this.atlas.lineHeight * this.size;
                    continue;
                }
                MsdfAtlas.Glyph glyph = this.atlas.lookup(character);
                if (glyph.hasInk) {
                    float left = penX + glyph.planeLeft * this.size;
                    float right = penX + glyph.planeRight * this.size;
                    float top = baseline - glyph.planeTop * this.size;
                    float bottom = baseline - glyph.planeBottom * this.size;
                    GL11.glTexCoord2f(glyph.u0, glyph.v0);
                    GL11.glVertex2f(left, top);
                    GL11.glTexCoord2f(glyph.u0, glyph.v1);
                    GL11.glVertex2f(left, bottom);
                    GL11.glTexCoord2f(glyph.u1, glyph.v1);
                    GL11.glVertex2f(right, bottom);
                    GL11.glTexCoord2f(glyph.u1, glyph.v0);
                    GL11.glVertex2f(right, top);
                }
                penX += glyph.advance * this.size;
            }
        } finally {
            GL11.glEnd();
            shader.unload();
            GlStateManager.bindTexture(0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return Math.round(penX - startX);
    }
    private static void setColor(ShaderProgram shader, int color) {
        shader.setUniformf("textColor",
                ((color >>> 16) & 0xFF) / 255.0F,
                ((color >>> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F,
                ((color >>> 24) & 0xFF) / 255.0F);
    }
}
