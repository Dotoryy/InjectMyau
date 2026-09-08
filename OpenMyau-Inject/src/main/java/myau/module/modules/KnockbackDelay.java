package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.timeout.ModuleBackedTimeout;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import org.lwjgl.input.Mouse;

public class KnockbackDelay extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int LEFT_MOUSE = 0;

    public final FloatProperty distanceToTarget =
            new FloatProperty("distance-to-target", 6.0F, 3.0F, 12.0F, 0.1F);
    public final PercentProperty chance = new PercentProperty("chance", 100);
    public final IntProperty maximumDelay = new IntProperty("maximum-delay", 200, 50, 1000, 10);
    public final BooleanProperty inAir = new BooleanProperty("in-air", true);
    public final BooleanProperty lookingAtPlayer = new BooleanProperty("looking-at-player", false);
    public final BooleanProperty requireLeftMouse = new BooleanProperty("require-left-mouse", false);
    public final BooleanProperty onlyWhitelistedItem = new BooleanProperty("restrict-held-item", false);
    public final TextProperty whitelistedItems = new TextProperty("whitelisted-items", "",
            () -> this.onlyWhitelistedItem.getValue());

    private LagRequest inboundLagRequest;

    public KnockbackDelay() {
        super("Knockback Delay", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%dms", this.maximumDelay.getValue())};
    }

    @Override
    public void onDisabled() {
        this.flushInboundLagAndClear();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.flushInboundLagAndClear();
            return;
        }
        if (!(event.getPacket() instanceof S12PacketEntityVelocity)
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
        if (packet.getEntityID() != mc.thePlayer.getEntityId()) {
            return;
        }
        if (this.conditionsFailureReason() != null || this.isInboundSessionActive()) {
            return;
        }
        if (this.chance.getValue() < 100 && Math.random() * 100.0 >= this.chance.getValue()) {
            return;
        }
        this.inboundLagRequest =
                new LagRequest(EnumLagDirection.ONLY_INBOUND, new ModuleBackedTimeout(this));
        Myau.lagHandler.requestLag(this.inboundLagRequest);
    }

    @EventTarget(Priority.LOWEST)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead) {
            this.flushInboundLagAndClear();
            return;
        }
        if (!this.isInboundSessionActive()) {
            return;
        }
        if (this.conditionsFailureReason() != null) {
            this.flushInboundLagAndClear();
            return;
        }
        Myau.lagHandler.releaseExpiredPackets(
                EnumLagDirection.INBOUND, this.maximumDelay.getValue());
    }

    private boolean isInboundSessionActive() {
        return this.inboundLagRequest != null
                && !this.inboundLagRequest.getTimeout().isTimedOut();
    }

    private void flushInboundLagAndClear() {
        if (this.inboundLagRequest != null) {
            this.inboundLagRequest.getTimeout().forceTimeOut();
            this.inboundLagRequest = null;
        }
    }

    private String conditionsFailureReason() {
        double maxDistance = this.distanceToTarget.getValue();
        if (findTarget(maxDistance) == null) {
            return "no target in range";
        }
        if (this.inAir.getValue() && mc.thePlayer.onGround) {
            return "not in air";
        }
        if (this.lookingAtPlayer.getValue() && mouseOverTarget(maxDistance) == null) {
            return "not looking at player";
        }
        if (this.requireLeftMouse.getValue() && !Mouse.isButtonDown(LEFT_MOUSE)) {
            return "LMB not held";
        }
        if (this.onlyWhitelistedItem.getValue()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !this.matchesWhitelist(held)) {
                return "held item not whitelisted";
            }
        }
        return null;
    }

    private boolean matchesWhitelist(ItemStack stack) {
        String list = this.whitelistedItems.getValue();
        if (list == null || list.trim().isEmpty()) {
            return false;
        }
        String name = stack.getDisplayName();
        if (name == null) {
            return false;
        }
        name = name.toLowerCase();
        for (String entry : list.split(",")) {
            String trimmed = entry.trim().toLowerCase();
            if (!trimmed.isEmpty() && name.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static EntityPlayer findTarget(double maxDistance) {
        EntityPlayer underCrosshair = mouseOverTarget(maxDistance);
        if (underCrosshair != null) {
            return underCrosshair;
        }
        EntityPlayer closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidTarget(player, maxDistance)) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(player);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = player;
            }
        }
        return closest;
    }

    private static EntityPlayer mouseOverTarget(double maxDistance) {
        if (mc.objectMouseOver == null) {
            return null;
        }
        Entity hit = mc.objectMouseOver.entityHit;
        if (!(hit instanceof EntityPlayer)) {
            return null;
        }
        EntityPlayer player = (EntityPlayer) hit;
        return isValidTarget(player, maxDistance) ? player : null;
    }

    private static boolean isValidTarget(EntityPlayer player, double maxDistance) {
        if (player == null || player == mc.thePlayer || player.isDead || player.deathTime > 0) {
            return false;
        }
        if (TeamUtil.isFriend(player) || TeamUtil.isBot(player) || TeamUtil.isSameTeam(player)) {
            return false;
        }
        return RotationUtil.distanceToEntity(player) <= maxDistance;
    }
}
