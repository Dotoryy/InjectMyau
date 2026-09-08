package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MouseButtonEvent;
import myau.events.PacketEvent;
import myau.events.PrePlayerInteractEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.RightClickMouseEvent;
import myau.events.UseItemEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.timeout.ModuleBackedTimeout;
import myau.module.Module;
import myau.util.BlockUtil;
import myau.util.CombatTargeting;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.ReflectionUtils;
import myau.util.TeamUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

public class Autoblock extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_VANILLA = 0;
    private static final int MODE_LAG = 1;

    private static final int UNBLOCK_DISABLED = 0;
    private static final int UNBLOCK_ONCE = 1;
    private static final int UNBLOCK_ALWAYS = 2;

    private static final int RIGHT_MOUSE = 1;
    private static final int LEFT_MOUSE = 0;

    public final ModeProperty mode =
            new ModeProperty("mode", MODE_VANILLA, new String[]{"Vanilla", "Lag"});
    public final FloatProperty range = new FloatProperty("range", 4.0F, 2.0F, 6.0F, 0.1F);
    public final FloatProperty maxHurtTimeMs =
            new FloatProperty("maximum-hurt-time", 200.0F, 50.0F, 500.0F, 50.0F);
    public final FloatProperty maxHoldMs =
            new FloatProperty("maximum-hold-duration", 150.0F, 50.0F, 500.0F, 50.0F);
    public final FloatProperty cooldownMs =
            new FloatProperty("cooldown", 0.0F, 0.0F, 500.0F, 50.0F);
    public final FloatProperty lagChance = new FloatProperty("lag-chance", 100.0F, 0.0F, 100.0F,
            5.0F, () -> Autoblock.staticIsLagMode());
    public final FloatProperty lagMaxDuration = new FloatProperty("lag-max-duration", 200.0F,
            50.0F, 500.0F, 50.0F, () -> Autoblock.staticIsLagMode());
    public final ModeProperty unblockOutOfRange = new ModeProperty("unblock-out-of-range",
            UNBLOCK_ONCE, new String[]{"Disabled", "Once", "Always"});
    public final BooleanProperty preventDelayAttacks =
            new BooleanProperty("prevent-delaying-attacks", true, () -> Autoblock.staticIsLagMode());
    public final BooleanProperty blockAgainImmediately =
            new BooleanProperty("block-again-immediately", true, () -> Autoblock.staticIsLagMode());
    public final BooleanProperty forceBlockAnimation =
            new BooleanProperty("force-block-animation", true);
    public final BooleanProperty requireLmb = new BooleanProperty("require-left-mouse", true);
    public final BooleanProperty requireRmb = new BooleanProperty("require-right-mouse", false);
    public final BooleanProperty onlyWhenDamaged = new BooleanProperty("damaged", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);

    private boolean isBlocking = false;
    private boolean manualBlock = false;
    private boolean targetWasInRange = false;
    private boolean unblockedAfterLeavingRange = false;
    private boolean allowingAlwaysInteraction = false;
    private int blockStartTick = -1;
    private long lastBlockEndTimeMs = 0L;
    private EntityPlayer currentTarget = null;
    private int lastSelfHurtTime = 0;
    private boolean isLagging = false;
    private int lagStartTick = -1;
    private LagRequest outboundLag = null;
    private int tickCounter = 0;

    public Autoblock() {
        super("Auto Block", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @Override
    public void onEnabled() {
        this.tickCounter = 0;
        this.resetState(false);
    }

    @Override
    public void onDisabled() {
        this.resetState(true);
    }

    private static int msToTicks(double ms) {
        return ms <= 0.0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    private boolean isLagMode() {
        return this.mode.getValue() == MODE_LAG;
    }

    private static boolean staticIsLagMode() {
        Autoblock module = (Autoblock) Myau.moduleManager.modules.get(Autoblock.class);
        return module != null && module.isLagMode();
    }

    private static boolean isBedBreaking() {
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        return bedNuker != null && bedNuker.isEnabled() && bedNuker.isBreaking();
    }

    private static boolean isKillAuraAttacking(EntityPlayer target) {
        if (target == null) {
            return false;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && !killAura.requirePress.getValue();
    }

    private int unblockMode() {
        return this.unblockOutOfRange.getValue();
    }


    @EventTarget(Priority.HIGHEST)
    public void onMouseButton(MouseButtonEvent event) {
        if (!this.isEnabled() || event.getButton() != RIGHT_MOUSE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || !ItemUtil.isHoldingSword()) {
            return;
        }
        if (isBedBreaking()) {
            return;
        }
        if (this.unblockMode() == UNBLOCK_ALWAYS) {
            if (!event.isPressed() && this.allowingAlwaysInteraction) {
                this.allowingAlwaysInteraction = false;
                return;
            }
            if (event.isPressed() && this.canInteractWhileAlwaysUnblocked()) {
                this.releaseLag();
                this.stopBlocking(true);
                this.manualBlock = false;
                this.allowingAlwaysInteraction = true;
                return;
            }
        }
        event.setCancelled(true);
    }

    @EventTarget(Priority.HIGHEST)
    public void onRightClickMouse(RightClickMouseEvent event) {
        if (this.shouldBlockVanillaUse()) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onUseItem(UseItemEvent event) {
        if (this.allowingAlwaysInteraction || this.shouldBlockVanillaUse()) {
            event.setCancelled(true);
        }
    }

    private boolean shouldBlockVanillaUse() {
        return this.isEnabled() && this.isLagging && mc.thePlayer != null && mc.theWorld != null
                && ItemUtil.isHoldingSword() && mc.currentScreen == null;
    }

    private boolean canInteractWhileAlwaysUnblocked() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null) {
            return false;
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return BlockUtil.isUseInteractable(hit);
        }
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && hit.entityHit != null) {
            Entity entity = hit.entityHit;
            return !(entity instanceof EntityPlayer) || TeamUtil.isBot((EntityPlayer) entity);
        }
        return false;
    }


    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.syncBlockAnimation();
            return;
        }
        if (isBedBreaking()) {
            ReflectionUtils.setItemInUse(false);
            return;
        }
        if (mc.currentScreen == null || !this.isBlocking && !this.isLagging) {
            this.syncBlockAnimation();
            return;
        }
        this.resetState(true);
    }

    private void syncBlockAnimation() {
        boolean killAuraAttacking = isKillAuraAttacking(this.currentTarget);
        boolean requiredMouseButtonsDown = this.checkConditions(
                Mouse.isButtonDown(LEFT_MOUSE) || killAuraAttacking, Mouse.isButtonDown(RIGHT_MOUSE));
        boolean continuousUndamagedBlock = !this.onlyWhenDamaged.getValue()
                && this.currentTarget != null && !this.allowingAlwaysInteraction;
        boolean shouldAnimate = this.forceBlockAnimation.getValue() && mc.thePlayer != null
                && mc.theWorld != null && mc.currentScreen == null && ItemUtil.isHoldingSword()
                && requiredMouseButtonsDown
                && (continuousUndamagedBlock || this.isBlocking || this.isLagging);
        ReflectionUtils.setItemInUse(shouldAnimate);
    }


    @EventTarget
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead
                || mc.currentScreen != null) {
            this.resetState(true);
            return;
        }
        if (isBedBreaking()) {
            this.resetState(true);
            return;
        }
        int selfHurtTime = mc.thePlayer.hurtTime;
        boolean hurtAgain = selfHurtTime > this.lastSelfHurtTime;
        this.lastSelfHurtTime = selfHurtTime;

        if (!ItemUtil.isHoldingSword()) {
            this.resetState(false);
            return;
        }
        if (this.allowingAlwaysInteraction) {
            this.releaseLag();
            this.stopBlocking(true);
            this.manualBlock = false;
            return;
        }

        int currentTick = this.tickCounter;
        if (!this.isLagMode() && this.isLagging) {
            this.releaseLag();
        }

        double rangeValue = this.range.getValue();
        this.currentTarget =
                CombatTargeting.findTarget(rangeValue * rangeValue, this.ignoreTeammates.getValue());
        boolean killAuraAttacking = isKillAuraAttacking(this.currentTarget);
        boolean rmbDown = Mouse.isButtonDown(RIGHT_MOUSE);
        boolean lmbDown = Mouse.isButtonDown(LEFT_MOUSE) || killAuraAttacking;
        boolean hasTarget = this.currentTarget != null;
        boolean conditionsMet = hasTarget && this.checkConditions(lmbDown, rmbDown);
        boolean leftTargetRange = this.targetWasInRange && !hasTarget;
        this.targetWasInRange = hasTarget;

        int unblockMode = this.unblockMode();
        if (unblockMode != UNBLOCK_ONCE || !rmbDown || hasTarget) {
            this.unblockedAfterLeavingRange = false;
        }

        if (unblockMode == UNBLOCK_ALWAYS && !hasTarget) {
            this.releaseLag();
            this.stopBlocking(true);
            this.manualBlock = false;
            return;
        }
        if (unblockMode == UNBLOCK_ONCE && rmbDown && leftTargetRange) {
            if (this.isLagging) {
                this.releaseLag();
            }
            this.stopBlocking(true);
            this.manualBlock = false;
            this.unblockedAfterLeavingRange = true;
            return;
        }

        if (hurtAgain) {
            this.releaseLag();
            this.stopBlocking(true);
            this.manualBlock = false;
        }

        if (!conditionsMet && rmbDown) {
            if (!this.unblockedAfterLeavingRange) {
                if (this.isLagging) {
                    this.releaseLag();
                }
                if (!this.isBlocking) {
                    this.startBlocking(currentTick);
                }
                this.manualBlock = true;
            }
            return;
        }

        if (this.manualBlock) {
            this.stopBlocking(true);
            this.manualBlock = false;
        }

        if (this.isLagging) {
            int lagMaxTicks = msToTicks(this.lagMaxDuration.getValue());
            boolean lagExpired = lagMaxTicks > 0 && this.lagStartTick >= 0
                    && currentTick - this.lagStartTick >= lagMaxTicks;
            if (lagExpired || !conditionsMet) {
                this.releaseLag();
                if (lagExpired && this.blockAgainImmediately.getValue() && conditionsMet) {
                    this.startBlocking(currentTick);
                }
            }
        }

        if (!conditionsMet) {
            this.stopBlocking(true);
            return;
        }
        if (!this.isBlocking && !this.isLagging && this.shouldPredictiveBlock()) {
            this.startBlocking(currentTick);
        }
        if (!this.isBlocking) {
            return;
        }
        int maxHoldTicks = msToTicks(this.maxHoldMs.getValue());
        boolean timeExpired = maxHoldTicks > 0 && this.blockStartTick >= 0
                && currentTick - this.blockStartTick >= maxHoldTicks;
        if (timeExpired) {
            if (this.shouldStartLag()) {
                this.startLag(currentTick);
            }
            this.stopBlocking(true);
        }
    }

    private boolean checkConditions(boolean lmbDown, boolean rmbDown) {
        if (this.requireLmb.getValue() && !lmbDown) {
            return false;
        }
        return !this.requireRmb.getValue() || rmbDown;
    }

    private boolean shouldPredictiveBlock() {
        int ourHurtTime = mc.thePlayer.hurtTime;
        int triggerTick = (int) Math.round(this.maxHurtTimeMs.getValue() / 50.0);
        triggerTick = Math.max(1, Math.min(10, triggerTick));
        return ourHurtTime == triggerTick
                || !this.onlyWhenDamaged.getValue() && ourHurtTime == 0;
    }


    private void startBlocking(int currentTick) {
        if (!ItemUtil.isHoldingSword() || this.isCooldownActive()) {
            return;
        }
        int keyCode = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBindUtil.setKeyBindState(keyCode, true);
        KeyBindUtil.pressKeyOnce(keyCode);
        this.isBlocking = true;
        this.blockStartTick = currentTick;
        this.syncBlockAnimation();
    }

    private void stopBlocking(boolean forceRelease) {
        if (!this.isBlocking && !forceRelease) {
            return;
        }
        boolean wasBlocking = this.isBlocking;
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        this.isBlocking = false;
        this.blockStartTick = -1;
        if (wasBlocking) {
            this.lastBlockEndTimeMs = System.currentTimeMillis();
        }
        this.syncBlockAnimation();
    }

    private boolean isCooldownActive() {
        double cooldown = this.cooldownMs.getValue();
        return cooldown > 0.0 && this.lastBlockEndTimeMs > 0L
                && System.currentTimeMillis() - this.lastBlockEndTimeMs < cooldown;
    }


    private boolean shouldStartLag() {
        if (!this.isLagMode()) {
            return false;
        }
        double chance = this.lagChance.getValue();
        if (chance <= 0.0) {
            return false;
        }
        if (chance >= 100.0) {
            return true;
        }
        return Math.random() * 100.0 < chance;
    }

    private void startLag(int currentTick) {
        if (this.isLagging) {
            return;
        }
        int lagReferenceTick = this.blockStartTick >= 0 ? this.blockStartTick : currentTick;
        int lagMaxTicks = msToTicks(this.lagMaxDuration.getValue());
        if (lagMaxTicks > 0 && currentTick - lagReferenceTick >= lagMaxTicks) {
            return;
        }
        this.outboundLag =
                new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Myau.lagHandler.requestLag(this.outboundLag);
        this.isLagging = true;
        this.lagStartTick = lagReferenceTick;
        this.syncBlockAnimation();
    }

    private void releaseLag() {
        if (!this.isLagging) {
            return;
        }
        if (this.outboundLag != null) {
            this.outboundLag.getTimeout().forceTimeOut();
            this.outboundLag = null;
        }
        this.isLagging = false;
        this.lagStartTick = -1;
        this.syncBlockAnimation();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.tickCounter++;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        if (isBedBreaking()) {
            this.releaseLag();
            return;
        }
        if (!this.isLagging || !this.preventDelayAttacks.getValue()) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        if (((C02PacketUseEntity) event.getPacket()).getAction()
                != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        this.releaseLag();
        if (this.blockAgainImmediately.getValue() && ItemUtil.isHoldingSword()) {
            this.startBlocking(this.tickCounter);
        }
    }

    public boolean isActive() {
        return this.isEnabled() && (this.isBlocking || this.isLagging);
    }

    private void resetState(boolean releaseUseKey) {
        boolean restorePhysicalUse = this.isBlocking && mc.gameSettings != null
                && mc.gameSettings.keyBindUseItem.isKeyDown() && Mouse.isButtonDown(RIGHT_MOUSE)
                && mc.currentScreen == null;
        this.releaseLag();
        this.stopBlocking(releaseUseKey);
        this.manualBlock = false;
        this.targetWasInRange = false;
        this.unblockedAfterLeavingRange = false;
        this.allowingAlwaysInteraction = false;
        this.lastBlockEndTimeMs = 0L;
        this.currentTarget = null;
        this.lastSelfHurtTime = 0;
        this.syncBlockAnimation();
        if (restorePhysicalUse) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        }
    }
}
