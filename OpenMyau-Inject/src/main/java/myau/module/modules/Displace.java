package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.PreAttackEvent;
import myau.events.PrePlayerInteractEvent;
import myau.events.Render3DEvent;
import myau.events.RightClickMouseEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.timeout.ModuleBackedTimeout;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.access.AccessorEntityPlayerSP;
import myau.access.AccessorRenderManager;
import myau.util.CombatTargeting;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;
import net.minecraft.util.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Displace extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int DISPLACE_WINDOW_TICKS = 10;

    private static final int VOID_SCAN_DIRECTIONS = 48;
    private static final double VOID_SCAN_ANGLE_STEP = 7.5;
    private static final double VOID_SCAN_STEP = 0.5;
    private static final double VOID_COLLISION_STEP = 0.25;
    private static final double VOID_COLLISION_INSET = 0.03;
    private static final double VOID_REFINEMENT_STEP = 1.5;
    private static final double VOID_SCORE_EPSILON = 1.0E-4;
    private static final int DISPLACEMENT_LOCK_IDLE_TICKS = 20;

    private static final double TARGET_RANGE_SQ = 9.0;
    private static final double OVERRIDE_RANGE_SQ = 25.0;
    private static final float ARROW_FADE_MS = 250.0F;
    private static final double ARROW_NUDGE_MS = 320.0;
    private static final double ARROW_NUDGE_FADE_START = 0.58;

    private static final int MODE_OFFSET = 0;
    private static final int MODE_VOID = 1;
    private static final int DIRECTION_LEFT = 0;

    public final ModeProperty mode =
            new ModeProperty("mode", MODE_OFFSET, new String[]{"Offset", "Void"});
    public final FloatProperty yawOffset =
            new FloatProperty("yaw-offset", 90.0F, 0.0F, 180.0F, 1.0F);
    public final FloatProperty scanRadius =
            new FloatProperty("scan-radius", 6.0F, 1.0F, 12.0F, 0.5F);
    public final FloatProperty delay = new FloatProperty("delay", 0.0F, 0.0F, 500.0F, 50.0F);
    public final ModeProperty direction =
            new ModeProperty("direction", DIRECTION_LEFT, new String[]{"Left", "Right"});
    public final BooleanProperty findVoid = new BooleanProperty("find-void", false);
    public final BooleanProperty silent = new BooleanProperty("silent", false);
    public final BooleanProperty blink = new BooleanProperty("blink", false);
    public final FloatProperty maxBlinkHold =
            new FloatProperty("max-blink-hold", 350.0F, 50.0F, 1000.0F, 50.0F,
                    () -> this.blink.getValue());
    public final BooleanProperty ignoreOneBlockWall = new BooleanProperty("ignore-one-block-wall", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty onlyKnockbackItems = new BooleanProperty("only-knockback-items", false);
    public final BooleanProperty overrideAttack = new BooleanProperty("override-attack", false);
    public final BooleanProperty renderArrow = new BooleanProperty("render-arrow", true);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapon-only", false);

    private boolean displaceThisTick = false;
    private boolean active = false;
    private boolean hasKB = false;
    private boolean displaceLeft = false;
    private boolean wasDisplacingLastTick = false;
    private boolean releaseBlinkNextGameTick = false;
    private boolean compensateNextTick = false;

    private OverrideAttackState overrideAttackState = OverrideAttackState.IDLE;
    private EntityPlayer overrideTarget = null;
    private float overrideTargetYaw = 0.0F;
    private float overrideTargetPitch = 0.0F;
    private float overrideFlickYaw = 0.0F;
    private float overrideFlickOffset = 0.0F;
    private boolean overrideAbsoluteFlickYaw = false;
    private boolean overrideAwayPacketSent = false;
    private boolean overrideAttackInProgress = false;
    private boolean overrideAttackPacketSent = false;
    private boolean suppressUseForOverrideAttackTick = false;
    private int overrideStateTicks = 0;
    private long lastOverrideFlickMs = -1L;

    private EntityPlayer arrowPlayer = null;
    private float arrowYaw = 0.0F;
    private float arrowFadeStartAlpha = 0.0F;
    private float arrowFadeEndAlpha = 0.0F;
    private long arrowFadeStartMs = 0L;
    private boolean arrowVisible = false;
    private long arrowNudgeStartMs = -1L;
    private int arrowNudgePlayerId = -1;
    private int tickCounter;

    private final Map<Integer, Integer> targetWindowStartTicks = new HashMap<Integer, Integer>();
    private final Map<Integer, DisplacementLock> targetDisplacementLocks =
            new HashMap<Integer, DisplacementLock>();

    private LagRequest outboundBlink;
    private long outboundBlinkStartedAtMs;

    public Displace() {
        super("Displace", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    private void reset() {
        this.resetOverrideAttackState();
        this.lastOverrideFlickMs = -1L;
        this.displaceThisTick = false;
        this.active = false;
        this.hasKB = false;
        this.wasDisplacingLastTick = false;
        this.releaseBlinkNextGameTick = false;
        this.tickCounter = 0;
        this.targetWindowStartTicks.clear();
        this.targetDisplacementLocks.clear();
        this.releaseBlink();
    }

    private static int msToTicks(double ms) {
        return ms <= 0.0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    private boolean isVoidMode() {
        return this.mode.getValue() == MODE_VOID;
    }

    public boolean isActive() {
        return this.isEnabled() && this.active;
    }

    public boolean isDisplacingThisTick() {
        return this.displaceThisTick;
    }

    public boolean hasKnockback() {
        return this.hasKB;
    }


    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.resetOverrideAttackState();
            this.active = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            this.targetDisplacementLocks.clear();
            this.clearArrow();
            return;
        }
        this.tickCounter++;
        int currentTick = this.tickCounter;
        this.pruneTargetDelayStates();

        if (this.isOverrideAttackEnabled()) {
            if (this.overrideAttackState == OverrideAttackState.IDLE) {
                this.stand();
            } else {
                this.updateOverrideRotation(event);
            }
            return;
        }
        if (this.overrideAttackState != OverrideAttackState.IDLE) {
            this.resetOverrideAttackState();
        }

        boolean passesItemCondition =
                (!this.onlyKnockbackItems.getValue()
                        || EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0)
                        && (!this.weaponOnly.getValue() || ItemUtil.isHoldingSword());
        if (!passesItemCondition) {
            this.stand();
            return;
        }

        EntityPlayer target = this.findTarget();
        this.active = target != null;
        if (!this.active) {
            this.stand();
            return;
        }

        float playerYaw = event.isRotated() ? event.getNewYaw() : event.getYaw();
        float displaceYaw;
        if (this.isVoidMode()) {
            Float bestVoidYaw = this.findBestVoidYaw(target, playerYaw);
            if (bestVoidYaw == null) {
                this.stand();
                return;
            }
            displaceYaw = bestVoidYaw;
            this.updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            if (!this.findVoid.getValue() || !this.tryFindVoidDirection(target)) {
                this.displaceLeft = this.direction.getValue() == DIRECTION_LEFT;
            }
            displaceYaw = this.offsetYaw(playerYaw);
        }

        this.hasKB = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        this.displaceThisTick = !this.displaceThisTick;
        if (this.displaceThisTick && !this.shouldDisplaceInCurrentWindow(target, currentTick)) {
            this.stand();
            return;
        }

        if (this.displaceThisTick) {
            displaceYaw = this.lockDisplacementYaw(target, displaceYaw);
            this.updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            displaceYaw = this.getLockedDisplacementYaw(target, displaceYaw);
        }

        this.showArrow(target, displaceYaw);
        if (!this.displaceThisTick && this.wasDisplacingLastTick) {
            int key = mc.gameSettings.keyBindAttack.getKeyCode();
            if (key != 0) {
                KeyBindUtil.pressKeyOnce(key);
            }
        }
        this.wasDisplacingLastTick = this.displaceThisTick;

        if (this.displaceThisTick) {
            event.setRotation(displaceYaw, event.getNewPitch(), ROTATION_PRIORITY);
            event.setPervRotation(displaceYaw, ROTATION_PRIORITY);
            if (!this.silent.getValue()) {
                Myau.rotationManager.setRotation(displaceYaw, event.getNewPitch(),
                        ROTATION_PRIORITY, true);
            }
        }
    }

    private static final int ROTATION_PRIORITY = 2;

    private float offsetYaw(float playerYaw) {
        float offset = this.yawOffset.getValue();
        return this.displaceLeft ? playerYaw - offset : playerYaw + offset;
    }

    private void stand() {
        this.active = false;
        this.displaceThisTick = false;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.hideArrow();
    }


    private boolean isOverrideAttackEnabled() {
        return this.overrideAttack.getValue();
    }

    private boolean passesOverrideItemConditions() {
        if (this.onlyKnockbackItems.getValue()
                && EnchantmentHelper.getKnockbackModifier(mc.thePlayer) <= 0) {
            return false;
        }
        return !this.weaponOnly.getValue() || ItemUtil.isHoldingSword();
    }

    private boolean isOverrideTargetValid(EntityPlayer target) {
        return target != null && target.worldObj == mc.theWorld
                && CombatTargeting.asValidPlayer(target, OVERRIDE_RANGE_SQ,
                this.ignoreTeammates.getValue()) != null;
    }

    private boolean canStartOverrideAttack(EntityPlayer target) {
        if (this.overrideAttackState != OverrideAttackState.IDLE || mc.thePlayer == null
                || mc.theWorld == null || mc.currentScreen != null
                || !this.isOverrideTargetValid(target) || !this.passesOverrideItemConditions()) {
            return false;
        }
        long configuredDelay = (long) this.delay.getValue().floatValue();
        return this.lastOverrideFlickMs < 0L || configuredDelay <= 0L
                || System.currentTimeMillis() - this.lastOverrideFlickMs >= configuredDelay;
    }

    private static float[] getOverrideTargetRotations(EntityPlayer target, float baseYaw,
                                                      float basePitch) {
        if (target == null || mc.thePlayer == null) {
            return null;
        }
        return RotationUtil.getRotationsToBox(target.getEntityBoundingBox(), baseYaw, basePitch,
                180.0F, 0.0F);
    }

    private boolean startOverrideAttack(EntityPlayer target) {
        if (!this.canStartOverrideAttack(target)) {
            return false;
        }
        float baseYaw = AccessorEntityPlayerSP.getLastReportedYaw(mc.thePlayer);
        float basePitch = AccessorEntityPlayerSP.getLastReportedPitch(mc.thePlayer);
        float[] targetRotations = getOverrideTargetRotations(target, baseYaw, basePitch);
        if (targetRotations == null) {
            return false;
        }
        this.overrideTargetYaw = targetRotations[0];
        this.overrideTargetPitch = targetRotations[1];
        this.overrideAbsoluteFlickYaw = this.isVoidMode();
        if (this.overrideAbsoluteFlickYaw) {
            Float bestVoidYaw = this.findBestVoidYaw(target, this.overrideTargetYaw);
            if (bestVoidYaw == null) {
                return false;
            }
            this.overrideFlickYaw = bestVoidYaw;
            this.overrideFlickOffset = 0.0F;
            this.updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
        } else {
            if (!this.findVoid.getValue() || !this.tryFindVoidDirection(target)) {
                this.displaceLeft = this.direction.getValue() == DIRECTION_LEFT;
            }
            this.overrideFlickOffset =
                    this.displaceLeft ? -this.yawOffset.getValue() : this.yawOffset.getValue();
            this.overrideFlickYaw = this.overrideTargetYaw + this.overrideFlickOffset;
        }
        this.overrideFlickYaw = this.lockDisplacementYaw(target, this.overrideFlickYaw);
        this.updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
        this.overrideTarget = target;
        this.overrideAttackState = OverrideAttackState.FLICKING_AWAY;
        this.overrideStateTicks = 0;
        this.overrideAwayPacketSent = false;
        this.overrideAttackInProgress = false;
        this.overrideAttackPacketSent = false;
        this.suppressUseForOverrideAttackTick = false;
        this.lastOverrideFlickMs = System.currentTimeMillis();
        this.active = true;
        this.displaceThisTick = true;
        this.hasKB = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.showArrow(target, this.overrideFlickYaw);
        this.releaseUseForOverrideAttack();
        this.startOverrideBlink();
        return true;
    }

    private void releaseUseForOverrideAttack() {
        if (mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        if (mc.thePlayer.isUsingItem()) {
            mc.playerController.onStoppedUsingItem(mc.thePlayer);
        }
    }

    private void startOverrideBlink() {
        this.releaseBlink();
        this.releaseBlinkNextGameTick = false;
        if (this.blink.getValue()) {
            this.outboundBlink = new LagRequest(EnumLagDirection.ONLY_OUTBOUND,
                    new ModuleBackedTimeout(this));
            this.outboundBlinkStartedAtMs = System.currentTimeMillis();
            Myau.lagHandler.requestLag(this.outboundBlink);
        }
    }

    private void advanceOverrideAttackState() {
        if (this.overrideAttackState == OverrideAttackState.IDLE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null
                || !this.isOverrideTargetValid(this.overrideTarget)) {
            this.resetOverrideAttackState();
            return;
        }
        this.overrideStateTicks++;
        switch (this.overrideAttackState) {
            case FLICKING_AWAY:
                if (this.overrideAwayPacketSent || this.overrideStateTicks >= 5) {
                    this.overrideAttackState = OverrideAttackState.ATTACKING;
                    this.overrideStateTicks = 0;
                    this.displaceThisTick = false;
                }
                break;
            case ATTACKING:
            case RESTORING:
                if (this.overrideStateTicks >= 2) {
                    this.resetOverrideAttackState();
                }
                break;
            default:
                break;
        }
    }

    private void resetOverrideAttackState() {
        this.releaseBlink();
        this.overrideAttackState = OverrideAttackState.IDLE;
        this.overrideTarget = null;
        this.overrideTargetYaw = 0.0F;
        this.overrideTargetPitch = 0.0F;
        this.overrideFlickYaw = 0.0F;
        this.overrideFlickOffset = 0.0F;
        this.overrideAbsoluteFlickYaw = false;
        this.overrideAwayPacketSent = false;
        this.overrideAttackInProgress = false;
        this.overrideAttackPacketSent = false;
        this.suppressUseForOverrideAttackTick = false;
        this.overrideStateTicks = 0;
        this.active = false;
        this.displaceThisTick = false;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.hideArrow();
    }

    private void updateOverrideRotation(UpdateEvent event) {
        if (!this.isOverrideTargetValid(this.overrideTarget)) {
            this.resetOverrideAttackState();
            return;
        }
        float baseYaw = event.isRotated() ? event.getNewYaw() : event.getYaw();
        float basePitch = event.isRotated() ? event.getNewPitch() : event.getPitch();
        float[] targetRotations =
                getOverrideTargetRotations(this.overrideTarget, baseYaw, basePitch);
        if (targetRotations == null) {
            this.resetOverrideAttackState();
            return;
        }
        this.overrideTargetYaw = targetRotations[0];
        this.overrideTargetPitch = targetRotations[1];
        if (!this.overrideAbsoluteFlickYaw) {
            this.overrideFlickYaw = this.getLockedDisplacementYaw(this.overrideTarget,
                    this.overrideTargetYaw + this.overrideFlickOffset);
        }
        this.updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
        float yaw;
        if (this.overrideAttackState == OverrideAttackState.FLICKING_AWAY) {
            yaw = this.overrideFlickYaw;
            this.displaceThisTick = true;
        } else {
            yaw = this.overrideTargetYaw;
            this.displaceThisTick = false;
        }
        event.setRotation(yaw, this.overrideTargetPitch, ROTATION_PRIORITY);
        event.setPervRotation(yaw, ROTATION_PRIORITY);
        if (!this.silent.getValue()) {
            Myau.rotationManager.setRotation(yaw, this.overrideTargetPitch, ROTATION_PRIORITY, true);
        }
        this.active = true;
        this.showArrow(this.overrideTarget, this.overrideFlickYaw);
    }


    private void showArrow(EntityPlayer player, float yaw) {
        long now = System.currentTimeMillis();
        boolean playerChanged = this.arrowPlayer != player;
        float currentAlpha = playerChanged ? 0.0F : this.getArrowAlpha(now);
        if (playerChanged) {
            this.clearArrowNudge();
        }
        this.arrowPlayer = player;
        this.arrowYaw = yaw;
        if (!this.arrowVisible || playerChanged) {
            this.arrowFadeStartAlpha = currentAlpha;
            this.arrowFadeEndAlpha = 1.0F;
            this.arrowFadeStartMs = now;
        }
        this.arrowVisible = true;
    }

    private void hideArrow() {
        if (this.arrowPlayer != null && this.arrowVisible) {
            long now = System.currentTimeMillis();
            this.arrowFadeStartAlpha = this.getArrowAlpha(now);
            this.arrowFadeEndAlpha = 0.0F;
            this.arrowFadeStartMs = now;
            this.arrowVisible = false;
        }
    }

    private float getArrowAlpha(long now) {
        float progress =
                Math.min(1.0F, Math.max(0.0F, (now - this.arrowFadeStartMs) / ARROW_FADE_MS));
        return this.arrowFadeStartAlpha
                + (this.arrowFadeEndAlpha - this.arrowFadeStartAlpha) * progress;
    }

    private void triggerArrowNudge(EntityPlayer player) {
        if (player == null || this.arrowPlayer != player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.arrowNudgePlayerId != player.getEntityId()
                || now - this.arrowNudgeStartMs >= ARROW_NUDGE_MS) {
            this.arrowNudgePlayerId = player.getEntityId();
            this.arrowNudgeStartMs = now;
        }
    }

    private double getArrowNudgeProgress(EntityPlayer player, long now) {
        if (player == null || this.arrowNudgePlayerId != player.getEntityId()
                || this.arrowNudgeStartMs < 0L) {
            return -1.0;
        }
        return Math.min(1.0, Math.max(0.0, (now - this.arrowNudgeStartMs) / ARROW_NUDGE_MS));
    }

    private static double getArrowNudgeOffset(double progress) {
        if (progress < 0.0) {
            return 0.0;
        }
        double endValue = Math.exp(-6.0);
        double movement = (1.0 - Math.exp(-6.0 * progress)) / (1.0 - endValue);
        return movement * 0.3;
    }

    private static float getArrowNudgeAlpha(double progress) {
        if (progress < 0.0 || progress <= ARROW_NUDGE_FADE_START) {
            return 1.0F;
        }
        double fadeProgress =
                (progress - ARROW_NUDGE_FADE_START) / (1.0 - ARROW_NUDGE_FADE_START);
        double endValue = Math.exp(-5.0);
        return (float) ((Math.exp(-5.0 * fadeProgress) - endValue) / (1.0 - endValue));
    }

    private void clearArrowNudge() {
        this.arrowNudgeStartMs = -1L;
        this.arrowNudgePlayerId = -1;
    }

    private void clearArrow() {
        this.arrowPlayer = null;
        this.arrowFadeStartAlpha = 0.0F;
        this.arrowFadeEndAlpha = 0.0F;
        this.arrowFadeStartMs = 0L;
        this.arrowVisible = false;
        this.clearArrowNudge();
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            this.clearArrow();
            return;
        }
        if (!this.renderArrow.getValue() || this.arrowPlayer == null || this.arrowPlayer.isDead
                || this.arrowPlayer.deathTime != 0) {
            this.clearArrow();
            return;
        }
        long now = System.currentTimeMillis();
        double nudgeProgress = this.getArrowNudgeProgress(this.arrowPlayer, now);
        float alpha = this.getArrowAlpha(now) * getArrowNudgeAlpha(nudgeProgress);
        if (alpha <= 0.0F) {
            if (!this.arrowVisible) {
                this.clearArrow();
            }
            return;
        }
        this.drawArrow(this.arrowPlayer, this.arrowYaw, event.getPartialTicks(), alpha,
                getArrowNudgeOffset(nudgeProgress));
    }

    private void drawArrow(EntityPlayer player, float yaw, float partialTicks, float alpha,
                           double nudgeOffset) {
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks
                + player.height * 0.5;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double startDistance = player.width * 0.5 + 0.3 + nudgeOffset;
        double startX = x + forwardX * startDistance;
        double startZ = z + forwardZ * startDistance;
        double bodyX = startX + forwardX * 0.74;
        double bodyZ = startZ + forwardZ * 0.74;
        double headBaseX = startX + forwardX * 0.56;
        double headBaseZ = startZ + forwardZ * 0.56;
        double tipX = bodyX + forwardX * 0.52;
        double tipZ = bodyZ + forwardZ * 0.52;
        double viewerX = AccessorRenderManager.getRenderPosX(mc.getRenderManager());
        double viewerY = AccessorRenderManager.getRenderPosY(mc.getRenderManager());
        double viewerZ = AccessorRenderManager.getRenderPosZ(mc.getRenderManager());

        GL11.glPushMatrix();
        RenderUtil.enableRenderState();
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.82F * alpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        vertex(startX, y, startZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, -0.3, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, 0.3, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.95F * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        vertex(startX, y, startZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, -0.3, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, 0.3, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
        GL11.glDepthMask(true);
        RenderUtil.disableRenderState();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static void vertex(double x, double y, double z, double yOffset, double viewerX,
                               double viewerY, double viewerZ) {
        GL11.glVertex3d(x - viewerX, y + yOffset - viewerY, z - viewerZ);
    }

    @EventTarget(Priority.LOWEST)
    public void onAttackPacketForArrow(PacketEvent event) {
        if (!this.isEnabled() || !this.active || !this.renderArrow.getValue()
                || event.getType() != EventType.SEND || event.isCancelled()
                || mc.theWorld == null) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        if (packet.getEntityFromWorld(mc.theWorld) == this.arrowPlayer) {
            this.triggerArrowNudge(this.arrowPlayer);
        }
    }


    @EventTarget(Priority.HIGHEST)
    public void onRightClickMouse(RightClickMouseEvent event) {
        if (this.isEnabled() && this.isOverrideAttackEnabled() && this.shouldSuppressUse()) {
            event.setCancelled(true);
        }
    }

    private boolean shouldSuppressUse() {
        return this.overrideAttackState == OverrideAttackState.FLICKING_AWAY
                || this.overrideAttackState == OverrideAttackState.ATTACKING
                || this.suppressUseForOverrideAttackTick;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPreAttack(PreAttackEvent event) {
        if (!this.isEnabled() || !this.isOverrideAttackEnabled()) {
            return;
        }
        if (this.overrideAttackState != OverrideAttackState.IDLE) {
            event.setCancelled(true);
            return;
        }
        MovingObjectPosition mouseOver = event.objectMouseOver;
        EntityPlayer target = mouseOver != null
                && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                ? CombatTargeting.asValidPlayer(mouseOver.entityHit, OVERRIDE_RANGE_SQ,
                this.ignoreTeammates.getValue())
                : null;
        if (target != null && this.startOverrideAttack(target)) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !this.isOverrideAttackEnabled()
                || this.overrideAttackInProgress) {
            return;
        }
        if (this.overrideAttackState != OverrideAttackState.IDLE) {
            event.setCancelled(true);
            return;
        }
        EntityPlayer target = CombatTargeting.asValidPlayer(event.getTarget(), OVERRIDE_RANGE_SQ,
                this.ignoreTeammates.getValue());
        if (target != null && this.startOverrideAttack(target)) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!this.isEnabled() || !this.isOverrideAttackEnabled()
                || this.overrideAttackState != OverrideAttackState.ATTACKING
                || this.overrideAttackInProgress) {
            return;
        }
        if (!this.isOverrideTargetValid(this.overrideTarget)) {
            this.resetOverrideAttackState();
            return;
        }
        this.overrideAttackPacketSent = false;
        this.releaseUseForOverrideAttack();
        this.suppressUseForOverrideAttackTick = true;
        this.overrideAttackInProgress = true;
        try {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, this.overrideTarget);
        } finally {
            this.overrideAttackInProgress = false;
        }
        if (!this.overrideAttackPacketSent) {
            this.resetOverrideAttackState();
            return;
        }
        this.overrideAttackState = OverrideAttackState.RESTORING;
        this.overrideStateTicks = 0;
        this.displaceThisTick = false;
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.isOverrideAttackEnabled() || !this.active) {
            this.compensateNextTick = false;
            return;
        }
        if (this.compensateNextTick && !this.displaceThisTick) {
            this.compensateNextTick = false;
            mc.thePlayer.movementInput.moveStrafe = this.displaceLeft ? -1.0F : 1.0F;
            return;
        }
        if (this.displaceThisTick && !this.hasKB && anyMovementKey()) {
            mc.thePlayer.movementInput.moveForward = 1.0F;
            this.compensateNextTick = true;
        }
    }

    private static boolean anyMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private enum OverrideAttackState {
        IDLE,
        FLICKING_AWAY,
        ATTACKING,
        RESTORING
    }

    private EntityPlayer findTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        Entity auraTarget = killAura != null && killAura.isEnabled() ? killAura.getTarget() : null;
        if (auraTarget != null) {
            return CombatTargeting.asValidPlayer(auraTarget, TARGET_RANGE_SQ,
                    this.ignoreTeammates.getValue());
        }
        if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            return CombatTargeting.findClosestTarget(TARGET_RANGE_SQ,
                    this.ignoreTeammates.getValue());
        }
        return null;
    }

    private void updateDisplaceSide(float playerYaw, float displaceYaw) {
        this.displaceLeft = MathHelper.wrapAngleTo180_float(displaceYaw - playerYaw) < 0.0F;
    }

    private boolean shouldDisplaceInCurrentWindow(EntityPlayer target, int currentTick) {
        if (target == null) {
            return true;
        }
        int targetId = target.getEntityId();
        Integer windowStartTick = this.targetWindowStartTicks.get(targetId);
        if (windowStartTick == null || currentTick - windowStartTick >= DISPLACE_WINDOW_TICKS) {
            this.targetWindowStartTicks.put(targetId, currentTick);
            return true;
        }
        int delayTicks = msToTicks(this.delay.getValue());
        return delayTicks <= 0 || currentTick - windowStartTick >= delayTicks;
    }

    private float lockDisplacementYaw(EntityPlayer target, float yaw) {
        int targetId = target.getEntityId();
        DisplacementLock lock = this.targetDisplacementLocks.get(targetId);
        if (lock != null && (lock.airborne && target.onGround
                || this.tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS)) {
            this.targetDisplacementLocks.remove(targetId);
            lock = null;
        }
        if (lock == null) {
            lock = new DisplacementLock(yaw, this.tickCounter, !target.onGround);
            this.targetDisplacementLocks.put(targetId, lock);
        } else {
            lock.lastUseTick = this.tickCounter;
            if (!target.onGround) {
                lock.airborne = true;
            }
        }
        return lock.yaw;
    }

    private float getLockedDisplacementYaw(EntityPlayer target, float fallbackYaw) {
        DisplacementLock lock = this.targetDisplacementLocks.get(target.getEntityId());
        return lock == null ? fallbackYaw : lock.yaw;
    }

    private void pruneTargetDelayStates() {
        if (mc.theWorld == null) {
            this.targetWindowStartTicks.clear();
            this.targetDisplacementLocks.clear();
            return;
        }
        Iterator<Map.Entry<Integer, Integer>> windows =
                this.targetWindowStartTicks.entrySet().iterator();
        while (windows.hasNext()) {
            Map.Entry<Integer, Integer> entry = windows.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead
                    || ((EntityPlayer) entity).deathTime != 0) {
                windows.remove();
            }
        }
        Iterator<Map.Entry<Integer, DisplacementLock>> locks =
                this.targetDisplacementLocks.entrySet().iterator();
        while (locks.hasNext()) {
            Map.Entry<Integer, DisplacementLock> entry = locks.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead
                    || ((EntityPlayer) entity).deathTime != 0) {
                locks.remove();
                continue;
            }
            EntityPlayer player = (EntityPlayer) entity;
            DisplacementLock lock = entry.getValue();
            if (!player.onGround) {
                lock.airborne = true;
            } else if (lock.airborne
                    || this.tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS) {
                locks.remove();
            }
        }
    }


    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (!this.isOverrideAttackEnabled()) {
            if (this.overrideAttackState != OverrideAttackState.IDLE) {
                this.resetOverrideAttackState();
            }
        } else if (this.overrideAttackState != OverrideAttackState.IDLE) {
            if (this.overrideAttackState == OverrideAttackState.FLICKING_AWAY
                    && event.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer movementPacket = (C03PacketPlayer) event.getPacket();
                if (movementPacket.getRotating()
                        && Math.abs(MathHelper.wrapAngleTo180_float(
                        movementPacket.getYaw() - this.overrideFlickYaw)) <= 6.0F) {
                    this.overrideAwayPacketSent = true;
                }
            }
            if (this.overrideAttackInProgress
                    && event.getPacket() instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) event.getPacket()).getAction()
                    == C02PacketUseEntity.Action.ATTACK) {
                this.overrideAttackPacketSent = true;
                this.releaseBlink();
            }
            return;
        }
        if (!this.blink.getValue() || !this.active || !this.displaceThisTick
                || this.releaseBlinkNextGameTick) {
            return;
        }
        if (!(event.getPacket() instanceof C03PacketPlayer) || this.outboundBlink != null) {
            return;
        }
        this.outboundBlink = new LagRequest(EnumLagDirection.ONLY_OUTBOUND,
                new ModuleBackedTimeout(this));
        this.outboundBlinkStartedAtMs = System.currentTimeMillis();
        Myau.lagHandler.requestLag(this.outboundBlink);
        this.releaseBlinkNextGameTick = true;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        this.suppressUseForOverrideAttackTick = false;
        if (this.releaseBlinkNextGameTick) {
            this.releaseBlink();
            this.releaseBlinkNextGameTick = false;
        }
        if (this.outboundBlink != null
                && System.currentTimeMillis() - this.outboundBlinkStartedAtMs
                        >= this.maxBlinkHold.getValue()) {
            this.releaseBlink();
        }
        if (this.isOverrideAttackEnabled()) {
            this.advanceOverrideAttackState();
        } else if (this.overrideAttackState != OverrideAttackState.IDLE) {
            this.resetOverrideAttackState();
        }
    }

    private void releaseBlink() {
        if (this.outboundBlink != null) {
            this.outboundBlink.getTimeout().forceTimeOut();
            this.outboundBlink = null;
        }
    }


    private boolean tryFindVoidDirection(EntityPlayer target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            return false;
        }
        dx /= dist;
        dz /= dist;
        double rightX = -dz;
        double rightZ = dx;
        double eyeY = target.posY + target.getEyeHeight();
        int leftVoidCount = 0;
        int rightVoidCount = 0;
        for (int i = 1; i <= 12; i++) {
            double off = i * VOID_SCAN_STEP;
            double rx = target.posX + rightX * off;
            double rz = target.posZ + rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(rx, eyeY, rz),
                    new Vec3(rx, eyeY - 10.0, rz)) == null) {
                rightVoidCount++;
            }
            double lx = target.posX - rightX * off;
            double lz = target.posZ - rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(lx, eyeY, lz),
                    new Vec3(lx, eyeY - 10.0, lz)) == null) {
                leftVoidCount++;
            }
        }
        if (leftVoidCount == 0 && rightVoidCount == 0) {
            return false;
        }
        if (leftVoidCount != rightVoidCount) {
            this.displaceLeft = leftVoidCount > rightVoidCount;
        }
        return true;
    }

    private Float findBestVoidYaw(EntityPlayer target, float playerYaw) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        float offset = this.yawOffset.getValue();
        float preferredYawPositive = MathHelper.wrapAngleTo180_float(playerYaw + offset);
        float preferredYawNegative = MathHelper.wrapAngleTo180_float(playerYaw - offset);
        double scanDistance = this.scanRadius.getValue();
        Map<Long, Boolean> voidColumns = new HashMap<Long, Boolean>();
        Map<Long, VoidNeighborhood> voidNeighborhoods = new HashMap<Long, VoidNeighborhood>();

        VoidYawCandidate best = null;
        for (int directionIndex = 0; directionIndex < VOID_SCAN_DIRECTIONS; directionIndex++) {
            float candidateYaw = MathHelper.wrapAngleTo180_float(
                    preferredYawPositive + (float) (directionIndex * VOID_SCAN_ANGLE_STEP));
            best = this.selectBetterVoidYaw(best, candidateYaw, preferredYawPositive,
                    preferredYawNegative, target, scanDistance, voidColumns, voidNeighborhoods);
        }
        if (best == null) {
            return null;
        }
        float coarseYaw = best.yaw;
        for (double refinementOffset = -VOID_SCAN_ANGLE_STEP + VOID_REFINEMENT_STEP;
             refinementOffset < VOID_SCAN_ANGLE_STEP; refinementOffset++) {
            if (Math.abs(refinementOffset) < VOID_SCORE_EPSILON) {
                continue;
            }
            float candidateYaw =
                    MathHelper.wrapAngleTo180_float(coarseYaw + (float) refinementOffset);
            best = this.selectBetterVoidYaw(best, candidateYaw, preferredYawPositive,
                    preferredYawNegative, target, scanDistance, voidColumns, voidNeighborhoods);
        }
        return best.yaw;
    }

    private VoidYawCandidate selectBetterVoidYaw(VoidYawCandidate currentBest, float candidateYaw,
                                                 float preferredYawPositive,
                                                 float preferredYawNegative, EntityPlayer target,
                                                 double scanDistance,
                                                 Map<Long, Boolean> voidColumns,
                                                 Map<Long, VoidNeighborhood> voidNeighborhoods) {
        double radians = Math.toRadians(candidateYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double positiveDistance =
                Math.abs(MathHelper.wrapAngleTo180_float(candidateYaw - preferredYawPositive));
        double negativeDistance =
                Math.abs(MathHelper.wrapAngleTo180_float(candidateYaw - preferredYawNegative));
        double priorityDistance = Math.min(positiveDistance, negativeDistance);
        VoidPathScore pathScore = this.scoreVoidPath(target, forwardX, forwardZ, scanDistance,
                voidColumns, voidNeighborhoods);
        if (pathScore == null) {
            return currentBest;
        }
        VoidYawCandidate candidate =
                new VoidYawCandidate(candidateYaw, pathScore, priorityDistance);
        return currentBest != null && candidate.compareTo(currentBest) <= 0
                ? currentBest : candidate;
    }

    private VoidPathScore scoreVoidPath(EntityPlayer target, double forwardX, double forwardZ,
                                        double scanDistance, Map<Long, Boolean> voidColumns,
                                        Map<Long, VoidNeighborhood> voidNeighborhoods) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        double sideOffset = Math.max(0.2, target.width * 0.45);
        double checkedForward = 0.0;
        double pathQuality = 0.0;
        int consecutiveCenterVoid = 0;
        int longestCenterVoidRun = 0;
        int bestNeighborhoodQuality = -1;
        int bestDestinationWidth = 0;
        double bestDestinationDistance = Double.MAX_VALUE;
        double bestDestinationX = 0.0;
        double bestDestinationZ = 0.0;
        VoidNeighborhood bestNeighborhood = null;
        AxisAlignedBB collisionBox = target.getEntityBoundingBox()
                .expand(VOID_COLLISION_INSET, 0.001, VOID_COLLISION_INSET);

        for (double forward = VOID_SCAN_STEP; forward <= scanDistance + VOID_SCORE_EPSILON;
             forward += VOID_SCAN_STEP) {
            double blockedDistance = this.getVoidPathBlockedDistance(collisionBox, forwardX,
                    forwardZ, checkedForward, forward);
            if (blockedDistance >= 0.0) {
                break;
            }
            checkedForward = forward;
            double centerX = target.posX + forwardX * forward;
            double centerZ = target.posZ + forwardZ * forward;
            boolean centerVoid = this.isVoidColumn(centerX, target.posY, centerZ, voidColumns);
            boolean leftVoid = this.isVoidColumn(centerX - sideX * sideOffset, target.posY,
                    centerZ - sideZ * sideOffset, voidColumns);
            boolean rightVoid = this.isVoidColumn(centerX + sideX * sideOffset, target.posY,
                    centerZ + sideZ * sideOffset, voidColumns);
            double distanceWeight = scanDistance + VOID_SCAN_STEP - forward;
            if (leftVoid) {
                pathQuality += distanceWeight;
            }
            if (rightVoid) {
                pathQuality += distanceWeight;
            }
            if (!centerVoid) {
                consecutiveCenterVoid = 0;
                continue;
            }
            consecutiveCenterVoid++;
            longestCenterVoidRun = Math.max(longestCenterVoidRun, consecutiveCenterVoid);
            pathQuality += distanceWeight * 1.6 + consecutiveCenterVoid * 2.0;
            VoidNeighborhood neighborhood = this.getVoidNeighborhood(centerX, target.posY, centerZ,
                    voidColumns, voidNeighborhoods);
            int neighborhoodQuality = neighborhood.quality;
            int destinationWidth = 1 + (leftVoid ? 1 : 0) + (rightVoid ? 1 : 0);
            if (neighborhoodQuality > bestNeighborhoodQuality
                    || neighborhoodQuality == bestNeighborhoodQuality
                    && destinationWidth > bestDestinationWidth
                    || neighborhoodQuality == bestNeighborhoodQuality
                    && destinationWidth == bestDestinationWidth
                    && forward < bestDestinationDistance) {
                bestNeighborhoodQuality = neighborhoodQuality;
                bestDestinationWidth = destinationWidth;
                bestDestinationDistance = forward;
                bestDestinationX = centerX;
                bestDestinationZ = centerZ;
                bestNeighborhood = neighborhood;
            }
        }
        if (bestNeighborhoodQuality < 0) {
            return null;
        }
        return new VoidPathScore(bestNeighborhood.immediateVoidNeighbors,
                bestNeighborhood.extendedVoidNeighbors, bestDestinationWidth, longestCenterVoidRun,
                pathQuality, bestDestinationDistance, bestDestinationX, bestDestinationZ);
    }

    private VoidNeighborhood getVoidNeighborhood(double x, double y, double z,
                                                 Map<Long, Boolean> voidColumns,
                                                 Map<Long, VoidNeighborhood> voidNeighborhoods) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        VoidNeighborhood cached = voidNeighborhoods.get(columnKey);
        if (cached != null) {
            return cached;
        }
        int immediateVoidNeighbors = 0;
        int extendedVoidNeighbors = 0;
        long voidMask = 0L;
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int radius = Math.max(Math.abs(offsetX), Math.abs(offsetZ));
                if (radius == 0 || !this.isVoidColumn(blockX + offsetX + 0.5, y,
                        blockZ + offsetZ + 0.5, voidColumns)) {
                    continue;
                }
                voidMask |= 1L << ((offsetX + 2) * 5 + offsetZ + 2);
                if (radius == 1) {
                    immediateVoidNeighbors++;
                } else {
                    extendedVoidNeighbors++;
                }
            }
        }
        int quality = immediateVoidNeighbors * 100 + extendedVoidNeighbors;
        VoidNeighborhood neighborhood = new VoidNeighborhood(blockX, blockZ,
                immediateVoidNeighbors, extendedVoidNeighbors, quality, voidMask);
        voidNeighborhoods.put(columnKey, neighborhood);
        return neighborhood;
    }

    private double getVoidPathBlockedDistance(AxisAlignedBB collisionBox, double forwardX,
                                              double forwardZ, double fromForward,
                                              double toForward) {
        double forward = fromForward + VOID_COLLISION_STEP;
        while (forward <= toForward + VOID_SCORE_EPSILON) {
            AxisAlignedBB checkBox = collisionBox.offset(forwardX * forward, 0.0,
                    forwardZ * forward);
            BlockPos minCorner = new BlockPos(checkBox.minX, checkBox.minY, checkBox.minZ);
            BlockPos maxCorner = new BlockPos(checkBox.maxX, checkBox.maxY, checkBox.maxZ);
            if (!mc.theWorld.isBlockLoaded(minCorner) || !mc.theWorld.isBlockLoaded(maxCorner)) {
                return forward;
            }
            List<AxisAlignedBB> blockCollisions = mc.theWorld.getCollisionBoxes(checkBox);
            if (blockCollisions.isEmpty()
                    || this.ignoreOneBlockWall.getValue()
                    && isOneBlockWall(checkBox, blockCollisions)) {
                forward += VOID_COLLISION_STEP;
                continue;
            }
            return forward;
        }
        return -1.0;
    }

    private static boolean isOneBlockWall(AxisAlignedBB checkBox,
                                          List<AxisAlignedBB> blockCollisions) {
        double maximumWallY = checkBox.minY + 1.0 + VOID_SCORE_EPSILON;
        for (AxisAlignedBB collision : blockCollisions) {
            if (collision.maxY > maximumWallY) {
                return false;
            }
        }
        return true;
    }

    private boolean isVoidColumn(double x, double y, double z, Map<Long, Boolean> voidColumns) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        Boolean cached = voidColumns.get(columnKey);
        if (cached != null) {
            return cached;
        }
        int startY = Math.min(255, MathHelper.floor_double(y - 0.01));
        BlockPos.MutableBlockPos blockPos =
                new BlockPos.MutableBlockPos(blockX, Math.max(0, startY), blockZ);
        if (!mc.theWorld.isBlockLoaded(blockPos)) {
            voidColumns.put(columnKey, false);
            return false;
        }
        for (int blockY = startY; blockY >= 0; blockY--) {
            blockPos.set(blockX, blockY, blockZ);
            if (!mc.theWorld.isAirBlock(blockPos)) {
                voidColumns.put(columnKey, false);
                return false;
            }
        }
        voidColumns.put(columnKey, true);
        return true;
    }

    private static long getColumnKey(int blockX, int blockZ) {
        return (long) blockX << 32 ^ blockZ & 4294967295L;
    }

    private static final class VoidYawCandidate {
        private final float yaw;
        private final VoidPathScore pathScore;
        private final double priorityDistance;

        private VoidYawCandidate(float yaw, VoidPathScore pathScore, double priorityDistance) {
            this.yaw = yaw;
            this.pathScore = pathScore;
            this.priorityDistance = priorityDistance;
        }

        private int compareTo(VoidYawCandidate other) {
            int pathComparison = this.pathScore.compareTo(other.pathScore);
            if (pathComparison != 0) {
                return pathComparison;
            }
            if (Math.abs(this.priorityDistance - other.priorityDistance) > VOID_SCORE_EPSILON) {
                return this.priorityDistance < other.priorityDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidPathScore {
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int destinationWidth;
        private final int longestCenterVoidRun;
        private final double pathQuality;
        private final double destinationDistance;
        private final double destinationX;
        private final double destinationZ;

        private VoidPathScore(int immediateVoidNeighbors, int extendedVoidNeighbors,
                              int destinationWidth, int longestCenterVoidRun, double pathQuality,
                              double destinationDistance, double destinationX,
                              double destinationZ) {
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.destinationWidth = destinationWidth;
            this.longestCenterVoidRun = longestCenterVoidRun;
            this.pathQuality = pathQuality;
            this.destinationDistance = destinationDistance;
            this.destinationX = destinationX;
            this.destinationZ = destinationZ;
        }

        private int compareTo(VoidPathScore other) {
            if (this.immediateVoidNeighbors != other.immediateVoidNeighbors) {
                return Integer.compare(this.immediateVoidNeighbors, other.immediateVoidNeighbors);
            }
            if (this.extendedVoidNeighbors != other.extendedVoidNeighbors) {
                return Integer.compare(this.extendedVoidNeighbors, other.extendedVoidNeighbors);
            }
            if (this.destinationWidth != other.destinationWidth) {
                return Integer.compare(this.destinationWidth, other.destinationWidth);
            }
            if (this.longestCenterVoidRun != other.longestCenterVoidRun) {
                return Integer.compare(this.longestCenterVoidRun, other.longestCenterVoidRun);
            }
            if (Math.abs(this.pathQuality - other.pathQuality) > VOID_SCORE_EPSILON) {
                return this.pathQuality > other.pathQuality ? 1 : -1;
            }
            if (Math.abs(this.destinationDistance - other.destinationDistance)
                    > VOID_SCORE_EPSILON) {
                return this.destinationDistance < other.destinationDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidNeighborhood {
        private final int blockX;
        private final int blockZ;
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int quality;
        private final long voidMask;

        private VoidNeighborhood(int blockX, int blockZ, int immediateVoidNeighbors,
                                 int extendedVoidNeighbors, int quality, long voidMask) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.quality = quality;
            this.voidMask = voidMask;
        }
    }

    private static final class DisplacementLock {
        private final float yaw;
        private int lastUseTick;
        private boolean airborne;

        private DisplacementLock(float yaw, int lastUseTick, boolean airborne) {
            this.yaw = yaw;
            this.lastUseTick = lastUseTick;
            this.airborne = airborne;
        }
    }
}
