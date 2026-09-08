package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.*;
import myau.access.AccessorEntity;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.ChatUtil;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class Velocity extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_JUMP = 1;
    private static final int MODE_WATCHDOG = 5;
    private static final long MW_GROUND_TIMEOUT_MS = 1000L;
    private static final double FALL_IGNORE_DISTANCE = 3.0;
    private static final float JUMP_FOV = 330.0F;

    private int chanceCounter = 0;
    private int delayChanceCounter = 0;
    private boolean pendingExplosion = false;
    private boolean allowNext = true;
    private boolean jumpFlag = false;
    private boolean reverseFlag = false;
    private boolean delayActive = false;

    private boolean shouldJump = false;
    private boolean setJump = false;
    private boolean ignoreNext = false;
    private int lastHurtTime = 0;
    private double lastFallDistance = 0.0;
    private int jumpCooldown = 0;

    private int mwCount = 0;
    private int mwAttackCount = 0;
    private int mwStuckTicks = 0;
    private boolean mwStrict = false;
    private boolean mwDelaying = false;
    private boolean mwServerSprint = false;
    private int mwLastAttackTick = -1;
    private long mwWorldChangeTime = 0L;
    private long mwDelayStart = 0L;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"VANILLA", "JUMP", "DELAY", "REVERSE", "LEGIT_TEST",
            "WATCHDOG"});
    public final IntProperty delayTicks = new IntProperty("delay-ticks", 3, 1, 20, () -> this.mode.getValue() == 2);
    public final IntProperty mwDelayTicks = new IntProperty("mw-delay-ticks", 1, 0, 100,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final IntProperty mwWorldTimeout = new IntProperty("mw-world-timeout", 5000, 0, 10000,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwCancelExplosion = new BooleanProperty("mw-cancel-explosion", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwIgnoreExplosion = new BooleanProperty("mw-ignore-explosion", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwJumpReset = new BooleanProperty("mw-jump-reset", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwAttackReduce = new BooleanProperty("mw-attack-reduce", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwDisableOnFlag = new BooleanProperty("mw-disable-on-flag", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwAirOnly = new BooleanProperty("mw-air-only", true,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty mwUntilGround = new BooleanProperty("mw-until-ground", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final PercentProperty delayChance = new PercentProperty("delay-chance", 100, () -> this.mode.getValue() == 2);
    public final PercentProperty chance =
            new PercentProperty("chance", 100, () -> this.mode.getValue() != MODE_JUMP);
    public final PercentProperty horizontal =
            new PercentProperty("horizontal", 0, () -> this.mode.getValue() != MODE_JUMP);
    public final PercentProperty vertical =
            new PercentProperty("vertical", 100, () -> this.mode.getValue() != MODE_JUMP);
    public final PercentProperty explosionHorizontal =
            new PercentProperty("explosions-horizontal", 100, () -> this.mode.getValue() != MODE_JUMP);
    public final PercentProperty explosionVertical =
            new PercentProperty("explosions-vertical", 100, () -> this.mode.getValue() != MODE_JUMP);
    public final PercentProperty jumpChance =
            new PercentProperty("jump-chance", 80, this::jumpResetActive);
    public final BooleanProperty requireMouseDown = new BooleanProperty("require-mouse-down", false,
            this::jumpResetActive);
    public final BooleanProperty requireMovingForward =
            new BooleanProperty("require-moving-forward", true, this::jumpResetActive);
    public final BooleanProperty requireAim = new BooleanProperty("require-aim", true,
            this::jumpResetActive);
    public final BooleanProperty fakeCheck = new BooleanProperty("fake-check", true);
    public final BooleanProperty debugLog = new BooleanProperty("debug-log", false);

    private boolean isInLiquidOrWeb() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || AccessorEntity.getIsInWeb(mc.thePlayer);
    }

    private boolean canDelay() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return mc.thePlayer.onGround && (!killAura.isEnabled() || !killAura.shouldAutoBlock());
    }

    public Velocity() {
        super("Velocity", false);
    }


    public boolean isAttackReduceActive() {
        return this.isEnabled() && this.mode.getValue() == MODE_WATCHDOG
                && this.mwAttackReduce.getValue() && this.mwAttackCount > 0;
    }

    private boolean mwInactive() {
        return !this.isEnabled() || this.mode.getValue() != MODE_WATCHDOG
                || mc.thePlayer == null || mc.theWorld == null
                || System.currentTimeMillis() - this.mwWorldChangeTime
                < (long) this.mwWorldTimeout.getValue();
    }

    private boolean jumpResetActive() {
        return this.mode.getValue() == MODE_JUMP
                || this.mode.getValue() == MODE_WATCHDOG && this.mwJumpReset.getValue();
    }

    private boolean mwOwnsDelay() {
        return Myau.delayManager.getDelayModule() == DelayModules.VELOCITY;
    }

    private void mwRelease() {
        if (this.mwOwnsDelay()) {
            Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
        }
        this.mwDelaying = false;
    }

    private void mwReset() {
        this.mwRelease();
        this.mwCount = 0;
        this.mwAttackCount = 0;
        this.mwStuckTicks = 0;
        this.mwStrict = false;
        this.mwDelaying = false;
    }

    @EventTarget
    public void onLoadWorldMw(LoadWorldEvent event) {
        this.mwWorldChangeTime = System.currentTimeMillis();
        this.mwRelease();
        this.mwCount = 0;
        this.mwAttackCount = 0;
        this.mwStuckTicks = 0;
        this.mwStrict = false;
        this.mwDelaying = false;
    }

    @EventTarget
    public void onMoveInputMw(MoveInputEvent event) {
        if (this.mwInactive() || mc.thePlayer.movementInput == null) {
            return;
        }
        if (this.mwStrict) {
            mc.thePlayer.movementInput.moveForward = 1.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
        }
    }


    @EventTarget
    public void onUpdateMw(UpdateEvent event) {
        if (this.mode.getValue() != MODE_WATCHDOG) {
            return;
        }
        if (event.getType() == EventType.POST) {
            if (this.mwInactive()) {
                if (this.mwOwnsDelay()) {
                    this.mwRelease();
                }
                return;
            }
            this.mwAdvanceDelay();
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (this.mwInactive()) {
            if (this.mwOwnsDelay()) {
                this.mwRelease();
            }
            return;
        }
        if (this.mwStuckTicks > 0) {
            this.mwStuckTicks--;
        }
        if (this.mwAttackCount > 0) {
            if (this.mwAttackReduce.getValue()
                    && this.mwLastAttackTick != mc.thePlayer.ticksExisted
                    && this.mwServerSprint) {
                this.mwForceAttack();
            }
            this.mwAttackCount--;
        } else if (this.mwStrict) {
            this.mwStrict = false;
        }
    }

    private void mwAdvanceDelay() {
        if (!this.mwDelaying) {
            if (this.mwOwnsDelay()) {
                this.mwRelease();
            }
            return;
        }
        this.mwCount++;
        if (this.mwUntilGround.getValue()) {
            boolean settled = mc.thePlayer.onGround || mc.thePlayer.isOnLadder()
                    || this.isInLiquidOrWeb()
                    || System.currentTimeMillis() - this.mwDelayStart >= MW_GROUND_TIMEOUT_MS;
            if (settled) {
                this.mwCount = 0;
                this.mwRelease();
            }
            return;
        }
        if (this.mwCount >= this.mwDelayTicks.getValue()) {
            this.mwCount = 0;
            this.mwRelease();
        }
    }

    private void mwForceAttack() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null || !killAura.isEnabled()) {
            return;
        }
        net.minecraft.entity.EntityLivingBase target = killAura.getTarget();
        if (target == null || target.isDead || target == mc.thePlayer) {
            return;
        }
        net.minecraft.util.AxisAlignedBB box = target.getEntityBoundingBox();
        double range = killAura.attackRange.getValue();
        if (RotationUtil.distanceToBox(box) > range) {
            return;
        }
        if (RotationUtil.rayTrace(box, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                range) == null) {
            return;
        }
        this.mwLastAttackTick = mc.thePlayer.ticksExisted;
        mc.thePlayer.swingItem();
        myau.access.AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
        myau.util.PacketUtil.sendPacket(new net.minecraft.network.play.client.C02PacketUseEntity(
                target, net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK));
        if (mc.playerController.getCurrentGameType()
                != net.minecraft.world.WorldSettings.GameType.SPECTATOR) {
            myau.util.PlayerUtil.attackEntity(target);
        }
    }


    private boolean mwBeginDelay(PacketEvent event, net.minecraft.network.Packet<?> packet) {
        if (this.mwDelayTicks.getValue() == 0) {
            return false;
        }
        if (this.mwStuckTicks > 0) {
            return false;
        }
        if (this.mwAirOnly.getValue() && mc.thePlayer.onGround) {
            return false;
        }
        if (Myau.delayManager.getDelayModule() != DelayModules.NONE && !this.mwOwnsDelay()) {
            return false;
        }
        this.mwCount = 0;
        this.mwDelayStart = System.currentTimeMillis();
        this.mwDelaying = true;
        Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
        Myau.delayManager.delayedPacket.offer(
                (net.minecraft.network.Packet<net.minecraft.network.play.INetHandlerPlayClient>) packet);
        event.setCancelled(true);
        return true;
    }

    private boolean mwHandlePacket(PacketEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_WATCHDOG
                || mc.thePlayer == null) {
            return false;
        }
        net.minecraft.network.Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (packet instanceof net.minecraft.network.play.client.C02PacketUseEntity
                    && ((net.minecraft.network.play.client.C02PacketUseEntity) packet).getAction()
                    == net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK) {
                this.mwLastAttackTick = mc.thePlayer.ticksExisted;
            }
            if (packet instanceof net.minecraft.network.play.client.C0BPacketEntityAction) {
                net.minecraft.network.play.client.C0BPacketEntityAction action =
                        (net.minecraft.network.play.client.C0BPacketEntityAction) packet;
                if (action.getAction()
                        == net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SPRINTING) {
                    this.mwServerSprint = true;
                } else if (action.getAction()
                        == net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SPRINTING) {
                    this.mwServerSprint = false;
                }
            }
            return false;
        }
        if (event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return false;
        }
        if (packet instanceof net.minecraft.network.play.server.S01PacketJoinGame
                || packet instanceof net.minecraft.network.play.server.S07PacketRespawn) {
            this.mwRelease();
            this.mwCount = 0;
            this.mwStuckTicks = 0;
            return false;
        }
        if (packet instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook
                && this.mwDisableOnFlag.getValue()) {
            this.mwWorldChangeTime = System.currentTimeMillis();
            this.mwReset();
            return false;
        }
        if (this.mwInactive()) {
            return false;
        }
        if (this.mwDelaying) {
            return false;
        }
        if (packet instanceof S27PacketExplosion) {
            if (this.mwCancelExplosion.getValue()) {
                event.setCancelled(true);
                return true;
            }
            if (this.mwIgnoreExplosion.getValue()) {
                return false;
            }
            return this.mwBeginDelay(event, packet);
        }
        if (packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
            if (this.mwAttackReduce.getValue()) {
                this.mwStrict = true;
                this.mwAttackCount = 2;
            }
            return this.mwBeginDelay(event, packet);
        }
        return false;
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            this.pendingExplosion = false;
            this.allowNext = true;
        } else if (this.mode.getValue() == MODE_JUMP) {
            this.pendingExplosion = false;
            this.allowNext = true;
        } else if (!this.allowNext || !(Boolean) this.fakeCheck.getValue()) {
            this.allowNext = true;
            if (this.pendingExplosion) {
                this.pendingExplosion = false;
                if (this.explosionHorizontal.getValue() > 0) {
                    event.setX(event.getX() * (double) this.explosionHorizontal.getValue() / 100.0);
                    event.setZ(event.getZ() * (double) this.explosionHorizontal.getValue() / 100.0);
                } else {
                    event.setX(mc.thePlayer.motionX);
                    event.setZ(mc.thePlayer.motionZ);
                }
                if (this.explosionVertical.getValue() > 0) {
                    event.setY(event.getY() * (double) this.explosionVertical.getValue() / 100.0);
                } else {
                    event.setY(mc.thePlayer.motionY);
                }
            } else {
                this.chanceCounter = this.chanceCounter % 100 + this.chance.getValue();
                if (this.chanceCounter >= 100) {
                    this.jumpFlag = this.mode.getValue() == 2 && event.getY() > 0.0;
                    this.delayActive = this.mode.getValue() == 3;
                    if (this.horizontal.getValue() > 0) {
                        event.setX(event.getX() * (double) this.horizontal.getValue() / 100.0);
                        event.setZ(event.getZ() * (double) this.horizontal.getValue() / 100.0);
                    } else {
                        event.setX(mc.thePlayer.motionX);
                        event.setZ(mc.thePlayer.motionZ);
                    }
                    if (this.vertical.getValue() > 0) {
                        event.setY(event.getY() * (double) this.vertical.getValue() / 100.0);
                    } else {
                        event.setY(mc.thePlayer.motionY);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            if (this.reverseFlag
                    && (
                    this.canDelay()
                            || this.isInLiquidOrWeb()
                            || Myau.delayManager.getDelay() >= (long) this.delayTicks.getValue()
            )) {
                Myau.delayManager.setDelayState(false, DelayModules.VELOCITY);
                this.reverseFlag = false;
            }
            if (this.delayActive) {
                MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                this.delayActive = false;
            }

            if (this.mode.getValue() == 4) {
                int hurtTime = mc.thePlayer.hurtTime;

                if (hurtTime >= 8) {
                    if (jumpCooldown <= 0) {
                        shouldJump = true;
                        jumpCooldown = 2;
                    }
                } else if (hurtTime <= 1) {
                    shouldJump = false;
                    jumpCooldown = 0;
                }

                if (shouldJump && mc.thePlayer.onGround && jumpCooldown <= 0) {
                    mc.thePlayer.jump();
                    shouldJump = false;
                }

                if (jumpCooldown > 0) {
                    jumpCooldown--;
                }
            }
        }
    }

    @EventTarget
    public void onJumpReset(UpdateEvent event) {
        if (!this.isEnabled() || !this.jumpResetActive() || mc.thePlayer == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            if (this.setJump && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                this.setJump = false;
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
            }
            return;
        }
        int hurtTime = mc.thePlayer.hurtTime;
        boolean onGround = mc.thePlayer.onGround;
        if (onGround && this.lastFallDistance > FALL_IGNORE_DISTANCE
                && !mc.thePlayer.capabilities.allowFlying) {
            this.ignoreNext = true;
        }
        if (hurtTime > this.lastHurtTime) {
            boolean mouseDown = !this.requireMouseDown.getValue()
                    || mc.gameSettings.keyBindAttack.isKeyDown();
            boolean aimingAt = !this.requireAim.getValue() || isAimingAtPlayer();
            boolean forward = !this.requireMovingForward.getValue()
                    || mc.gameSettings.keyBindForward.isKeyDown();
            boolean rolled = this.jumpChance.getValue() >= 100
                    || Math.random() * 100.0 < this.jumpChance.getValue();
            if (!this.ignoreNext && !mc.thePlayer.isBurning() && onGround && aimingAt && forward
                    && mouseDown && rolled && !hasBadEffect() && knockbackInFov()) {
                this.setJump = true;
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
            }
            this.ignoreNext = false;
        }
        this.lastHurtTime = hurtTime;
        this.lastFallDistance = mc.thePlayer.fallDistance;
    }

    private static boolean isAimingAtPlayer() {
        MovingObjectPosition hit = mc.objectMouseOver;
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && hit.entityHit instanceof EntityPlayer;
    }

    private static boolean hasBadEffect() {
        return mc.thePlayer.isPotionActive(Potion.jump)
                || mc.thePlayer.isPotionActive(Potion.poison)
                || mc.thePlayer.isPotionActive(Potion.wither);
    }

    private static boolean knockbackInFov() {
        float heading = MoveUtil.getMoveYaw();
        float pushYaw = (float) (Math.atan2(mc.thePlayer.motionX, mc.thePlayer.motionZ)
                * 57.29578 * -1.0);
        float half = JUMP_FOV * 0.5F;
        double delta = MathHelper.wrapAngleTo180_double((heading - pushYaw) % 360.0F);
        return delta > 0.0 ? delta < half : delta > -half;
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.jumpFlag) {
            this.jumpFlag = false;
            if (mc.thePlayer.onGround && mc.thePlayer.isSprinting() && !mc.thePlayer.isPotionActive(Potion.jump) && !this.isInLiquidOrWeb()) {
                mc.thePlayer.movementInput.jump = true;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.mwHandlePacket(event)) {
            return;
        }
        if (this.isEnabled() && event.getType() == EventType.RECEIVE && !event.isCancelled()) {
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                if (packet.getEntityID() == mc.thePlayer.getEntityId()) {
                    LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
                    if (this.mode.getValue() == 2
                            && !this.reverseFlag
                            && !this.canDelay()
                            && !this.isInLiquidOrWeb()
                            && !this.pendingExplosion
                            && (!this.allowNext || !(Boolean) this.fakeCheck.getValue())
                            && (!longJump.isEnabled() || !longJump.canStartJump())) {
                        this.delayChanceCounter = this.delayChanceCounter % 100 + this.delayChance.getValue();
                        if (this.delayChanceCounter >= 100) {
                            Myau.delayManager.setDelayState(true, DelayModules.VELOCITY);
                            Myau.delayManager.delayedPacket.offer(packet);
                            event.setCancelled(true);
                            this.reverseFlag = true;
                            return;
                        }
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sVelocity (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        (double) packet.getMotionX() / 8000.0,
                                        (double) packet.getMotionY() / 8000.0,
                                        (double) packet.getMotionZ() / 8000.0
                                )
                        );
                    }
                }
            } else if (!(event.getPacket() instanceof S27PacketExplosion)) {
                if (event.getPacket() instanceof S19PacketEntityStatus) {
                    S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
                    Entity entity = packet.getEntity(mc.theWorld);
                    if (entity != null && entity.equals(mc.thePlayer) && packet.getOpCode() == 2) {
                        this.allowNext = false;
                    }
                }
            } else {
                S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
                if (packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F) {
                    this.pendingExplosion = true;
                    if (this.explosionHorizontal.getValue() == 0 || this.explosionVertical.getValue() == 0) {
                        event.setCancelled(true);
                    }
                    if (this.debugLog.getValue()) {
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sExplosion (&otick: %d, x: %.2f, y: %.2f, z: %.2f&r)&r",
                                        Myau.clientName,
                                        mc.thePlayer.ticksExisted,
                                        mc.thePlayer.motionX + (double) packet.func_149149_c(),
                                        mc.thePlayer.motionY + (double) packet.func_149144_d(),
                                        mc.thePlayer.motionZ + (double) packet.func_149147_e()
                                )
                        );
                    }
                }
            }
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.onDisabled();
    }

    @Override
    public void onDisabled() {
        this.pendingExplosion = false;
        this.allowNext = true;
        this.shouldJump = false;
        this.jumpCooldown = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
