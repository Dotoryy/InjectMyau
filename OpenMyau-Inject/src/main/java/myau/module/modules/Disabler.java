package myau.module.modules;

import myau.access.AccessorC03PacketPlayer;
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
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import java.util.concurrent.CopyOnWriteArrayList;

public class Disabler extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_WATCHDOG = 0;

    private static final String SPOOFED_ADDRESS = "1iro.cc/spoofing";

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

    public final BooleanProperty spoofIp = new BooleanProperty("Spoof IP", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);

    public final BooleanProperty duplicateRotPlace = new BooleanProperty("Duplicate Rot Place", false);

    private static final float DUPLICATE_ROT_MIN_DELTA = 2.0F;
    private static final float DUPLICATE_ROT_EPSILON = 0.0001F;
    private static final float DUPLICATE_ROT_NUDGE = 0.0002F;
    private float duplicateLastYaw = Float.NaN;
    private float duplicateDeltaYaw = 0.0F;
    private float duplicateLastPlacedDeltaYaw = 0.0F;
    private boolean duplicateRotated = false;

    private volatile ServerData originalServerData = null;

    private volatile ServerData spoofedServerData = null;

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
        this.restoreServerAddress();
        this.flushTimerPackets();
        this.invShouldBlink = false;
        this.invBlockHolder.release();
    }

    private boolean isWatchdogActive() {
        return this.isEnabled() && this.mode.getValue() == MODE_WATCHDOG;
    }

    private void updateServerAddressSpoof() {
        if (!this.isWatchdogActive() || !this.spoofIp.getValue()) {
            this.restoreServerAddress();
            return;
        }
        ServerData current = mc.getCurrentServerData();
        if (current == null || current == this.spoofedServerData) {
            return;
        }
        ServerData copy = new ServerData(current.serverName, SPOOFED_ADDRESS, current.isOnLAN());
        copy.copyFrom(current);
        copy.serverIP = SPOOFED_ADDRESS;
        this.originalServerData = current;
        this.spoofedServerData = copy;
        mc.setServerData(copy);
    }

    private void restoreServerAddress() {
        ServerData spoofed = this.spoofedServerData;
        ServerData original = this.originalServerData;
        this.spoofedServerData = null;
        this.originalServerData = null;
        if (spoofed != null && original != null && mc.getCurrentServerData() == spoofed) {
            mc.setServerData(original);
        }
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
        this.updateServerAddressSpoof();
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
        this.updateServerAddressSpoof();
        this.timerHeldPackets.clear();
        this.timerHoldStartMs = System.currentTimeMillis();
        this.invShouldBlink = false;
    }

    @EventTarget(Priority.LOW)
    public void onDuplicateRotPlace(PacketEvent event) {
        if (event.getType() != EventType.SEND || event.isCancelled()
                || !this.isEnabled() || !this.duplicateRotPlace.getValue()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer movement = (C03PacketPlayer) packet;
            if (!movement.getRotating()) {
                return;
            }
            float yaw = movement.getYaw();
            float previous = this.duplicateLastYaw;
            this.duplicateLastYaw = yaw;
            if (Float.isNaN(previous)) {
                return;
            }
            this.duplicateDeltaYaw = Math.abs(yaw - previous);
            this.duplicateRotated = true;
            if (this.duplicateDeltaYaw > DUPLICATE_ROT_MIN_DELTA
                    && Math.abs(this.duplicateDeltaYaw - this.duplicateLastPlacedDeltaYaw)
                            < DUPLICATE_ROT_EPSILON) {
                float nudged = yaw + DUPLICATE_ROT_NUDGE;
                AccessorC03PacketPlayer.setYaw(movement, nudged);
                this.duplicateLastYaw = nudged;
                this.duplicateDeltaYaw = Math.abs(nudged - previous);
            }
        } else if (packet instanceof C08PacketPlayerBlockPlacement && this.duplicateRotated) {
            this.duplicateLastPlacedDeltaYaw = this.duplicateDeltaYaw;
            this.duplicateRotated = false;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacketSend(PacketEvent event) {
        if (event.getType() != EventType.SEND) {
            return;
        }
        if (event.getPacket() instanceof C00Handshake
                && ((C00Handshake) event.getPacket()).getRequestedState() == EnumConnectionState.LOGIN) {
            this.updateServerAddressSpoof();
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
