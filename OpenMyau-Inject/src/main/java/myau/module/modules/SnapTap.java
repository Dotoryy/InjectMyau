package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovementInput;

public class SnapTap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float SNEAK_MULTIPLIER = 0.3F;
    private boolean prevW;
    private boolean prevS;
    private boolean prevA;
    private boolean prevD;
    private int lastForwardSign;
    private int lastStrafeSign;

    public SnapTap() {
        super("Snap Tap", false);
    }

    @Override
    public void onDisabled() {
        this.prevW = false;
        this.prevS = false;
        this.prevA = false;
        this.prevD = false;
        this.lastForwardSign = 0;
        this.lastStrafeSign = 0;
    }

    @EventTarget(Priority.HIGH)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        boolean w = mc.gameSettings.keyBindForward.isKeyDown();
        boolean s = mc.gameSettings.keyBindBack.isKeyDown();
        boolean a = mc.gameSettings.keyBindLeft.isKeyDown();
        boolean d = mc.gameSettings.keyBindRight.isKeyDown();
        if (w && !this.prevW) {
            this.lastForwardSign = 1;
        }
        if (s && !this.prevS) {
            this.lastForwardSign = -1;
        }
        if (a && !this.prevA) {
            this.lastStrafeSign = 1;
        }
        if (d && !this.prevD) {
            this.lastStrafeSign = -1;
        }
        MovementInput input = mc.thePlayer.movementInput;
        float scale = input.sneak ? SNEAK_MULTIPLIER : 1.0F;
        if (w && s) {
            input.moveForward = (this.lastForwardSign >= 0 ? 1.0F : -1.0F) * scale;
        }
        if (a && d) {
            input.moveStrafe = (this.lastStrafeSign >= 0 ? 1.0F : -1.0F) * scale;
        }
        this.prevW = w;
        this.prevS = s;
        this.prevA = a;
        this.prevD = d;
    }
}
