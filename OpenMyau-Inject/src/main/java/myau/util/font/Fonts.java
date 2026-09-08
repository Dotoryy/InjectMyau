package myau.util.font;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Fonts {
    private static final String RESOURCE_ROOT = "/myau/font/";
    private static final float HUD_FONT_SIZE = 10.0F;
    public static final String[] MSDF_FONTS = {"SF", "SFBOLD", "ProductSansRegular",
            "GoogleSansRegular", "GoogleSansBold", "Tahoma", "TahomaBold", "Verdana"};
    public static final String[] ARRAYLIST_FONTS =
            {"Sf-Bold.ttf", "Sf-Regular.ttf", "Sf-Ui.ttf", "product_sans_regular.ttf"};
    private static final int MAX_CACHED_RENDERERS = 512;
    private static final Map<String, GlyphFontRenderer> RENDERERS =
            new LinkedHashMap<String, GlyphFontRenderer>(16, 0.75F, true);
    private static final Map<String, Font> BASE_FONTS = new ConcurrentHashMap<String, Font>();
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<String, Boolean>();
    private static final Map<String, RavenFontRenderer> ADAPTERS =
            new ConcurrentHashMap<String, RavenFontRenderer>();
    private Fonts() {
    }
    public static RavenFontRenderer arraylist(int selection, float scale) {
        float safeScale = Math.max(0.5F, Math.min(2.0F, scale));
        float size = HUD_FONT_SIZE * safeScale;
        if (selection <= 0 || selection > ARRAYLIST_FONTS.length + MSDF_FONTS.length) {
            return minecraft(size);
        }
        if (selection <= ARRAYLIST_FONTS.length) {
            RavenFontRenderer built = renderer(ARRAYLIST_FONTS[selection - 1], size);
            return built == null ? minecraft(size) : built;
        }
        RavenFontRenderer field = msdf(selection - 1 - ARRAYLIST_FONTS.length, size);
        return field == null ? minecraft(size) : field;
    }
    private static RavenFontRenderer msdf(int index, float size) {
        if (index < 0 || index >= MSDF_FONTS.length || !MsdfShader.isSupported()) {
            return null;
        }
        MsdfAtlas atlas = MsdfAtlas.get(MSDF_FONTS[index]);
        return atlas == null ? null : new MsdfFontRenderer(atlas, size);
    }

    private static synchronized RavenFontRenderer minecraft(float size) {
        FontRenderer vanilla = Minecraft.getMinecraft().fontRendererObj;
        float height = Math.max(1.0F, vanilla.FONT_HEIGHT);
        float scale = Math.max(0.5F, Math.min(2.0F, size / height));
        String key = "Minecraft#" + quantize(scale);
        RavenFontRenderer cached = ADAPTERS.get(key);
        if (cached != null) {
            return cached;
        }
        MinecraftFontAdapter adapter = new MinecraftFontAdapter(vanilla, scale);
        ADAPTERS.put(key, adapter);
        return adapter;
    }
    public static synchronized GlyphFontRenderer renderer(String fileName, float size) {
        float safeSize = Math.max(1.0F, size);
        String key = fileName + "#" + quantize(safeSize);
        GlyphFontRenderer cached = RENDERERS.get(key);
        if (cached != null) {
            return cached;
        }
        Font base = baseFont(fileName);
        if (base == null) {
            return null;
        }
        GlyphFontRenderer built = new GlyphFontRenderer(base.deriveFont(safeSize), true);
        RENDERERS.put(key, built);
        trim();
        return built;
    }
    private static Font baseFont(String fileName) {
        if (MISSING.containsKey(fileName)) {
            return null;
        }
        Font cached = BASE_FONTS.get(fileName);
        if (cached != null) {
            return cached;
        }
        byte[] data = read(fileName);
        if (data == null) {
            MISSING.put(fileName, Boolean.TRUE);
            return null;
        }
        Font loaded = create(data);
        if (loaded == null) {
            MISSING.put(fileName, Boolean.TRUE);
            return null;
        }
        BASE_FONTS.put(fileName, loaded);
        return loaded;
    }
    private static Font create(byte[] data) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data));
        } catch (Exception notTrueType) {
            try {
                return Font.createFont(Font.TYPE1_FONT, new ByteArrayInputStream(data));
            } catch (Exception notAFontAtAll) {
                return null;
            }
        }
    }
    private static byte[] read(String fileName) {
        try (InputStream in = Fonts.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (IOException unreadable) {
            return null;
        }
    }
    private static void trim() {
        while (RENDERERS.size() > MAX_CACHED_RENDERERS) {
            Iterator<Map.Entry<String, GlyphFontRenderer>> iterator = RENDERERS.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<String, GlyphFontRenderer> eldest = iterator.next();
            iterator.remove();
            if (eldest.getValue() != null) {
                eldest.getValue().destroy();
            }
        }
    }
    private static float quantize(float value) {
        return Math.round(value * 100.0F) / 100.0F;
    }
}
