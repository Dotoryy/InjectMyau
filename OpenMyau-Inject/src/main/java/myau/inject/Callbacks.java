package myau.inject;

import myau.Myau;
import myau.access.AccessorMinecraft;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import org.lwjgl.input.Keyboard;
import myau.events.HitBlockEvent;
import myau.events.LeftClickMouseEvent;
import myau.events.MouseButtonEvent;
import myau.events.PreAttackEvent;
import myau.events.RightClickMouseEvent;
import myau.events.PrePlayerInteractEvent;
import myau.events.UseItemEvent;
import myau.module.modules.InventoryMove;
import myau.events.Render2DEvent;
import myau.events.Render2DPostEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.management.blockage.InboundNetworkBlockage;
import myau.management.blockage.OutboundNetworkBlockage;
import myau.module.modules.NoHitDelay;
import myau.module.modules.NoRotate;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.client.Minecraft;
import myau.util.ConnectionUtil;
import myau.util.KeyBindUtil;

public final class Callbacks {
    public static final String OWNER = "myau/inject/Callbacks";
    private static float overlayPartialTicks;
    private static float worldPartialTicks;
    private static int lastKey;
    private static boolean lastPressed;
    private static boolean lastSynthetic;
    private Callbacks() {
    }
    public static void teleportRotation(net.minecraft.entity.player.EntityPlayer player,
            double x, double y, double z, float yaw, float pitch) {
        float useYaw = yaw;
        float usePitch = pitch;
        try {
            if (Bootstrap.isStarted()) {
                NoRotate noRotate = (NoRotate) Myau.moduleManager.modules.get(NoRotate.class);
                float[] override = noRotate == null ? null
                        : noRotate.onServerTeleport(x, y, z, yaw, pitch);
                if (override != null) {
                    useYaw = override[0];
                    usePitch = override[1];
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        player.setPositionAndRotation(x, y, z, useYaw, usePitch);
    }

    public static void tickPre() {
        try {

            Bootstrap.tick();
            NativeBridge.flushTransformLog();
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            tickSequence++;
            EventManager.call(new TickEvent(EventType.PRE));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    private static long tickSequence;
    private static long lastInteractTick = -1L;

    public static void render2DPre(float partialTicks) {
        overlayPartialTicks = partialTicks;
    }
    public static void render2DPost() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            EventManager.call(new Render2DEvent(overlayPartialTicks));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void render2DFrameEnd() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            EventManager.call(new Render2DPostEvent(overlayPartialTicks));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void prePlayerInteract() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            if (lastInteractTick == tickSequence) {
                return;
            }
            lastInteractTick = tickSequence;
            EventManager.call(new PrePlayerInteractEvent());
        } catch (Throwable ignored) {
        }
    }

    public static Boolean sendUseItem(Object stack) {
        try {
            if (!Bootstrap.isStarted()) {
                return null;
            }
            UseItemEvent event = new UseItemEvent((net.minecraft.item.ItemStack) stack);
            EventManager.call(event);
            return event.isCancelled() ? Boolean.FALSE : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean rightClickMouse() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return false;
            }
            RightClickMouseEvent event = new RightClickMouseEvent();
            EventManager.call(event);
            return event.isCancelled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean sendClickBlockToController() {
        try {
            if (!Bootstrap.isStarted()) {
                return false;
            }
            HitBlockEvent event = new HitBlockEvent();
            EventManager.call(event);
            if (!event.isCancelled()) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.playerController != null) {
                mc.playerController.resetBlockRemoving();
            }
            return true;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static boolean clickMouse() {
        try {
            if (!Bootstrap.isStarted()) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (Myau.moduleManager != null
                    && Myau.moduleManager.modules.get(NoHitDelay.class).isEnabled()) {
                AccessorMinecraft.setLeftClickCounter(mc, 0);
            }
            PreAttackEvent preAttack =
                    new PreAttackEvent(Minecraft.getMinecraft().objectMouseOver);
            EventManager.call(preAttack);
            if (preAttack.isCancelled()) {
                return true;
            }
            LeftClickMouseEvent event = new LeftClickMouseEvent();
            EventManager.call(event);
            return event.isCancelled();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }

    public static boolean keyBindStatePre(int key, boolean pressed) {
        lastKey = key;
        lastPressed = pressed;
        lastSynthetic = KeyBindUtil.isSynthetic();
        try {
            if (!Bootstrap.isStarted() || lastSynthetic || key >= 0) {
                return false;
            }
            MouseButtonEvent event = new MouseButtonEvent(key + 100, pressed);
            EventManager.call(event);
            return event.isCancelled();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static void guiKeyboardInput() {
        try {
            if (!Bootstrap.isStarted() || !Keyboard.getEventKeyState()) {
                return;
            }
            int key = Keyboard.getEventKey();
            if (key == 0) {
                return;
            }
            EventManager.call(new KeyEvent(key, true));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    public static void keyBindStatePost() {
        int key = lastKey;
        boolean pressed = lastPressed;
        boolean synthetic = lastSynthetic;
        try {
            if (!Bootstrap.isStarted() || !pressed || synthetic) {
                return;
            }
            EventManager.call(new KeyEvent(key,
                    Minecraft.getMinecraft().currentScreen != null));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void render3DPre(float partialTicks) {
        worldPartialTicks = partialTicks;
    }
    public static void render3DPost() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            EventManager.call(new Render3DEvent(worldPartialTicks));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void loadWorld() {
        try {
            if (!Bootstrap.isStarted()) {
                return;
            }
            OutboundNetworkBlockage.get().reset();
            InboundNetworkBlockage.get().reset();
            EventManager.call(new LoadWorldEvent());
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void ingameNotInFocus() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            InventoryMove module =
                    (InventoryMove) Myau.moduleManager.modules.get(InventoryMove.class);
            if (module != null && module.isEnabled()) {
                module.updateMovementKeyStates();
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static boolean packetReceive(Object manager, Object raw) {
        try {
            if (!Bootstrap.isStarted()) {
                return false;
            }
            if (ConnectionUtil.isForeign(manager)) {
                return false;
            }
            Packet<?> packet = (Packet<?>) raw;
            if (Myau.delayManager != null
                    && Myau.delayManager.shouldDelay((Packet<INetHandlerPlayClient>) packet)) {
                return true;
            }
            PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                return true;
            }
            return ConnectionUtil.isPlayPacket(packet)
                    && InboundNetworkBlockage.get().isBlocked(packet);
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static boolean packetSend(Object manager, Object raw) {
        try {
            if (!Bootstrap.isStarted()) {
                return false;
            }
            if (ConnectionUtil.isForeign(manager)) {
                return false;
            }
            Packet<?> packet = (Packet<?>) raw;
            PacketEvent event = new PacketEvent(EventType.SEND, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                return true;
            }
            if (ConnectionUtil.isPlayPacket(packet)
                    && OutboundNetworkBlockage.get().isBlocked(packet)) {
                return true;
            }
            return handOffToManagers(packet);
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static boolean packetSendWithListeners(Object manager, Object raw) {
        try {
            if (!Bootstrap.isStarted()) {
                return false;
            }
            if (ConnectionUtil.isForeign(manager)) {
                return false;
            }
            Packet<?> packet = (Packet<?>) raw;
            return handOffToManagers(packet);
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }

    private static boolean handOffToManagers(Packet<?> packet) {
        if (Myau.playerStateManager == null || Myau.blinkManager == null
                || Myau.lagManager == null || Myau.lagManager.isFlushing()) {
            return false;
        }
        Myau.playerStateManager.handlePacket(packet);
        if (Myau.blinkManager.isBlinking() && Myau.blinkManager.offerPacket(packet)) {
            return true;
        }
        return Myau.lagManager.handlePacket(packet);
    }
    public static void tickPost() {
        try {
            if (!Bootstrap.isStarted() || !GameState.inGame()) {
                return;
            }
            EventManager.call(new TickEvent(EventType.POST));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
}
