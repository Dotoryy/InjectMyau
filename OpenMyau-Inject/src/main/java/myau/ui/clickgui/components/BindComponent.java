package myau.ui.clickgui.components;

import myau.module.Module;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.input.Keyboard;

import java.awt.Color;

public class BindComponent extends Component {
    public static final float HEIGHT = 11.0F;
    private static final int BIND_COLOR = new Color(150, 170, 220).getRGB();
    private final Module module;
    private final ModuleComponent parent;
    private float offset;
    private float x;
    private float y;
    private boolean binding;
    public BindComponent(Module module, ModuleComponent parent, float offset) {
        this.module = module;
        this.parent = parent;
        this.offset = offset;
        this.x = parent.category.getX();
        this.y = parent.category.getY() + offset;
    }
    @Override
    public void render() {
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        String text = this.binding
                ? "Press a key..."
                : "Bind: '§e" + this.keyName() + "§r'";
        Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(text,
                (this.parent.category.getX() + 4) * 2,
                (this.parent.category.getY() + this.offset + 3) * 2,
                BIND_COLOR);
        GL11.glPopMatrix();
    }
    private String keyName() {
        int key = this.module.getKey();
        return key == 0 ? "None" : KeyBindUtil.getKeyName(key);
    }
    @Override
    public void drawScreen(int mouseX, int mouseY) {
        this.y = this.parent.category.getModuleY() + this.offset;
        this.x = this.parent.category.getX();
    }
    @Override
    public boolean onClick(int mouseX, int mouseY, int button) {
        if (!this.parent.isOpened() || !this.contains(mouseX, mouseY)) {
            return false;
        }
        if (button == 0) {
            this.binding = !this.binding;
            return true;
        }
        if (this.binding && button > 1) {
            this.module.setKey(button - 100);
            this.binding = false;
            return true;
        }
        return false;
    }
    @Override
    public void keyTyped(char typed, int key) {
        if (!this.binding) {
            return;
        }
        this.module.setKey(key == Keyboard.KEY_ESCAPE ? 0 : key);
        this.binding = false;
    }

    public boolean isBinding() {
        return this.binding;
    }
    @Override
    public void onGuiClosed() {
        this.binding = false;
    }
    private boolean contains(int mouseX, int mouseY) {
        return mouseX > this.x && mouseX < this.x + this.parent.category.getWidth()
                && mouseY > this.y && mouseY < this.y + HEIGHT;
    }
    @Override
    public void updateHeight(float offset) {
        this.offset = offset;
    }
    @Override
    public float getOffset() {
        return this.offset;
    }
    @Override
    public float getHeightF() {
        return HEIGHT;
    }
}
