package myau.module.modules;

import myau.Myau;
import myau.access.AccessorKeyBinding;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.MoveInputEvent;
import myau.events.SprintEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.MovementTicks;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_STANDARD = 0;
    private static final int MODE_PREDICTION = 1;
    private static final int MODE_PREDICTION_2 = 2;
    private static final int KNOCKBACK_GUARD_TICKS = 8;

    private static final int STAGE_IDLE = 0;
    private static final int STAGE_SUPPRESS = 1;
    private static final int STAGE_RESTORE = 2;
    private static final int STAGE_TIMEOUT_TICKS = 5;
    private static final int SPRINT_FOOD_LEVEL = 6;
    private static final int SLOWDOWN_SPRINT_DROP = 60;

    public final ModeProperty mode = new ModeProperty("mode", MODE_STANDARD,
            new String[]{"STANDARD", "PREDICTION", "PREDICTION 2"});
    public final PercentProperty slowdown = new PercentProperty("slowdown", 0,
            () -> this.mode.getValue() != MODE_PREDICTION);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false,
            () -> this.mode.getValue() == MODE_STANDARD);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false,
            () -> this.mode.getValue() == MODE_STANDARD);
    private boolean sprintCancelled = false;
    private int stopTick = Integer.MIN_VALUE;

    private int stage = STAGE_IDLE;
    private int stageTicks = 0;
    private boolean attackRuled = false;
    private boolean eatAttack = false;
    private boolean releasePending = false;

    public KeepSprint() {
        super("Keep Sprint", false);
    }
    @Override
    public String[] getSuffix() {
        switch (this.mode.getValue()) {
            case MODE_PREDICTION:
                return new String[]{"Prediction"};
            case MODE_PREDICTION_2:
                return new String[]{"Prediction 2"};
            default:
                return new String[]{"Standard"};
        }
    }

    @Override
    public void onDisabled() {
        this.sprintCancelled = false;
        this.stopTick = Integer.MIN_VALUE;
        this.resetStage();
    }

    private void resetStage() {
        this.stage = STAGE_IDLE;
        this.stageTicks = 0;
        this.attackRuled = false;
        this.eatAttack = false;
        this.releasePending = false;
    }

    public boolean shouldKeepSprint() {
        if (this.mode.getValue() == MODE_PREDICTION_2) {
            return this.stage == STAGE_RESTORE;
        }
        if (this.mode.getValue() != MODE_STANDARD) {
            return false;
        }
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround) {
            return false;
        }
        if (!this.reachOnly.getValue()) {
            return true;
        }
        return mc.objectMouseOver != null
                && mc.objectMouseOver.hitVec != null
                && mc.objectMouseOver.hitVec.distanceTo(mc.getRenderViewEntity().getPositionEyes(1.0F)) > 3.0;
    }

    public boolean shouldDropSprintAfterHit() {
        return this.mode.getValue() == MODE_PREDICTION_2
                && this.shouldKeepSprint()
                && this.slowdown.getValue() == SLOWDOWN_SPRINT_DROP;
    }

    public boolean shouldDeferAttack(Entity target) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return false;
        }
        if (this.mode.getValue() == MODE_PREDICTION_2) {
            return this.ruleOnAttack(target);
        }
        if (this.mode.getValue() != MODE_PREDICTION) {
            return false;
        }
        if (MovementTicks.sinceVelocity() < KNOCKBACK_GUARD_TICKS) {
            return false;
        }
        if (MovementTicks.ground() == 1) {
            return true;
        }
        if (!mc.thePlayer.isSprinting()) {
            return false;
        }
        mc.thePlayer.setSprinting(false);

        AccessorKeyBinding.setPressed(mc.gameSettings.keyBindSprint, false);
        this.sprintCancelled = true;
        this.stopTick = mc.thePlayer.ticksExisted;
        return true;
    }

    private boolean ruleOnAttack(Entity target) {
        if (this.attackRuled) {
            return this.eatAttack;
        }
        this.attackRuled = true;
        this.eatAttack = false;
        this.releasePending = false;
        if (target != null && !(target instanceof EntityPlayer)) {
            return false;
        }
        switch (this.stage) {
            case STAGE_IDLE:
                if (mc.thePlayer.isSprinting()) {
                    this.stage = STAGE_SUPPRESS;
                    this.stageTicks = 0;
                    this.eatAttack = true;
                } else {
                    this.releasePending = true;
                }
                break;
            case STAGE_SUPPRESS:
                mc.thePlayer.setSprinting(false);
                this.releasePending = true;
                break;
            default:
                break;
        }
        return this.eatAttack;
    }

    public void confirmAttack() {
        if (!this.isEnabled() || this.mode.getValue() != MODE_PREDICTION_2
                || !this.releasePending) {
            return;
        }
        this.releasePending = false;
        this.stageTicks = 0;
        this.stage = STAGE_RESTORE;
    }

    private static boolean canSprintNow() {
        NoSlow noSlow = (NoSlow) Myau.moduleManager.modules.get(NoSlow.class);
        boolean noSlowSprint = noSlow != null && noSlow.isEnabled()
                && (noSlow.isSwordActive() && noSlow.swordSprint.getValue()
                        || noSlow.isFoodActive() && noSlow.foodSprint.getValue()
                        || noSlow.isBowActive() && noSlow.bowSprint.getValue());
        return mc.thePlayer.movementInput.moveForward > 0.0F
                && (noSlowSprint || !mc.thePlayer.isUsingItem())
                && !mc.thePlayer.isSneaking()
                && !mc.thePlayer.isCollidedHorizontally
                && mc.thePlayer.getFoodStats().getFoodLevel() > SPRINT_FOOD_LEVEL;
    }

    private void applyStage() {
        switch (this.stage) {
            case STAGE_SUPPRESS:
                mc.thePlayer.setSprinting(false);
                break;
            case STAGE_RESTORE:
                if (!mc.thePlayer.isUsingItem() || canSprintNow()) {
                    mc.thePlayer.setSprinting(true);
                }
                break;
            default:
                break;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (!this.isEnabled() || this.mode.getValue() != MODE_PREDICTION_2 || mc.thePlayer == null) {
            this.resetStage();
            return;
        }
        if (this.stageTicks > STAGE_TIMEOUT_TICKS) {
            this.resetStage();
        }
        switch (this.stage) {
            case STAGE_SUPPRESS:
                mc.thePlayer.setSprinting(false);
                this.stageTicks++;
                break;
            case STAGE_RESTORE:
                this.applyStage();
                this.stageTicks = 0;
                this.stage = STAGE_IDLE;
                break;
            default:
                break;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_PREDICTION_2 || mc.thePlayer == null) {
            return;
        }
        if (!event.isFromController()) {
            return;
        }
        if (this.ruleOnAttack(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onSprint(SprintEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == MODE_PREDICTION_2) {
            this.applyStage();
            return;
        }
        if (this.mode.getValue() != MODE_PREDICTION) {
            return;
        }
        if (mc.thePlayer.ticksExisted == this.stopTick && mc.thePlayer.isSprinting()) {
            mc.thePlayer.setSprinting(false);
        }
    }
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled() && this.sprintCancelled && !mc.thePlayer.isSprinting()) {
            mc.thePlayer.movementInput.jump = false;
        }
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        this.attackRuled = false;
        this.eatAttack = false;
        if (mc.thePlayer == null || mc.thePlayer.isSprinting()
                || this.mode.getValue() != MODE_PREDICTION || !hasTarget()) {
            this.sprintCancelled = false;
        }
    }
    private static boolean hasTarget() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.getTarget() != null;
    }
}
