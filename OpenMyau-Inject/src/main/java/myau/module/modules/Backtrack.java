package myau.module.modules;

import myau.access.AccessorRenderManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.inject.Log;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ItemUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final long POSITION_INTERP_MS = 80L;
    private static final double POS_EPS = 1.0E-6;
    private static final double MOVEMENT_DISTANCE_EPS = 0.001;
    private static final int WARMUP_TICKS = 20;

    public final FloatProperty maxDelay = new FloatProperty("delay", 200.0F, 50.0F, 500.0F, 10.0F);
    public final FloatProperty minDistance =
            new FloatProperty("distance-min", 0.0F, 0.0F, 3.0F, 0.1F);
    public final FloatProperty maxDistance =
            new FloatProperty("distance-max", 4.0F, 1.0F, 6.0F, 0.1F);
    public final FloatProperty cooldown = new FloatProperty("cooldown", 0.0F, 0.0F, 2000.0F, 50.0F);
    public final FloatProperty maxHurtTime =
            new FloatProperty("max-hurt-time", 500.0F, 0.0F, 500.0F, 10.0F);
    public final BooleanProperty disableAdventure = new BooleanProperty("disable-adventure", true);
    public final BooleanProperty flushOnDamage = new BooleanProperty("flush-on-damage", true);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapon-only", false);
    public final BooleanProperty showServerPosition =
            new BooleanProperty("show-server-position", true);
    public final ColorProperty positionColor =
            new ColorProperty("position-color", 0x0000FA, () -> this.showServerPosition.getValue());
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    public final IntProperty positionAlpha =
            new IntProperty("position-alpha", 100, 0, 255, 5, () -> this.showServerPosition.getValue());

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<TimedPacket>();
    private Vec3 targetPos = null;
    private Vec3 lastTargetSnapshot = null;
    private EntityPlayer target = null;
    private int currentDelay = 0;
    private long lastDeactivationTime = 0L;
    private long lastQueuedReleaseAt = 0L;
    private boolean delayingPackets = false;
    private boolean wasActive = false;
    private Vec3 positionInterpFrom = null;
    private Vec3 positionInterpTo = null;
    private long positionInterpStartMs = 0L;
    private String lastDebugLine = null;

    public Backtrack() {
        super("Backtrack", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{
                this.currentDelay > 0 && !this.isPacketQueueEmpty()
                        ? this.currentDelay + "ms"
                        : (int) this.maxDelay.getValue().floatValue() + "ms"
        };
    }

    @Override
    public void onEnabled() {
        this.clearPendingPackets();
        this.targetPos = null;
        this.lastTargetSnapshot = null;
        this.target = null;
        this.currentDelay = 0;
        this.lastDeactivationTime = 0L;
        this.delayingPackets = false;
        this.wasActive = false;
        this.clearPositionInterp();
    }

    @Override
    public void onDisabled() {
        if (mc.isSingleplayer()) {
            this.resetWithoutProcessingPackets();
        } else {
            this.releaseAll();
        }
        this.target = null;
        this.targetPos = null;
        this.lastTargetSnapshot = null;
        this.currentDelay = 0;
        this.clearPositionInterp();
    }

    private double minDistanceValue() {
        return Math.min(this.minDistance.getValue(), this.maxDistance.getValue());
    }


    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled()) {
            return;
        }
        if (mc.isSingleplayer()) {
            this.resetWithoutProcessingPackets();
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.releaseAll();
            return;
        }
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) {
            this.releaseAll();
            return;
        }
        if (this.disableAdventure.getValue() && isAdventure()) {
            this.releaseAll();
            return;
        }
        if (this.flushOnDamage.getValue() && mc.thePlayer.hurtTime > 0) {
            this.releaseAll();
            return;
        }
        if (this.targetPos != null && this.target != null) {
            double distance = RotationUtil.distanceToBoxAt(this.target, this.targetPos);
            if ((distance > this.maxDistance.getValue() || distance < this.minDistanceValue())
                    && this.wasActive) {
                this.releaseAll();
                this.lastDeactivationTime = System.currentTimeMillis();
                this.wasActive = false;
            }
        }
        this.processDelayedPackets();
        if (this.isPacketQueueEmpty() && this.target != null) {
            this.targetPos = this.target.getPositionVector();
        }
        this.wasActive = this.currentDelay > 0 && !this.isPacketQueueEmpty();
        if (this.debug.getValue()) {
            this.reportState();
        } else {
            this.lastDebugLine = null;
        }
    }

    private void reportState() {
        int queued = this.packetQueue.size();
        double offset = -1.0;
        if (this.target != null && this.targetPos != null) {
            offset = this.targetPos.distanceTo(this.target.getPositionVector());
        }
        String line = String.format(
                "[BT] target=%s delay=%d queue=%d delaying=%b active=%b offset=%.2f draw=%b",
                this.target == null ? "none" : this.target.getName(),
                this.currentDelay, queued, this.delayingPackets, this.wasActive, offset,
                this.target != null && this.targetPos != null && this.currentDelay > 0
                        && this.showServerPosition.getValue());
        if (!line.equals(this.lastDebugLine)) {
            this.lastDebugLine = line;
            Log.line(line);
        }
    }

    private static boolean isAdventure() {
        GameType gameType = mc.playerController.getCurrentGameType();
        return gameType != null && gameType.isAdventure();
    }


    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.isSingleplayer() || mc.thePlayer == null) {
            return;
        }
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()) {
            return;
        }
        if (this.disableAdventure.getValue() && isAdventure()) {
            return;
        }
        if (this.cooldown.getValue() > 0.0F && this.lastDeactivationTime > 0L
                && System.currentTimeMillis() - this.lastDeactivationTime
                < this.cooldown.getValue()) {
            return;
        }
        if (!(event.getTarget() instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer attacked = (EntityPlayer) event.getTarget();
        if (TeamUtil.isBot(attacked) || TeamUtil.isFriend(attacked)
                || this.ignoreTeammates.getValue() && TeamUtil.isSameTeam(attacked)) {
            return;
        }
        double distance = mc.thePlayer.getDistanceToEntity(attacked);
        if (distance > this.maxDistance.getValue() || distance < this.minDistanceValue()) {
            return;
        }
        if (this.maxHurtTime.getValue() < 500.0F
                && attacked.hurtTime * 50 > this.maxHurtTime.getValue()) {
            return;
        }
        if (this.target == null || attacked != this.target) {
            this.releaseAll();
            this.targetPos = attacked.getPositionVector();
        }
        this.target = attacked;
        this.currentDelay = (int) this.maxDelay.getValue().floatValue();
    }


    @EventTarget(Priority.LOW)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || mc.isSingleplayer()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (mc.thePlayer.ticksExisted < WARMUP_TICKS) {
            this.clearPendingPackets();
            this.delayingPackets = false;
            return;
        }
        Packet<?> packet = event.getPacket();
        this.processDelayedPackets();

        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword()
                || this.disableAdventure.getValue() && isAdventure()
                || this.flushOnDamage.getValue() && mc.thePlayer.hurtTime > 0
                || this.target == null) {
            this.releaseAll();
            return;
        }
        if (packet instanceof S40PacketDisconnect) {
            this.releaseAll();
            this.target = null;
            this.targetPos = null;
            return;
        }
        if (packet instanceof S13PacketDestroyEntities) {
            for (int id : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                if (this.target != null && id == this.target.getEntityId()) {
                    this.target = null;
                    this.targetPos = null;
                    this.releaseAll();
                    return;
                }
            }
        }
        if (this.currentDelay <= 0) {
            return;
        }

        Vec3 nextTargetSnapshot = this.readTargetPosition(packet);
        if (nextTargetSnapshot != null) {
            this.delayingPackets = this.isMovingAway(this.lastTargetSnapshot, nextTargetSnapshot);
            this.targetPos = nextTargetSnapshot;
            this.lastTargetSnapshot = nextTargetSnapshot;
        }
        if (this.shouldFlushForPacket(packet)) {
            this.flushPendingPackets();
            return;
        }
        if (this.delayingPackets) {
            this.queuePacket(packet);
            event.setCancelled(true);
            return;
        }
        if (!this.isPacketQueueEmpty()) {
            this.flushPendingPackets();
        }
    }

    private Vec3 readTargetPosition(Packet<?> packet) {
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity movePacket = (S14PacketEntity) packet;
            if (movePacket.getEntity(mc.theWorld) != this.target) {
                return null;
            }
            Vec3 position = this.targetPos != null ? this.targetPos : this.target.getPositionVector();
            return position.addVector(
                    movePacket.func_149062_c() / 32.0,
                    movePacket.func_149061_d() / 32.0,
                    movePacket.func_149064_e() / 32.0);
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleportPacket = (S18PacketEntityTeleport) packet;
            if (teleportPacket.getEntityId() != this.target.getEntityId()) {
                return null;
            }
            return new Vec3(teleportPacket.getX() / 32.0, teleportPacket.getY() / 32.0,
                    teleportPacket.getZ() / 32.0);
        }
        return null;
    }

    private boolean shouldFlushForPacket(Packet<?> packet) {
        if (packet instanceof S08PacketPlayerPosLook) {
            return true;
        }
        return packet instanceof S12PacketEntityVelocity && mc.thePlayer != null
                && ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId();
    }

    private boolean isMovingAway(Vec3 previousSnapshot, Vec3 newSnapshot) {
        if (previousSnapshot == null || newSnapshot == null || this.target == null) {
            return false;
        }
        double previousDistance = RotationUtil.distanceToBoxAt(this.target, previousSnapshot);
        double newDistance = RotationUtil.distanceToBoxAt(this.target, newSnapshot);
        return newDistance > previousDistance + MOVEMENT_DISTANCE_EPS;
    }


    private void processDelayedPackets() {
        synchronized (this.packetQueue) {
            long now = System.currentTimeMillis();
            while (!this.packetQueue.isEmpty()) {
                TimedPacket timed = this.packetQueue.peek();
                if (timed == null || now < timed.releaseAt) {
                    break;
                }
                this.packetQueue.poll();
                processPacket(timed.packet);
            }
            if (this.packetQueue.isEmpty()) {
                this.lastQueuedReleaseAt = 0L;
            }
        }
    }

    private void queuePacket(Packet<?> packet) {
        synchronized (this.packetQueue) {
            long releaseAt = System.currentTimeMillis() + this.currentDelay;
            if (this.lastQueuedReleaseAt > 0L && releaseAt <= this.lastQueuedReleaseAt) {
                releaseAt = this.lastQueuedReleaseAt + 1L;
            }
            this.packetQueue.add(new TimedPacket(packet, releaseAt));
            this.lastQueuedReleaseAt = releaseAt;
        }
    }

    private void releaseAll() {
        this.flushPendingPackets();
        this.currentDelay = 0;
        this.lastTargetSnapshot = null;
        this.delayingPackets = false;
        this.clearPositionInterp();
    }

    private void flushPendingPackets() {
        synchronized (this.packetQueue) {
            while (!this.packetQueue.isEmpty()) {
                TimedPacket timed = this.packetQueue.poll();
                if (timed != null) {
                    processPacket(timed.packet);
                }
            }
            this.lastQueuedReleaseAt = 0L;
        }
    }

    private void clearPendingPackets() {
        synchronized (this.packetQueue) {
            this.packetQueue.clear();
            this.lastQueuedReleaseAt = 0L;
        }
    }

    private boolean isPacketQueueEmpty() {
        synchronized (this.packetQueue) {
            return this.packetQueue.isEmpty();
        }
    }

    private void resetWithoutProcessingPackets() {
        this.clearPendingPackets();
        this.target = null;
        this.targetPos = null;
        this.lastTargetSnapshot = null;
        this.currentDelay = 0;
        this.lastDeactivationTime = 0L;
        this.delayingPackets = false;
        this.wasActive = false;
        this.clearPositionInterp();
    }

    @SuppressWarnings("unchecked")
    private static void processPacket(Packet<?> packet) {
        if (packet == null || mc.getNetHandler() == null) {
            return;
        }
        try {
            ((Packet<net.minecraft.network.play.INetHandlerPlayClient>) packet)
                    .processPacket(mc.getNetHandler());
        } catch (Throwable ignored) {
        }
    }


    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.isSingleplayer() || !this.showServerPosition.getValue()) {
            return;
        }
        if (this.target == null || this.targetPos == null || this.target.isDead
                || this.currentDelay <= 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (this.positionInterpTo == null) {
            this.positionInterpFrom = this.targetPos;
            this.positionInterpTo = this.targetPos;
            this.positionInterpStartMs = nowMs;
        } else if (positionChanged(this.targetPos, this.positionInterpTo)) {
            double elapsedProgress =
                    Math.min(1.0, (double) (nowMs - this.positionInterpStartMs) / POSITION_INTERP_MS);
            this.positionInterpFrom =
                    lerpVec3(this.positionInterpFrom, this.positionInterpTo, elapsedProgress);
            this.positionInterpTo = this.targetPos;
            this.positionInterpStartMs = nowMs;
        }
        double progress =
                Math.min(1.0, (double) (nowMs - this.positionInterpStartMs) / POSITION_INTERP_MS);
        drawPlayerBoundingBox(lerpVec3(this.positionInterpFrom, this.positionInterpTo, progress),
                this.positionColor.getValue(), this.positionAlpha.getValue());
    }

    private static void drawPlayerBoundingBox(Vec3 position, int color, int alpha) {
        if (mc.thePlayer == null) {
            return;
        }
        double x = position.xCoord - AccessorRenderManager.getRenderPosX(mc.getRenderManager());
        double y = position.yCoord - AccessorRenderManager.getRenderPosY(mc.getRenderManager());
        double z = position.zCoord - AccessorRenderManager.getRenderPosZ(mc.getRenderManager());
        AxisAlignedBB playerBox = mc.thePlayer.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        AxisAlignedBB box = new AxisAlignedBB(
                playerBox.minX - mc.thePlayer.posX + x,
                playerBox.minY - mc.thePlayer.posY + y,
                playerBox.minZ - mc.thePlayer.posZ + z,
                playerBox.maxX - mc.thePlayer.posX + x,
                playerBox.maxY - mc.thePlayer.posY + y,
                playerBox.maxZ - mc.thePlayer.posZ + z);
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;

        RenderUtil.enableRenderState();
        RenderUtil.drawBoundingBox(box, red, green, blue, alpha, 2.0F);
        GlStateManager.resetColor();
        RenderUtil.disableRenderState();
    }

    private void clearPositionInterp() {
        this.positionInterpFrom = null;
        this.positionInterpTo = null;
        this.positionInterpStartMs = 0L;
    }

    private static boolean positionChanged(Vec3 first, Vec3 second) {
        return Math.abs(first.xCoord - second.xCoord) > POS_EPS
                || Math.abs(first.yCoord - second.yCoord) > POS_EPS
                || Math.abs(first.zCoord - second.zCoord) > POS_EPS;
    }

    private static Vec3 lerpVec3(Vec3 from, Vec3 to, double progress) {
        if (progress <= 0.0) {
            return from;
        }
        if (progress >= 1.0) {
            return to;
        }
        return new Vec3(from.xCoord + (to.xCoord - from.xCoord) * progress,
                from.yCoord + (to.yCoord - from.yCoord) * progress,
                from.zCoord + (to.zCoord - from.zCoord) * progress);
    }


    public boolean isRenderingServerPositionFor(EntityLivingBase entity) {
        return !mc.isSingleplayer() && this.showServerPosition.getValue() && this.target == entity
                && this.targetPos != null && !this.target.isDead && this.currentDelay > 0;
    }

    public Vec3 getBacktrackPosition() {
        return this.target != null && this.targetPos != null && !this.target.isDead
                && this.currentDelay > 0 ? this.targetPos : null;
    }

    public EntityPlayer getBacktrackTarget() {
        return this.target != null && !this.target.isDead && this.currentDelay > 0
                ? this.target : null;
    }

    private static final class TimedPacket {
        private final Packet<?> packet;
        private final long releaseAt;

        private TimedPacket(Packet<?> packet, long releaseAt) {
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }
}
