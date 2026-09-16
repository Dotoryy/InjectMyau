package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.access.AccessorC03PacketPlayer;
import myau.access.AccessorMinecraft;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.MovementTicks;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

public class Fly extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_MATRIX = 1;
    private static final int MODE_MATRIX_DAMAGE = 2;
    private static final int MOTION_Y_NONE = 0;
    private static final int MOTION_Y_MULTIPLY = 2;
    private static final float NORMAL_TIMER = 1.0F;
    private static final double MATRIX_MOTION_Y_STEP = 0.00348;
    private static final double MATRIX_JUMP_MOTION = 0.42;
    private static final double MATRIX_FALL_STRAFE = 1.97;
    private static final double MATRIX_BOOST_STRAFE = 9.3;
    private static final int MATRIX_BOOST_TICK = 20;
    private static final int MATRIX_STOP_START = 15;
    private static final int MATRIX_STOP_END = 19;
    private static final float MATRIX_FALL_TRIGGER = 0.1F;
    private static final double DAMAGE_DEFAULT_SPEED = 0.03;
    private static final double DAMAGE_MOTION_Y_FACTOR = 0.039;
    private static final int DAMAGE_JUMP_LIMIT = 4;
    private static final int DAMAGE_AIR_TICKS = 3;
    private static final float DAMAGE_FALL_TRIGGER = 3.0F;
    private static final int SPOOF_NONE = 0;
    private static final int SPOOF_AIR = 1;
    private static final int SPOOF_GROUND = 2;

    private double verticalMotion = 0.0;
    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"VANILLA", "MATRIX", "MATRIX_DAMAGE"});
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 1.0F, 0.0F, 100.0F,
            () -> this.mode.getValue() == 0);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 1.0F, 0.0F, 100.0F,
            () -> this.mode.getValue() == 0);
    public final FloatProperty timerSpeed = new FloatProperty("damage-timer-speed", 30.0F, 1.0F, 100.0F, 0.1F,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final FloatProperty damageSpeed = new FloatProperty("damage-speed", 0.07F, 0.0F, 1.0F, 0.01F,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final BooleanProperty noSpeed = new BooleanProperty("damage-no-speed", false,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final BooleanProperty detectDamage = new BooleanProperty("damage-detect", true,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final BooleanProperty autoDisable = new BooleanProperty("damage-auto-disable", true,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final IntProperty flyTicks = new IntProperty("damage-fly-ticks", 1000, 0, 1600, 10,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE && !this.detectDamage.getValue());
    public final BooleanProperty selfDamage = new BooleanProperty("damage-self", true,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final BooleanProperty newSelfDamage = new BooleanProperty("damage-self-new", false,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final BooleanProperty resetPacket = new BooleanProperty("damage-reset-packet", true,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final ModeProperty motionYMode = new ModeProperty("damage-motion-y", 0,
            new String[]{"NONE", "SIMPLE", "MULTIPLY"},
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE);
    public final FloatProperty motionY = new FloatProperty("damage-motion", -0.01F, -0.3F, 0.3F, 0.01F,
            () -> this.mode.getValue() == MODE_MATRIX_DAMAGE && this.motionYMode.getValue() != MOTION_Y_NONE);

    private boolean teleported = false;
    private int flightTicks = 0;
    private int selfDamageJumps = 0;
    private int speedTicksLeft = 0;
    private boolean damaged = false;
    private float trackedFall = 0.0F;
    private int spoofGround = SPOOF_NONE;
    private boolean timerModified = false;

    public Fly() {
        super("Flight", false);
    }

    private boolean isMatrix() {
        return this.isEnabled() && this.mode.getValue() == MODE_MATRIX && mc.thePlayer != null;
    }

    private boolean isMatrixDamage() {
        return this.isEnabled() && this.mode.getValue() == MODE_MATRIX_DAMAGE && mc.thePlayer != null;
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX) {
            this.matrixStrafe();
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX_DAMAGE) {
            return;
        }
        if (mc.thePlayer.posY % 1.0 != 0.0) {
            mc.thePlayer.motionY = this.verticalMotion;
        }
        MoveUtil.setSpeed(0.0);
        event.setFriction((float) MoveUtil.getBaseMoveSpeed() * this.hSpeed.getValue());
    }

    private void matrixStrafe() {
        if (MovementTicks.sinceVelocity() > 1) {
            mc.thePlayer.motionY += MATRIX_MOTION_Y_STEP;
        }
        if (mc.thePlayer.onGround) {
            mc.thePlayer.jump();
        }
        if (mc.thePlayer.fallDistance > MATRIX_FALL_TRIGGER && !this.teleported) {
            mc.thePlayer.motionY = MATRIX_JUMP_MOTION;
            MoveUtil.strafe(MATRIX_FALL_STRAFE);
        }
        if (this.teleported && MovementTicks.air() == MATRIX_BOOST_TICK) {
            mc.thePlayer.motionY = MATRIX_JUMP_MOTION;
            MoveUtil.strafe(MATRIX_BOOST_STRAFE);
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isMatrix() || mc.thePlayer.movementInput == null) {
            return;
        }
        if (MovementTicks.air() > MATRIX_STOP_START && MovementTicks.air() < MATRIX_STOP_END) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX
                && event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook packet = (S08PacketPlayerPosLook) event.getPacket();
            event.setCancelled(true);
            PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                    packet.getX(), packet.getY(), packet.getZ(),
                    mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
            mc.thePlayer.setPosition(packet.getX(), packet.getY(), packet.getZ());
            mc.thePlayer.jump();
            if (this.teleported) {
                this.setEnabled(false);
                return;
            }
            this.teleported = true;
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX_DAMAGE
                && event.getType() == EventType.SEND
                && event.getPacket() instanceof C03PacketPlayer
                && this.spoofGround != SPOOF_NONE) {
            AccessorC03PacketPlayer.setOnGround(
                    (C03PacketPlayer) event.getPacket(), this.spoofGround == SPOOF_GROUND);
            this.spoofGround = SPOOF_NONE;
        }
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isMatrixDamage()) {
            return;
        }
        double fallen = mc.thePlayer.lastTickPosY - mc.thePlayer.posY;
        if (fallen > 0.0) {
            this.trackedFall = (float) ((double) this.trackedFall + fallen);
        }
        if (mc.thePlayer.onGround) {
            this.trackedFall = 0.0F;
        }
        this.spoofGround = SPOOF_NONE;
        if (this.selfDamage.getValue() && mc.thePlayer.hurtTime <= 0
                && this.selfDamageJumps < DAMAGE_JUMP_LIMIT) {
            this.spoofGround = SPOOF_AIR;
        }
        if (this.newSelfDamage.getValue() && this.trackedFall > DAMAGE_FALL_TRIGGER) {
            this.spoofGround = SPOOF_GROUND;
            mc.thePlayer.onGround = true;
            mc.thePlayer.motionY = 0.0;
            this.trackedFall = 0.0F;
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX_DAMAGE) {
            this.damageFlightUpdate();
            return;
        }
        if (this.mode.getValue() != 0) {
            return;
        }
        this.verticalMotion = 0.0;
        if (mc.currentScreen == null) {
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                this.verticalMotion = this.verticalMotion + this.vSpeed.getValue().doubleValue() * 0.42F;
            }
            if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode())) {
                this.verticalMotion = this.verticalMotion - this.vSpeed.getValue().doubleValue() * 0.42F;
            }
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
    }

    private void damageFlightUpdate() {
        if (mc.thePlayer.hurtTime > 0 && !this.damaged && MovementTicks.air() >= DAMAGE_AIR_TICKS) {
            this.speedTicksLeft = 20 * this.timerSpeed.getValue().intValue();
            this.damaged = true;
        }
        if (this.selfDamage.getValue() && mc.thePlayer.hurtTime <= 0
                && this.selfDamageJumps < DAMAGE_JUMP_LIMIT && mc.thePlayer.onGround) {
            mc.thePlayer.jump();
            this.selfDamageJumps++;
        }
        if (!this.detectDamage.getValue()
                || this.flightTicks <= this.flyTicks.getValue() && this.damaged) {
            double speed = this.speedTicksLeft > 0
                    ? this.damageSpeed.getValue().doubleValue()
                    : DAMAGE_DEFAULT_SPEED;
            if (!this.noSpeed.getValue()) {
                MoveUtil.strafe(speed);
            }
            this.setTimer(this.timerSpeed.getValue());
            if (this.motionYMode.getValue() == MOTION_Y_NONE) {
                mc.thePlayer.motionY *= DAMAGE_MOTION_Y_FACTOR;
            } else if (this.motionYMode.getValue() == MOTION_Y_MULTIPLY) {
                mc.thePlayer.motionY = mc.thePlayer.motionY * this.motionY.getValue().doubleValue();
            }
            if (this.speedTicksLeft > 0) {
                this.speedTicksLeft--;
            }
            this.flightTicks++;
        }
        if (this.detectDamage.getValue() && this.damaged) {
            if (!this.autoDisable.getValue()) {
                if (this.flightTicks >= this.flyTicks.getValue()) {
                    this.setEnabled(false);
                }
            } else if (this.speedTicksLeft <= 0) {
                this.setEnabled(false);
            }
        }
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

    @Override
    public void onEnabled() {
        this.teleported = false;
        this.flightTicks = 0;
        this.selfDamageJumps = 0;
        this.speedTicksLeft = 0;
        this.damaged = false;
        this.trackedFall = 0.0F;
        this.spoofGround = SPOOF_NONE;
    }

    @Override
    public void onDisabled() {
        this.teleported = false;
        this.flightTicks = 0;
        this.selfDamageJumps = 0;
        this.speedTicksLeft = 0;
        this.damaged = false;
        this.trackedFall = 0.0F;
        this.spoofGround = SPOOF_NONE;
        this.resetTimer();
        if (mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == MODE_MATRIX_DAMAGE) {
            if (this.resetPacket.getValue()) {
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                        mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                        mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C04PacketPlayerPosition(
                        mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, false));
                PacketUtil.sendPacketNoEvent(new C03PacketPlayer.C06PacketPlayerPosLook(
                        mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                        mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, false));
            }
            return;
        }
        if (this.mode.getValue() != 0) {
            return;
        }
        mc.thePlayer.motionY = 0.0;
        MoveUtil.setSpeed(0.0);
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 0) {
            return new String[0];
        }
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
