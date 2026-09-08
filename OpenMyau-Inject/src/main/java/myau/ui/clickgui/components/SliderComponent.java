package myau.ui.clickgui.components;

import myau.property.Property;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.ui.clickgui.GuiRender;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class SliderComponent extends Component {
    public static final float HEIGHT = 16.0F;
    private static final double CATCH_UP = 0.6;
    private static final int TRACK_COLOR = -12302777;
    private final Property<?> property;
    private final ModuleComponent module;
    private final double minimum;
    private final double maximum;
    private final boolean choice;
    private float offset;
    private float x;
    private float y;
    private boolean heldDown;
    private double shownValue;
    private double filledWidth;
    public SliderComponent(Property<?> property, ModuleComponent module, float offset) {
        this.property = property;
        this.module = module;
        this.offset = offset;
        this.x = module.category.getX();
        this.y = module.category.getY() + offset;
        if (property instanceof ModeProperty) {
            this.choice = true;
            this.minimum = 0.0;
            this.maximum = ((ModeProperty) property).getModeCount() - 1;
        } else if (property instanceof IntProperty) {
            this.choice = false;
            this.minimum = ((IntProperty) property).getMinimum();
            this.maximum = ((IntProperty) property).getMaximum();
        } else if (property instanceof PercentProperty) {
            this.choice = false;
            this.minimum = ((PercentProperty) property).getMinimum();
            this.maximum = ((PercentProperty) property).getMaximum();
        } else {
            this.choice = false;
            this.minimum = ((FloatProperty) property).getMinimum();
            this.maximum = ((FloatProperty) property).getMaximum();
        }
        this.shownValue = this.current();
        this.filledWidth = this.widthFor(this.shownValue);
    }
    public static boolean handles(Property<?> property) {
        return property instanceof IntProperty
                || property instanceof FloatProperty
                || property instanceof PercentProperty
                || property instanceof ModeProperty;
    }
    @Override
    public void render() {
        float left = this.module.category.getX() + 4;
        float right = left + this.module.category.getWidth() - 8;
        float top = this.module.category.getY() + this.offset + 11;
        float bottom = top + 4;
        GuiRender.drawRoundedRect(left, top, right, bottom, 4, TRACK_COLOR);
        GuiRender.drawRoundedRect(left, top, (float) Math.min(right, left + this.filledWidth), bottom, 4,
                Color.getHSBColor((System.currentTimeMillis() % 11000L) / 11000.0F, 0.75F, 0.9F).getRGB());
        GL11.glPushMatrix();
        GL11.glScaled(0.5, 0.5, 0.5);
        Minecraft.getMinecraft().fontRendererObj.drawStringWithShadow(
                this.property.getName() + ": " + (this.choice ? "§e" : "§b") + this.label(),
                (this.module.category.getX() + 4) * 2,
                (this.module.category.getY() + this.offset + 3) * 2,
                -1);
        GL11.glPopMatrix();
    }
    @Override
    public void drawScreen(int mouseX, int mouseY) {
        this.y = this.module.category.getModuleY() + this.offset;
        this.x = this.module.category.getX();
        if (this.heldDown) {
            double span = this.module.category.getWidth() - 8;
            double along = Math.min(span, Math.max(0.0, mouseX - this.x - 4));
            double value = round(along / span * (this.maximum - this.minimum) + this.minimum);
            this.apply(value);
        }
        this.shownValue += (this.current() - this.shownValue) * CATCH_UP;
        this.filledWidth = this.widthFor(this.shownValue);
    }
    @Override
    public boolean onClick(int mouseX, int mouseY, int button) {
        if (button == 0 && this.contains(mouseX, mouseY)
                && this.module.isOpened() && this.module.isVisible(this)) {
            this.heldDown = true;
        }
        return false;
    }
    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        this.heldDown = false;
    }
    @Override
    public void onGuiClosed() {
        this.heldDown = false;
    }
    private boolean contains(int mouseX, int mouseY) {
        return mouseX > this.x && mouseX < this.x + this.module.category.getWidth()
                && mouseY > this.y && mouseY < this.y + HEIGHT;
    }
    private double current() {
        return ((Number) this.property.getValue()).doubleValue();
    }
    private void apply(double value) {
        if (this.property instanceof FloatProperty) {
            this.property.setValue(((FloatProperty) this.property).snap((float) value));
        } else {
            int rounded = (int) Math.round(value);
            this.property.setValue(this.property instanceof IntProperty
                    ? ((IntProperty) this.property).snap(rounded)
                    : rounded);
        }
    }

    private String label() {
        if (this.choice) {
            return ((ModeProperty) this.property).getModeString();
        }
        double value = this.current();
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", round(value));
    }
    private double widthFor(double value) {
        double span = this.maximum - this.minimum;
        if (span <= 0.0) {
            return 0.0;
        }
        return (this.module.category.getWidth() - 8) * ((value - this.minimum) / span);
    }
    private static final double STEP = 0.1;
    private static double round(double value) {
        return new BigDecimal(value / STEP).setScale(0, RoundingMode.HALF_UP).doubleValue() * STEP;
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
    @Override
    public boolean isBaseVisible() {
        return this.property.isVisible();
    }
}
