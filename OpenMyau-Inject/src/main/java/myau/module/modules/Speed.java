package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.access.AccessorEntity;
import myau.access.AccessorMinecraft;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.JumpEvent;
import myau.events.LivingUpdateEvent;
import myau.events.MoveInputEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.StrafeEvent;
import myau.module.Module;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.MoveUtil;
import myau.util.MovementTicks;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;

public class Speed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_MATRIX = 1;
    private static final float NORMAL_TIMER = 1.0F;
    private static final double MATRIX_POSITION_STEP = 0.015625;
    private static final double MATRIX_LOW_SPEED = 0.2;
    private static final double MATRIX_MIN_SPEED = 0.195;
    private static final double MATRIX_GROUND_MULTIPLIER = 1.001;
    private static final double MATRIX_MOTION_Y_STEP = 0.00348;
    private static final double MATRIX_PARTIAL_STRAFE = 65.0;
    private static final double MATRIX_SNEAK_SPEED = 0.07;
    private static final int MATRIX_VELOCITY_TICKS = 12;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"VANILLA", "MATRIX"});
    public final FloatProperty multiplier = new FloatProperty("multiplier", 1.0F, 0.0F, 10.0F,
            () -> this.mode.getValue() != MODE_MATRIX);
    public final FloatProperty friction = new FloatProperty("friction", 1.0F, 0.0F, 10.0F,
            () -> this.mode.getValue() != MODE_MATRIX);
    public final PercentProperty strafe = new PercentProperty("strafe", 0,
            () -> this.mode.getValue() != MODE_MATRIX);
    public final FloatProperty timerSneakBoost = new FloatProperty("timer-sneak-boost", 30.0F, 1.0F, 100.0F, 0.1F,
            () -> this.mode.getValue() == MODE_MATRIX);

    private int ticksSinceSneak = 0;
    private boolean timerModified = false;

    private boolean canBoost() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        return !scaffold.isEnabled() && MoveUtil.isForwardPressed()
                && mc.thePlayer.getFoodStats().getFoodLevel() > 6
                && !mc.thePlayer.isSneaking()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isInLava()
                && !AccessorEntity.getIsInWeb(mc.thePlayer);
    }

    public Speed() {
        super("Speed", false);
    }

    private boolean isMatrix() {
        return this.isEnabled() && this.mode.getValue() == MODE_MATRIX && mc.thePlayer != null;
    }

    @EventTarget(Priority.LOW)
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX) {
            this.matrixStrafe();
            return;
        }
        if (this.canBoost()) {
            if (mc.thePlayer.onGround) {
                mc.thePlayer.motionY = 0.42F;
                MoveUtil.setSpeed(
                        MoveUtil.getJumpMotion() * (double) this.multiplier.getValue().floatValue(),
                        MoveUtil.getMoveYaw()
                );
            } else {
                if (this.friction.getValue() != 1.0F) {
                    event.setFriction(event.getFriction() * this.friction.getValue());
                }
                if (this.strafe.getValue() > 0) {
                    double speed = MoveUtil.getSpeed();
                    MoveUtil.setSpeed(speed * (double) ((float) (100 - this.strafe.getValue()) / 100.0F), MoveUtil.getDirectionYaw());
                    MoveUtil.addSpeed(
                            speed * (double) ((float) this.strafe.getValue().intValue() / 100.0F), MoveUtil.getMoveYaw()
                    );
                    MoveUtil.setSpeed(speed);
                }
            }
        }
    }

    private void matrixStrafe() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        boolean aligned = mc.thePlayer.posY % MATRIX_POSITION_STEP == 0.0;
        if (aligned || MoveUtil.getSpeed() < MATRIX_LOW_SPEED) {
            MoveUtil.strafe();
        }
        if (MoveUtil.getSpeed() < MATRIX_MIN_SPEED && !mc.thePlayer.isUsingItem()) {
            MoveUtil.strafe(MATRIX_MIN_SPEED);
        }
        if (mc.thePlayer.onGround) {
            mc.thePlayer.motionX *= MATRIX_GROUND_MULTIPLIER;
            mc.thePlayer.motionZ *= MATRIX_GROUND_MULTIPLIER;
            MoveUtil.strafe();
        }
        if (MovementTicks.sinceVelocity() > 1) {
            mc.thePlayer.motionY -= MATRIX_MOTION_Y_STEP;
        }
        if (MovementTicks.air() == 1) {
            MoveUtil.partialStrafePercent(MATRIX_PARTIAL_STRAFE);
        }
        if (MoveUtil.collidesVertically(mc.thePlayer.motionY)) {
            MoveUtil.partialStrafePercent(MATRIX_PARTIAL_STRAFE);
        }
        if (MovementTicks.sinceVelocity() < MATRIX_VELOCITY_TICKS) {
            MoveUtil.strafe();
        }
        MoveUtil.useDiagonalSpeed();
        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            this.ticksSinceSneak = 0;
            MoveUtil.strafe(MATRIX_SNEAK_SPEED);
            this.setTimer(this.timerSneakBoost.getValue());
        } else {
            this.ticksSinceSneak++;
            if (this.ticksSinceSneak == 1) {
                this.sendPositionBurst();
            }
            this.resetTimer();
        }
    }

    private void sendPositionBurst() {
        PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
        PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, false));
        PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
    }

    private void setTimer(float value) {
        AccessorMinecraft.getTimer(mc).timerSpeed = value;
        this.timerModified = true;
    }

    private void resetTimer() {
        if (!this.timerModified) {
            return;
        }
        this.timerModified = false;
        Timer timer = (Timer) Myau.moduleManager.modules.get(Timer.class);
        if (timer != null && timer.isEnabled()) {
            return;
        }
        AccessorMinecraft.getTimer(mc).timerSpeed = NORMAL_TIMER;
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (this.isMatrix()) {
            this.ticksSinceSneak++;
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isMatrix() || mc.thePlayer.movementInput == null) {
            return;
        }
        mc.thePlayer.movementInput.sneak = false;
        mc.thePlayer.movementInput.jump = true;
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isMatrix()) {
            return;
        }
        if (mc.thePlayer.onGround && !mc.thePlayer.isUsingItem()) {
            mc.thePlayer.jump();
        }
    }

    @EventTarget(Priority.LOW)
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled() && this.mode.getValue() != MODE_MATRIX && this.canBoost()) {
            mc.thePlayer.movementInput.jump = false;
        }
    }

    @Override
    public void onDisabled() {
        this.ticksSinceSneak = 0;
        this.resetTimer();
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() != MODE_MATRIX) {
            return new String[0];
        }
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
