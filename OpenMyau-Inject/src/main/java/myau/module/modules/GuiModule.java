package myau.module.modules;

import myau.module.Module;
import myau.ui.clickgui.ClickGui;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public class GuiModule extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private ClickGui clickGui;

    public GuiModule() {
        super("Click Gui", false);
        setKey(Keyboard.KEY_RSHIFT);
    }

    @Override
    public void onEnabled() {
        setEnabled(false);
        try {
            if (clickGui == null) {
                clickGui = new ClickGui();
            }
        } catch (Throwable failed) {
            ChatUtil.sendFormatted("&cclick gui failed to open: " + failed.getMessage());
            return;
        }
        mc.displayGuiScreen(clickGui);
    }
}
