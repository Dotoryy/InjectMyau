package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MouseButtonEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.KeyBindUtil;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

public class Freecam extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int FREE_ENTITY_ID = -8008;
    private static final double SPEED_SCALE = 0.215;
    private static final double VERTICAL_SCALE = 0.93;
    private static final float HIDDEN_ARM_PITCH = 700.0F;
    public static EntityOtherPlayerMP freeEntity = null;

    public final FloatProperty speed = new FloatProperty("speed", 2.5F, 0.5F, 10.0F, 0.5F);
    public final BooleanProperty disableOnDamage = new BooleanProperty("disable-on-damage", true);
    public final BooleanProperty allowDigging = new BooleanProperty("allow-digging", false);
    public final BooleanProperty allowInteracting = new BooleanProperty("allow-interacting", false);
    public final BooleanProperty allowPlacing = new BooleanProperty("allow-placing", false);
    public final BooleanProperty showArm = new BooleanProperty("show-arm", false);

    private int[] lastChunk = new int[]{Integer.MAX_VALUE, 0};
    private final float[] savedAngles = new float[]{0.0F, 0.0F};

    public Freecam() {
        super("Freecam", false);
    }

    public static boolean isFreeEntity(Entity entity) {
        return freeEntity != null && freeEntity == entity;
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null || !mc.thePlayer.onGround) {
            this.setEnabled(false);
            return;
        }
        freeEntity = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
        freeEntity.copyLocationAndAnglesFrom(mc.thePlayer);
        this.savedAngles[0] = freeEntity.rotationYawHead = mc.thePlayer.rotationYawHead;
        this.savedAngles[1] = mc.thePlayer.rotationPitch;
        freeEntity.setVelocity(0.0, 0.0, 0.0);
        freeEntity.setInvisible(true);
        mc.theWorld.addEntityToWorld(FREE_ENTITY_ID, freeEntity);
        mc.setRenderViewEntity(freeEntity);
    }

    @Override
    public void onDisabled() {
        if (freeEntity != null) {
            if (mc.thePlayer != null) {
                mc.setRenderViewEntity(mc.thePlayer);
                mc.thePlayer.rotationYaw = mc.thePlayer.rotationYawHead = this.savedAngles[0];
                mc.thePlayer.rotationPitch = this.savedAngles[1];
            }
            if (mc.theWorld != null) {
                mc.theWorld.removeEntity(freeEntity);
            }
            freeEntity = null;
        }
        this.lastChunk = new int[]{Integer.MAX_VALUE, 0};
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        int x = mc.thePlayer.chunkCoordX;
        int z = mc.thePlayer.chunkCoordZ;
        for (int x2 = -1; x2 <= 1; x2++) {
            for (int z2 = -1; z2 <= 1; z2++) {
                int a = x + x2;
                int b = z + z2;
                mc.theWorld.markBlockRangeForRenderUpdate(a * 16, 0, b * 16, a * 16 + 15, 256, b * 16 + 15);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (freeEntity == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.disableOnDamage.getValue() && mc.thePlayer.hurtTime != 0) {
            this.setEnabled(false);
            return;
        }
        double step = SPEED_SCALE * this.speed.getValue();
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())) {
            move(freeEntity.rotationYawHead, step, 1.0);
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode())) {
            move(freeEntity.rotationYawHead, step, -1.0);
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode())) {
            move(freeEntity.rotationYawHead - 90.0F, step, 1.0);
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode())) {
            move(freeEntity.rotationYawHead + 90.0F, step, 1.0);
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            freeEntity.posY += VERTICAL_SCALE * step;
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
            freeEntity.posY -= VERTICAL_SCALE * step;
        }
        if (this.lastChunk[0] != Integer.MAX_VALUE
                && (this.lastChunk[0] != freeEntity.chunkCoordX || this.lastChunk[1] != freeEntity.chunkCoordZ)) {
            int x = freeEntity.chunkCoordX;
            int z = freeEntity.chunkCoordZ;
            mc.theWorld.markBlockRangeForRenderUpdate(x * 16, 0, z * 16, x * 16 + 15, 256, z * 16 + 15);
        }
        this.lastChunk[0] = freeEntity.chunkCoordX;
        this.lastChunk[1] = freeEntity.chunkCoordZ;
    }

    private static void move(float yaw, double step, double direction) {
        double rad = (double) yaw * (Math.PI / 180.0);
        freeEntity.posX += -Math.sin(rad) * step * direction;
        freeEntity.posZ += Math.cos(rad) * step * direction;
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (freeEntity == null || mc.thePlayer == null) {
            return;
        }
        MovementInput input = mc.thePlayer.movementInput;
        input.moveForward = 0.0F;
        input.moveStrafe = 0.0F;
        input.jump = false;
        input.sneak = false;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.showArm.getValue()) {
            mc.thePlayer.renderArmPitch = mc.thePlayer.prevRenderArmPitch = HIDDEN_ARM_PITCH;
        }
        RenderUtil.enableRenderState();
        RenderUtil.drawEntityBoundingBox(mc.thePlayer, 0, 255, 0, 255, 2.0F, 0.1);
        RenderUtil.drawEntityBox(mc.thePlayer, 0, 255, 0);
        RenderUtil.disableRenderState();
    }

    @EventTarget
    public void onMouse(MouseButtonEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        MovingObjectType hitType = mc.objectMouseOver == null ? MovingObjectType.MISS : mc.objectMouseOver.typeOfHit;
        if (event.getButton() == 0) {
            if (hitType == MovingObjectType.BLOCK && !this.allowDigging.getValue()
                    || hitType == MovingObjectType.ENTITY && !this.allowInteracting.getValue()) {
                event.setCancelled(true);
            }
        } else if (event.getButton() == 1) {
            if (hitType == MovingObjectType.ENTITY) {
                if (!this.allowInteracting.getValue()) {
                    event.setCancelled(true);
                }
            } else if (!this.allowPlacing.getValue()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof C07PacketPlayerDigging && !this.allowDigging.getValue()
                && isBlockDigging((C07PacketPlayerDigging) packet)) {
            event.setCancelled(true);
        } else if (packet instanceof C08PacketPlayerBlockPlacement && !this.allowPlacing.getValue()) {
            event.setCancelled(true);
        } else if (packet instanceof C02PacketUseEntity && !this.allowInteracting.getValue()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        if (this.isEnabled()) {
            this.setEnabled(false);
        }
    }

    private static boolean isBlockDigging(C07PacketPlayerDigging packet) {
        C07PacketPlayerDigging.Action action = packet.getStatus();
        return action == C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
                || action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK
                || action == C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK;
    }
}
