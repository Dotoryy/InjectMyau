package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.PreAttackEvent;
import myau.events.PrePlayerInteractEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.CombatTargeting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double HIT_RANGE_SQ = 9.0;
    private static final int HURT_WINDOW_TICKS = 10;
    private static final int SERVER_CONFIRM_COOLDOWN_TICKS = 10;
    private static final int SERVER_CONFIRM_TIMEOUT_TICKS = 30;
    private static final int BLOCK_WAIT_FIRST = 1;
    private static final int BLOCK_SERVER_COOLDOWN = 8;
    private static final int BLOCK_PREDICTED_BURST = 16;
    private static final int BLOCK_CRITICALS = 32;

    public final IntProperty pauseDuration = new IntProperty("pause-duration", 500, 0, 500, 50);
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BURST", "CRITICALS"});
    public final IntProperty waitForFirstHit = new IntProperty("wait-for-first-hit", 0, 0, 500, 50);
    public final IntProperty whenOnlyCombo = new IntProperty("when-only-combo", 0, 0, 10);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapons-only", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty disableDuringKnockback = new BooleanProperty("disable-during-knockback", false);
    public final BooleanProperty onlyWhileDamaged = new BooleanProperty("only-while-damaged", false);
    public final BooleanProperty useServerAttackTime = new BooleanProperty("use-server-attack-time", false);
    public final BooleanProperty fakeSwing = new BooleanProperty("fake-swing", false);
    public final PercentProperty inCombatCancelRate = new PercentProperty("in-combat-cancel-rate", 100);
    public final PercentProperty missedSwingsCancelRate = new PercentProperty("missed-swings-cancel-rate", 0);

    private EntityPlayer currentTarget;
    private final Map<Integer, TargetState> targetStates = new HashMap<>();
    private int lastSelfHurtTime;
    private boolean takingKnockback;
    private boolean waitFirstTracking;
    private int waitFirstStartTick = -1;
    private boolean waitFirstUnlocked;
    private int tickCounter;

    public HitSelect() {
        super("Hit Select", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == 1 ? "Criticals" : "Burst"};
    }

    @Override
    public void onEnabled() {
        this.tickCounter = 0;
        this.resetAllState();
    }

    @Override
    public void onDisabled() {
        this.resetAllState();
    }

    private static int msToTicks(double ms) {
        return ms <= 0.0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    @EventTarget
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (mc.thePlayer != null && mc.theWorld != null && !mc.thePlayer.isDead) {
            ++this.tickCounter;
            int currentTick = this.tickCounter;
            this.pruneTargetStates();
            EntityPlayer nextTarget = CombatTargeting.findTarget(HIT_RANGE_SQ, this.ignoreTeammates.getValue());
            this.updateCurrentTarget(nextTarget, currentTick);
            this.updateSelfDamage(currentTick);
            this.updateTargetDamage(currentTick);
        } else {
            this.resetAllState();
        }
    }

    @EventTarget
    public void onPreAttack(PreAttackEvent event) {
        if (!this.canProcessClicks() || this.weaponOnly.getValue() && !this.holdingWeapon()) {
            return;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.getTarget() != null) {
            return;
        }
        int currentTick = this.tickCounter;
        ClickType clickType = this.classifyClick(event.objectMouseOver);
        if (clickType == ClickType.BLOCK_INTERACTION) {
            return;
        }
        if (clickType == ClickType.MISSED_SWING) {
            if (this.shouldCancel(this.missedSwingsCancelRate.getValue())) {
                this.cancelClick(event);
            }
            return;
        }
        EntityPlayer clickedTarget = CombatTargeting.asValidPlayer(
                event.objectMouseOver == null ? null : event.objectMouseOver.entityHit,
                HIT_RANGE_SQ, this.ignoreTeammates.getValue());
        if (clickedTarget == null) {
            return;
        }
        this.updateCurrentTarget(clickedTarget, currentTick);
        TargetState state = this.getTargetState(clickedTarget, currentTick);
        int blockMask = this.getValidHitBlockMask(state, currentTick);
        boolean shouldBlock = (blockMask & BLOCK_WAIT_FIRST) != 0
                || (blockMask & BLOCK_PREDICTED_BURST) != 0
                || this.applyPauseDuration(state, blockMask & ~BLOCK_PREDICTED_BURST, currentTick);
        if (shouldBlock && this.isComboGateOpen(state) && this.shouldCancel(this.inCombatCancelRate.getValue())) {
            this.cancelClick(event);
        } else {
            this.recordPassedValidHit(clickedTarget, currentTick);
        }
    }

    public boolean shouldBlockAttack(EntityLivingBase targetEntity) {
        if (!this.isEnabled() || !this.canProcessClicks()) {
            return false;
        }
        if (this.weaponOnly.getValue() && !this.holdingWeapon()) {
            return false;
        }
        EntityPlayer clickedTarget = CombatTargeting.asValidPlayer(
                targetEntity, HIT_RANGE_SQ, this.ignoreTeammates.getValue());
        if (clickedTarget == null) {
            return false;
        }
        int currentTick = this.tickCounter;
        this.updateCurrentTarget(clickedTarget, currentTick);
        TargetState state = this.getTargetState(clickedTarget, currentTick);
        int blockMask = this.getValidHitBlockMask(state, currentTick);
        boolean shouldBlock = (blockMask & BLOCK_WAIT_FIRST) != 0
                || (blockMask & BLOCK_PREDICTED_BURST) != 0
                || this.applyPauseDuration(state, blockMask & ~BLOCK_PREDICTED_BURST, currentTick);
        boolean blocked = shouldBlock && this.isComboGateOpen(state)
                && this.shouldCancel(this.inCombatCancelRate.getValue());
        if (blocked && this.fakeSwing.getValue() && mc.thePlayer != null) {
            this.setSwinging();
        }
        return blocked;
    }

    public void confirmHit(EntityLivingBase targetEntity) {
        if (!this.isEnabled() || !this.canProcessClicks()) {
            return;
        }
        if (this.weaponOnly.getValue() && !this.holdingWeapon()) {
            return;
        }
        EntityPlayer target = CombatTargeting.asValidPlayer(
                targetEntity, HIT_RANGE_SQ, this.ignoreTeammates.getValue());
        if (target == null) {
            return;
        }
        this.recordPassedValidHit(target, this.tickCounter);
    }

    public boolean shouldCancelMissedSwing() {
        return this.isEnabled() && this.shouldCancel(this.missedSwingsCancelRate.getValue());
    }

    private boolean canProcessClicks() {
        return mc.thePlayer != null && mc.theWorld != null && !mc.thePlayer.isDead;
    }

    private ClickType classifyClick(MovingObjectPosition objectMouseOver) {
        if (objectMouseOver == null) {
            return ClickType.MISSED_SWING;
        }
        if (objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            return ClickType.BLOCK_INTERACTION;
        }
        if (objectMouseOver.typeOfHit == MovingObjectType.ENTITY) {
            Entity entityHit = objectMouseOver.entityHit;
            return CombatTargeting.asValidPlayer(entityHit, HIT_RANGE_SQ, this.ignoreTeammates.getValue()) != null
                    ? ClickType.VALID_HIT : ClickType.MISSED_SWING;
        }
        return ClickType.MISSED_SWING;
    }

    private void cancelClick(PreAttackEvent event) {
        if (this.fakeSwing.getValue() && mc.thePlayer != null) {
            this.setSwinging();
        }
        event.setCancelled(true);
    }

    private void updateCurrentTarget(EntityPlayer nextTarget, int currentTick) {
        if (this.sameTarget(nextTarget)) {
            if (nextTarget != null) {
                this.currentTarget = nextTarget;
                this.getTargetState(nextTarget, currentTick);
            }
            return;
        }
        this.currentTarget = nextTarget;
        if (nextTarget == null) {
            this.resetWaitFirstState();
        } else if (!this.waitFirstTracking) {
            this.waitFirstTracking = true;
            this.waitFirstStartTick = currentTick;
            this.waitFirstUnlocked = false;
        }
        if (nextTarget != null) {
            this.getTargetState(nextTarget, currentTick);
        }
    }

    private void updateSelfDamage(int currentTick) {
        int hurtTime = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurtTime > this.lastSelfHurtTime;
        if (hurtAgain) {
            if (this.waitFirstTracking && !this.waitFirstUnlocked) {
                this.waitFirstUnlocked = true;
            }
            if (!this.takingKnockback) {
                this.takingKnockback = true;
            }
            if (this.currentTarget != null) {
                TargetState state = this.getTargetState(this.currentTarget, currentTick);
                state.firstSelfHitSeen = true;
                state.comboCount = 0;
            }
        }
        if (this.takingKnockback && mc.thePlayer.onGround && !hurtAgain) {
            this.takingKnockback = false;
        }
        this.lastSelfHurtTime = hurtTime;
    }

    private void updateTargetDamage(int currentTick) {
        if (this.currentTarget == null) {
            return;
        }
        TargetState state = this.getTargetState(this.currentTarget, currentTick);
        int targetHurtTime = this.currentTarget.hurtTime;
        if (targetHurtTime > state.lastObservedTargetHurtTime) {
            state.comboCount++;
        }
        if (this.useServerAttackTime.getValue()) {
            if (state.pendingServerConfirmationTick >= 0
                    && currentTick - state.pendingServerConfirmationTick > SERVER_CONFIRM_TIMEOUT_TICKS) {
                state.pendingServerConfirmationTick = -1;
            }
            if (state.pendingServerConfirmationTick >= 0 && targetHurtTime > state.lastObservedTargetHurtTime) {
                state.pendingServerConfirmationTick = -1;
                state.lastConfirmedTargetDamageTick = currentTick;
                state.rawBlockMask = BLOCK_SERVER_COOLDOWN;
                state.rawBlockStartTick = currentTick;
            }
        }
        state.lastObservedTargetHurtTime = targetHurtTime;
    }

    private int getValidHitBlockMask(TargetState state, int currentTick) {
        if (this.currentTarget == null) {
            return 0;
        }
        if (this.disableDuringKnockback.getValue() && this.isTakingKnockback()) {
            return 0;
        }
        int blockMask = 0;
        if (this.isWaitingForFirstHit(currentTick)) {
            blockMask |= BLOCK_WAIT_FIRST;
        }
        blockMask |= this.getBurstBlockMask(state, currentTick);
        if (this.isCriticalsBlocked(state)) {
            blockMask |= BLOCK_CRITICALS;
        }
        return blockMask;
    }

    private int getBurstBlockMask(TargetState state, int currentTick) {
        if (this.useServerAttackTime.getValue()) {
            return state.lastConfirmedTargetDamageTick >= 0
                    && currentTick - state.lastConfirmedTargetDamageTick < SERVER_CONFIRM_COOLDOWN_TICKS
                    ? BLOCK_SERVER_COOLDOWN : 0;
        }
        if (!this.isPredictedBurstWindowActive(state, currentTick)) {
            return 0;
        }
        int pauseTicks = msToTicks(this.pauseDuration.getValue());
        return pauseTicks > 0 && currentTick - state.predictedBurstWindowStartTick < pauseTicks
                ? BLOCK_PREDICTED_BURST : 0;
    }

    private boolean isCriticalsBlocked(TargetState state) {
        if (this.mode.getValue() != 1) {
            return false;
        }
        if (mc.thePlayer.onGround) {
            return false;
        }
        if (this.onlyWhileDamaged.getValue() && !state.firstSelfHitSeen) {
            return false;
        }
        if (this.disableDuringKnockback.getValue() && this.isTakingKnockback()) {
            return false;
        }
        return !this.canCriticalHit();
    }

    private boolean isWaitingForFirstHit(int currentTick) {
        if (this.waitForFirstHit.getValue() <= 0
                || this.currentTarget == null
                || !this.waitFirstTracking
                || this.waitFirstUnlocked
                || this.waitFirstStartTick < 0) {
            return false;
        }
        int requiredTicks = msToTicks(this.waitForFirstHit.getValue());
        return requiredTicks > 0 && currentTick - this.waitFirstStartTick < requiredTicks;
    }

    private boolean canCriticalHit() {
        return mc.thePlayer.fallDistance > 0.0F
                && !mc.thePlayer.onGround
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isPotionActive(Potion.blindness)
                && mc.thePlayer.ridingEntity == null;
    }

    private boolean isComboGateOpen(TargetState state) {
        return this.whenOnlyCombo.getValue() <= 0 || state.comboCount >= this.whenOnlyCombo.getValue();
    }

    private boolean isTakingKnockback() {
        return this.takingKnockback || mc.thePlayer.hurtTime > 0;
    }

    private boolean applyPauseDuration(TargetState state, int blockMask, int currentTick) {
        if (blockMask == 0) {
            state.rawBlockMask = 0;
            state.rawBlockStartTick = -1;
            return false;
        }
        if (this.pauseDuration.getValue() <= 0) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
            return false;
        }
        if (blockMask != state.rawBlockMask) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
        } else if (state.rawBlockStartTick < 0) {
            state.rawBlockStartTick = currentTick;
        }
        int requiredTicks = msToTicks(this.pauseDuration.getValue());
        return requiredTicks > 0 && currentTick - state.rawBlockStartTick < requiredTicks;
    }

    private void recordPassedValidHit(EntityPlayer target, int currentTick) {
        if (target == null) {
            return;
        }
        this.updateCurrentTarget(target, currentTick);
        TargetState state = this.getTargetState(target, currentTick);
        if (this.useServerAttackTime.getValue()) {
            state.pendingServerConfirmationTick = currentTick;
            state.lastConfirmedTargetDamageTick = -1;
        } else if (!this.isPredictedBurstWindowActive(state, currentTick)) {
            this.startPredictedBurstWindow(state, currentTick, HURT_WINDOW_TICKS);
        }
    }

    private boolean shouldCancel(double chance) {
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 100.0) {
            return true;
        }
        return Math.random() * 100.0 < chance;
    }

    private boolean sameTarget(EntityPlayer nextTarget) {
        if (this.currentTarget != null && nextTarget != null) {
            return this.currentTarget.getEntityId() == nextTarget.getEntityId();
        }
        return this.currentTarget == nextTarget;
    }

    private void resetWaitFirstState() {
        this.waitFirstTracking = false;
        this.waitFirstStartTick = -1;
        this.waitFirstUnlocked = false;
    }

    private int getHurtWindowTicks(EntityPlayer target) {
        return target != null && target.maxHurtTime > 0
                ? Math.max(HURT_WINDOW_TICKS, target.maxHurtTime) : HURT_WINDOW_TICKS;
    }

    private boolean isPredictedBurstWindowActive(TargetState state, int currentTick) {
        return state.predictedBurstWindowEndTick >= 0 && currentTick < state.predictedBurstWindowEndTick;
    }

    private void startPredictedBurstWindow(TargetState state, int startTick, int windowTicks) {
        int hurtWindowTicks = Math.max(1, windowTicks);
        state.predictedBurstWindowStartTick = startTick;
        state.predictedBurstWindowEndTick = startTick + hurtWindowTicks;
    }

    private void clearPredictedBurstWindow(TargetState state) {
        state.predictedBurstWindowStartTick = -1;
        state.predictedBurstWindowEndTick = -1;
    }

    private void syncPredictedBurstWindow(TargetState state, EntityPlayer target, int currentTick) {
        if (state.predictedBurstWindowEndTick >= 0 && currentTick >= state.predictedBurstWindowEndTick) {
            this.clearPredictedBurstWindow(state);
        }
        if (target != null && target.hurtTime > 0) {
            int hurtWindowTicks = Math.max(this.getHurtWindowTicks(target), target.hurtTime);
            int elapsedWindowTicks = hurtWindowTicks - target.hurtTime;
            int estimatedStartTick = currentTick - Math.max(0, elapsedWindowTicks);
            if (!this.isPredictedBurstWindowActive(state, currentTick)
                    || estimatedStartTick > state.predictedBurstWindowStartTick) {
                this.startPredictedBurstWindow(state, estimatedStartTick, hurtWindowTicks);
            }
        }
    }

    private TargetState getTargetState(EntityPlayer target, int currentTick) {
        TargetState state = this.targetStates.get(target.getEntityId());
        if (state == null) {
            state = new TargetState();
            if (this.useServerAttackTime.getValue()) {
                state.lastObservedTargetHurtTime = target.hurtTime;
            }
            this.targetStates.put(target.getEntityId(), state);
        }
        return state;
    }

    private void pruneTargetStates() {
        if (mc.theWorld == null) {
            this.targetStates.clear();
            return;
        }
        Iterator<Map.Entry<Integer, TargetState>> iterator = this.targetStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TargetState> entry = iterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }
    }

    private void resetAllState() {
        this.currentTarget = null;
        this.targetStates.clear();
        this.lastSelfHurtTime = 0;
        this.takingKnockback = false;
        this.resetWaitFirstState();
    }

    private boolean holdingWeapon() {
        if (mc.thePlayer == null) {
            return false;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return false;
        }
        Item item = held.getItem();
        return item instanceof ItemSword || item instanceof ItemTool;
    }

    private void setSwinging() {
        int armSwingEnd = mc.thePlayer.isPotionActive(Potion.digSpeed)
                ? 6 - (1 + mc.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier())
                : (mc.thePlayer.isPotionActive(Potion.digSlowdown)
                        ? 6 + (1 + mc.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) * 2
                        : 6);
        if (!mc.thePlayer.isSwingInProgress
                || mc.thePlayer.swingProgressInt >= armSwingEnd / 2
                || mc.thePlayer.swingProgressInt < 0) {
            mc.thePlayer.swingProgressInt = -1;
            mc.thePlayer.isSwingInProgress = true;
        }
    }

    private enum ClickType {
        VALID_HIT,
        BLOCK_INTERACTION,
        MISSED_SWING
    }

    private static class TargetState {
        boolean firstSelfHitSeen;
        int lastConfirmedTargetDamageTick = -1;
        int pendingServerConfirmationTick = -1;
        int predictedBurstWindowStartTick = -1;
        int predictedBurstWindowEndTick = -1;
        int lastObservedTargetHurtTime;
        int rawBlockStartTick = -1;
        int rawBlockMask;
        int comboCount;
    }
}
