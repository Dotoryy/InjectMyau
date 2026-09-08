package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.TickEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.timeout.ModuleBackedTimeout;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.util.Set;

public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_INBOUND = 0;
    private static final int MODE_OUTBOUND = 1;
    private static final int MODE_BOTH = 2;

    public final ModeProperty mode = new ModeProperty("mode", MODE_OUTBOUND,
            new String[]{"INBOUND", "OUTBOUND", "BOTH"});
    public final IntProperty packetDelay = new IntProperty("packet-delay", 0, 0, 1500, 20);

    private int appliedMode = -1;
    private long appliedDelayMs = -1L;
    private LagRequest activeLagRequest;

    public FakeLag() {
        super("Fake Lag", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%dms", this.packetDelay.getValue())};
    }

    @Override
    public void onEnabled() {
        if (mc.isSingleplayer()) {
            ChatUtil.sendFormatted("&cFake lag cannot be enabled in singleplayer.");
            this.setEnabled(false);
            return;
        }
        Blink blink = (Blink) Myau.moduleManager.modules.get(Blink.class);
        if (blink != null && blink.isEnabled()) {
            ChatUtil.sendFormatted("&cCannot use fake lag with blink!");
            this.setEnabled(false);
            return;
        }
        this.appliedMode = this.mode.getValue();
        this.appliedDelayMs = this.packetDelay.getValue();
        this.rebindLagRequest();
    }

    @Override
    public void onDisabled() {
        if (this.activeLagRequest != null) {
            this.activeLagRequest.getTimeout().forceTimeOut();
            this.activeLagRequest = null;
        }
        this.appliedMode = -1;
        this.appliedDelayMs = -1L;
    }

    private void rebindLagRequest() {
        if (this.activeLagRequest != null) {
            this.activeLagRequest.getTimeout().forceTimeOut();
        }
        this.activeLagRequest =
                new LagRequest(this.lagDirectionsForMode(), new ModuleBackedTimeout(this));
        Myau.lagHandler.requestLag(this.activeLagRequest);
    }

    private Set<EnumLagDirection> lagDirectionsForMode() {
        switch (this.mode.getValue()) {
            case MODE_INBOUND:
                return EnumLagDirection.ONLY_INBOUND;
            case MODE_BOTH:
                return EnumLagDirection.BIDIRECTIONAL;
            case MODE_OUTBOUND:
            default:
                return EnumLagDirection.ONLY_OUTBOUND;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        long delayMs = this.packetDelay.getValue();
        if (delayMs <= 0L) {
            this.setEnabled(false);
            return;
        }
        if (this.mode.getValue() != this.appliedMode || delayMs != this.appliedDelayMs) {
            this.appliedMode = this.mode.getValue();
            this.appliedDelayMs = delayMs;
            this.rebindLagRequest();
        }
        Set<EnumLagDirection> directions = this.lagDirectionsForMode();
        if (directions.contains(EnumLagDirection.INBOUND)) {
            Myau.lagHandler.releaseExpiredPackets(EnumLagDirection.INBOUND, delayMs);
        }
        if (directions.contains(EnumLagDirection.OUTBOUND)) {
            Myau.lagHandler.releaseExpiredPackets(EnumLagDirection.OUTBOUND, delayMs);
        }
    }
}
