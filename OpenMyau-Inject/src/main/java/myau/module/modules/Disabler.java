package myau.module.modules;

import myau.access.AccessorC0DPacketCloseWindow;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.management.blockage.BlockHolder;
import myau.management.blockage.OutboundNetworkBlockage;
import myau.management.blockage.PacketTransformer;
import myau.management.blockage.PacketValidator;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import java.util.concurrent.CopyOnWriteArrayList;

public class Disabler extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_WATCHDOG = 0;

    private static final long TIMER_SPIKE_INTERVAL_MS = 700L;

    private static final long TIMER_HOLD_WINDOW_MS = 350L;

    private static final int CLICK_MODE_QUICK_MOVE = 1;

    private static final int CLICK_MODE_SWAP = 2;

    private static final int CLICK_MODE_THROW = 4;

    private static final PacketTransformer INV_TRANSFORMER = packet -> packet;

    private static final PacketValidator INV_VALIDATOR = packet -> !(packet instanceof C0EPacketClickWindow
            || packet instanceof C0DPacketCloseWindow
            || packet instanceof C0FPacketConfirmTransaction
            || packet instanceof C00PacketKeepAlive);

    private static final String BRAND_CHANNEL = "MC|Brand";

    private static final String MOD_CONTROL_CHANNEL = "badlion:mods";

    private static final String[] HYPIXEL_PREFIXES = {"hypixel:", "hyevent:"};

    public final ModeProperty mode = new ModeProperty("mode", MODE_WATCHDOG, new String[]{"Watchdog"});
    public final TextProperty brand = new TextProperty("brand", "vanilla",
            () -> this.mode.getValue() == MODE_WATCHDOG);

    public final BooleanProperty hypixelBrand = new BooleanProperty("Hypixel Brand", true,
            () -> this.mode.getValue() == MODE_WATCHDOG);

    public final BooleanProperty watchdogInv = new BooleanProperty("Watchdog Inv", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);

    private final CopyOnWriteArrayList<Packet<?>> timerHeldPackets = new CopyOnWriteArrayList<Packet<?>>();

    private volatile long timerHoldStartMs = System.currentTimeMillis();

    private volatile long timerSpikeStartMs = System.currentTimeMillis();

    private final BlockHolder invBlockHolder = new BlockHolder(OutboundNetworkBlockage.get());

    private volatile boolean invShouldBlink;

    public Disabler() {
        super("Disabler", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @Override
    public void onDisabled() {
        this.flushTimerPackets();
        this.invShouldBlink = false;
        this.invBlockHolder.release();
    }

    private boolean isWatchdogActive() {
        return this.isEnabled() && this.mode.getValue() == MODE_WATCHDOG;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isWatchdogActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.timerSpikeStartMs > TIMER_SPIKE_INTERVAL_MS) {
            this.timerSpikeStartMs = now;
            this.timerHoldStartMs = now;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (!this.isWatchdogActive() || !this.watchdogInv.getValue()) {
            this.invShouldBlink = false;
            this.invBlockHolder.release();
            return;
        }
        if (mc.currentScreen == null) {
            this.invShouldBlink = false;
        }
        if (this.invShouldBlink) {
            this.invBlockHolder.block(INV_TRANSFORMER, INV_VALIDATOR);
        } else {
            this.invBlockHolder.release();
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.timerHeldPackets.clear();
        this.timerHoldStartMs = System.currentTimeMillis();
        this.invShouldBlink = false;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacketSend(PacketEvent event) {
        if (event.getType() != EventType.SEND) {
            return;
        }
        if (!this.isWatchdogActive() || mc.thePlayer == null) {
            this.flushTimerPackets();
            return;
        }
        Packet<?> packet = event.getPacket();
        if (this.watchdogInv.getValue()) {
            this.handleInvPacket(packet);
        }
        if (event.isCancelled()) {
            return;
        }
    }

    private void handleTimerPacket(PacketEvent event, Packet<?> packet) {
        if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer c03 = (C03PacketPlayer) packet;
            if (!c03.isMoving() && !c03.getRotating()) {
                event.setCancelled(true);
                return;
            }
        }
        if (System.currentTimeMillis() - this.timerHoldStartMs <= TIMER_HOLD_WINDOW_MS) {
            if (packet instanceof C0FPacketConfirmTransaction || packet instanceof C00PacketKeepAlive) {
                event.setCancelled(true);
                this.timerHeldPackets.add(packet);
            }
        } else {
            this.flushTimerPackets();
        }
    }

    private void flushTimerPackets() {
        if (this.timerHeldPackets.isEmpty()) {
            return;
        }
        if (mc.getNetHandler() == null) {
            this.timerHeldPackets.clear();
            return;
        }
        for (Packet<?> held : this.timerHeldPackets) {
            if (this.timerHeldPackets.remove(held)) {
                PacketUtil.sendPacketNoEvent(held);
            }
        }
    }

    private void handleInvPacket(Packet<?> packet) {
        if (packet instanceof C0EPacketClickWindow) {
            C0EPacketClickWindow click = (C0EPacketClickWindow) packet;
            int clickMode = click.getMode();
            boolean allowed = clickMode == CLICK_MODE_QUICK_MOVE
                    || clickMode == CLICK_MODE_SWAP
                    || clickMode == CLICK_MODE_THROW;
            if (click.getWindowId() == mc.thePlayer.inventoryContainer.windowId && allowed) {
                PacketUtil.sendPacket(new C0DPacketCloseWindow(click.getWindowId()));
            } else {
                this.invShouldBlink = true;
            }
        } else if (packet instanceof C0DPacketCloseWindow
                && AccessorC0DPacketCloseWindow.getWindowId((C0DPacketCloseWindow) packet)
                == mc.thePlayer.inventoryContainer.windowId) {
            this.invShouldBlink = false;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || this.mode.getValue() != MODE_WATCHDOG) {
            return;
        }
        if (event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S3FPacketCustomPayload)) {
            return;
        }

        S3FPacketCustomPayload payload = (S3FPacketCustomPayload) event.getPacket();
        String channel = payload.getChannelName();
        if (channel == null) {
            return;
        }

        if (this.hypixelBrand.getValue()) {
            if (MOD_CONTROL_CHANNEL.equalsIgnoreCase(channel)) {
                event.setCancelled(true);
                return;
            }
            String lower = channel.toLowerCase();
            for (String prefix : HYPIXEL_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (!BRAND_CHANNEL.equals(channel)) {
            return;
        }

        String replacement = this.brand.getValue();
        if (replacement == null || replacement.isEmpty()) {
            return;
        }
        try {

            PacketBuffer data = payload.getBufferData();
            data.clear();
            data.writeString(replacement);
        } catch (Throwable readOnlyOrTooSmall) {

            event.setCancelled(true);
        }
    }
}
