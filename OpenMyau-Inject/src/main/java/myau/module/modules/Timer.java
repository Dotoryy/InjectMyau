package myau.module.modules;

import myau.access.AccessorMinecraft;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.util.KeyBindUtil;
import net.minecraft.client.Minecraft;

public class Timer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float NORMAL = 1.0F;
    private static final long STALL_MS = 250L;
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.0F, 2.0F, 0.01F);
    private volatile long lastTickMs = System.currentTimeMillis();
    private boolean keyWasDown = false;
    private boolean skipQueuedToggle = false;
    public Timer() {
        super("Timer", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        AccessorMinecraft.getTimer(mc).timerSpeed = this.speed.getValue();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            this.lastTickMs = System.currentTimeMillis();
        } else {
            this.skipQueuedToggle = false;
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        int key = this.getKey();
        boolean down = key != 0 && KeyBindUtil.isKeyDown(key);
        boolean pressed = down && !this.keyWasDown;
        this.keyWasDown = down;
        if (!pressed || !this.isEnabled()) {
            return;
        }
        if (System.currentTimeMillis() - this.lastTickMs < STALL_MS) {
            return;
        }
        this.skipQueuedToggle = true;
        this.setEnabled(false);
    }

    public boolean consumeQueuedToggle() {
        if (!this.skipQueuedToggle) {
            return false;
        }
        this.skipQueuedToggle = false;
        return true;
    }

    @Override
    public void onDisabled() {
        AccessorMinecraft.getTimer(mc).timerSpeed = NORMAL;
    }
    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%.2f", this.speed.getValue())};
    }
}
