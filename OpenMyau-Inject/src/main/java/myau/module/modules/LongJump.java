package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.access.AccessorPlayerControllerMP;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.FloatProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import myau.property.properties.BooleanProperty;

public class LongJump extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil fireballTimer = new TimerUtil();
    private final TimerUtil jumpTimer = new TimerUtil();
    private boolean isJumping = false;
    private int tickCounter = 0;
    private int jumpModeStage = 0;
    private boolean readyToUseFireball = false;
    private boolean fireballLaunched = false;
    private int savedHotbarSlot = -1;
    private static final int MODE_MATRIX2 = 4;
    private static final double MATRIX_MOTION_Y_STEP = 0.00348;
    private static final double MATRIX_JUMP_MOTION = 0.42;
    private static final double MATRIX_FALL_STRAFE = 1.97;
    private static final float MATRIX_FALL_TRIGGER = 0.1F;
    private boolean matrixStarted = false;
    private boolean hadViewBobbing = false;
    private double startX = 0.0;
    private double startY = 0.0;
    private double startZ = 0.0;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"FIREBALL", "FIREBALL_MANUAL", "FIREBALL_HIGH", "FIREBALL_FLAT", "MATRIX2"});
    public final BooleanProperty silent = new BooleanProperty("silent", false, () -> this.mode.getValue() == MODE_MATRIX2);
    public final FloatProperty motion = new FloatProperty("motion", 1.0F, 1.0F, 20.0F);
    public final FloatProperty speedMotion = new FloatProperty("speed-motion", 1.0F, 1.0F, 20.0F);
    public final PercentProperty strafe = new PercentProperty("strafe", 0);

    private int findFireballInHotbar() {
        if (mc.thePlayer == null) {
            return -1;
        } else {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() instanceof ItemFireball) {
                    return i;
                }
            }
            return -1;
        }
    }

    private double getMotionFactor() {
        return MoveUtil.getSpeedLevel() > 0
                ? (double) this.speedMotion.getValue()
                : (double) this.motion.getValue();
    }

    public LongJump() {
        super("Long Jump", false);
    }

    public boolean isAutoMode() {
        return this.mode.getValue() == 0 || this.mode.getValue() == 2 || this.mode.getValue() == 3;
    }

    public boolean isManualMode() {
        return this.mode.getValue() == 1;
    }

    public boolean isLongJumpMode() {
        return this.isAutoMode() || this.isManualMode();
    }

    private boolean isMatrix() {
        return this.isEnabled() && this.mode.getValue() == MODE_MATRIX2 && mc.thePlayer != null;
    }

    public boolean canStartJump() {
        return !this.fireballTimer.hasTimeElapsed(1000L) && !this.isJumping;
    }

    public boolean isJumping() {
        return this.isJumping;
    }

    @EventTarget(Priority.HIGHEST)
    public void onKnockback(KnockbackEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            if ((this.isManualMode() || this.isAutoMode()) && this.canStartJump()) {
                event.setCancelled(true);
                this.isJumping = true;
                this.tickCounter = 0;
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.isAutoMode() && !this.fireballLaunched && this.readyToUseFireball) {
                        int slot = this.findFireballInHotbar();
                        if (slot != -1) {
                            this.savedHotbarSlot = mc.thePlayer.inventory.currentItem;
                            mc.thePlayer.inventory.currentItem = slot;
                            AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
                            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                            this.fireballTimer.reset();
                            this.fireballLaunched = true;
                        }
                    }
                    break;
                case POST:
                    if (this.savedHotbarSlot != -1) {
                        mc.thePlayer.inventory.currentItem = this.savedHotbarSlot;
                        this.savedHotbarSlot = -1;
                    }
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.isLongJumpMode() && this.isJumping) {
                this.tickCounter++;
                if (this.tickCounter == 1) {
                    switch (this.mode.getValue()) {
                        case 0:
                        case 1:
                            this.jumpModeStage = 0;
                            break;
                        case 2:
                            this.jumpModeStage = 1;
                            break;
                        case 3:
                            this.jumpModeStage = MoveUtil.isForwardPressed() ? 2 : 1;
                    }
                }
                if (this.tickCounter == 2 && MoveUtil.isForwardPressed()) {
                    MoveUtil.setSpeed(MoveUtil.getSpeed() * this.getMotionFactor());
                }
                if (this.tickCounter >= 1 && this.tickCounter <= 30) {
                    switch (this.jumpModeStage) {
                        case 1:
                            if (this.tickCounter == 1) {
                                mc.thePlayer.motionY *= 0.75;
                            } else {
                                double motion = mc.thePlayer.motionY / 0.98F + 0.055;
                                if (motion > 0.0) {
                                    mc.thePlayer.motionY = motion;
                                }
                            }
                            break;
                        case 2:
                            if (this.tickCounter == 1) {
                                mc.thePlayer.motionY *= 0.75;
                            } else {
                                mc.thePlayer.motionY = 0.01 + (double) this.tickCounter * 0.003;
                            }
                    }
                }
                if (this.tickCounter >= 30) {
                    this.isJumping = false;
                    this.tickCounter = 0;
                    this.jumpModeStage = 0;
                    if (this.isAutoMode()) {
                        this.setEnabled(false);
                    }
                    return;
                }
            }
            if (this.isAutoMode() && !this.isJumping) {
                if (this.jumpTimer.hasTimeElapsed(1500L)) {
                    this.setEnabled(false);
                    return;
                }
                this.readyToUseFireball = true;
                float yaw = RotationUtil.quantizeAngle(mc.thePlayer.rotationYaw - 180.0F - RandomUtil.nextFloat(0.0F, 1.0F));
                float pitch = RotationUtil.quantizeAngle(89.0F + RandomUtil.nextFloat(-0.25F, 0.25F));
                event.setRotation(yaw, pitch, 4);
                event.setPervRotation(yaw, 4);
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isMatrix() || mc.thePlayer.movementInput == null) {
            return;
        }
        if (!MoveUtil.isMoving()) {
            mc.thePlayer.movementInput.moveForward = 1.0F;
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isMatrix() || !this.silent.getValue()) {
            return;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        mc.gameSettings.viewBobbing = false;
        view.posX = this.startX;
        view.posY = this.startY;
        view.posZ = this.startZ;
        view.prevPosX = this.startX;
        view.prevPosY = this.startY;
        view.prevPosZ = this.startZ;
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isMatrix()) {
            if (mc.thePlayer.onGround) {
                mc.thePlayer.jump();
            }
            this.matrixStarted = true;
            if (MovementTicks.sinceVelocity() > 1) {
                mc.thePlayer.motionY += MATRIX_MOTION_Y_STEP;
            }
            if (this.matrixStarted && mc.thePlayer.fallDistance > MATRIX_FALL_TRIGGER) {
                mc.thePlayer.motionY = MATRIX_JUMP_MOTION;
                MoveUtil.strafe(MATRIX_FALL_STRAFE);
            }
            return;
        }
        if (this.isEnabled()) {
            if (this.isLongJumpMode()
                    && this.isJumping
                    && this.tickCounter >= 5
                    && this.tickCounter <= 30
                    && this.strafe.getValue() > 0) {
                double speed = MoveUtil.getSpeed();
                MoveUtil.setSpeed(speed * (double) ((float) (100 - this.strafe.getValue()) / 100.0F), MoveUtil.getDirectionYaw());
                MoveUtil.addSpeed(
                        speed * (double) ((float) this.strafe.getValue() / 100.0F), MoveUtil.getMoveYaw()
                );
                MoveUtil.setSpeed(speed);
            }
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
            if (stack != null && stack.getItem() instanceof ItemFireball) {
                this.fireballTimer.reset();
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                if (this.isMatrix()) {
                    S08PacketPlayerPosLook packet = (S08PacketPlayerPosLook) event.getPacket();
                    event.setCancelled(true);
                    PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                            packet.getX(), packet.getY(), packet.getZ(),
                            mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
                    mc.thePlayer.setPosition(packet.getX(), packet.getY(), packet.getZ());
                    mc.thePlayer.jump();
                    this.setEnabled(false);
                    return;
                }
                this.isJumping = false;
                this.tickCounter = 0;
                this.jumpModeStage = 0;
                if (this.isAutoMode()) {
                    this.setEnabled(false);
                }
            }
        }
    }

    @Override
    public void onEnabled() {
        this.matrixStarted = false;
        if (this.mode.getValue() == MODE_MATRIX2 && mc.thePlayer != null) {
            this.hadViewBobbing = mc.gameSettings.viewBobbing;
            this.startX = mc.thePlayer.posX;
            this.startY = mc.thePlayer.posY;
            this.startZ = mc.thePlayer.posZ;
        }
        this.jumpTimer.reset();
        if (this.isAutoMode() && this.findFireballInHotbar() == -1) {
            this.setEnabled(false);
            ChatUtil.sendFormatted(String.format("%s%s: &cNo fireball found in your hotbar!&r", Myau.clientName, this.getName()));
        }
    }

    @Override
    public void onDisabled() {
        this.matrixStarted = false;
        if (this.hadViewBobbing) {
            mc.gameSettings.viewBobbing = true;
            this.hadViewBobbing = false;
        }
        this.isJumping = false;
        this.tickCounter = 0;
        this.jumpModeStage = 0;
        this.readyToUseFireball = false;
        this.fireballLaunched = false;
    }

    @Override
    public String[] getSuffix() {
        String mode = this.mode.getModeString();
        return mode.contains("FIREBALL") ? new String[]{"Fireball"} : new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, mode)};
    }
}
