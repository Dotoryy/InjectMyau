package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.FloatModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.LivingUpdateEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.PrePlayerInteractEvent;
import myau.events.RightClickMouseEvent;
import myau.events.StrafeEvent;
import myau.events.UpdateEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.timeout.ModuleBackedTimeout;
import myau.module.Module;
import myau.util.KeyBindUtil;
import myau.access.AccessorEntity;
import myau.access.AccessorPlayerControllerMP;
import myau.util.BlockUtil;
import myau.util.MoveUtil;
import myau.util.MovementTicks;
import myau.util.ItemUtil;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import myau.util.TeamUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.item.ItemBow;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

public class NoSlow extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private int lastSlot = -1;
    private static final int SWORD_GRIM_30 = 2;
    private static final int SWORD_WATCHDOG_LAG = 3;
    private static final int SWORD_MATRIX = 4;
    private static final int ITEM_GRIM_30 = 3;
    private static final int ITEM_MATRIX = 4;
    private static final int WATCHDOG_LAG_START_TICKS = 1;
    private static final float WATCHDOG_ANIMATION_DEFAULT = 150.0F;
    private static final double MATRIX_GROUND_SPEED = 0.0265;
    private static final double MATRIX_SLOW_FACTOR = 0.992;
    private static final double MATRIX_SPEED_FACTOR = 0.99;
    public final ModeProperty swordMode = new ModeProperty("sword-mode", 1, new String[]{"NONE", "VANILLA", "GRIM_30", "WATCHDOG_LAG", "MATRIX"});
    public final PercentProperty swordMotion = new PercentProperty("sword-motion", 100,
            () -> this.swordMode.getValue() != 0 && this.swordMode.getValue() != SWORD_WATCHDOG_LAG
                    && this.swordMode.getValue() != SWORD_MATRIX);
    public final BooleanProperty swordSprint = new BooleanProperty("sword-sprint", true, () -> this.swordMode.getValue() != 0);
    public final FloatProperty watchdogAnimation = new FloatProperty("watchdog-block-animation",
            WATCHDOG_ANIMATION_DEFAULT, 0.0F, 500.0F, 50.0F,
            () -> this.swordMode.getValue() == SWORD_WATCHDOG_LAG);
    public final ModeProperty foodMode = new ModeProperty("food-mode", 0, new String[]{"NONE", "VANILLA", "FLOAT", "GRIM_30", "MATRIX"});
    public final PercentProperty foodMotion = new PercentProperty("food-motion", 100,
            () -> this.foodMode.getValue() != 0 && this.foodMode.getValue() != ITEM_MATRIX);
    public final BooleanProperty foodSprint = new BooleanProperty("food-sprint", true, () -> this.foodMode.getValue() != 0);
    public final ModeProperty bowMode = new ModeProperty("bow-mode", 0, new String[]{"NONE", "VANILLA", "FLOAT", "GRIM_30", "MATRIX"});
    public final PercentProperty bowMotion = new PercentProperty("bow-motion", 100,
            () -> this.bowMode.getValue() != 0 && this.bowMode.getValue() != ITEM_MATRIX);
    public final BooleanProperty bowSprint = new BooleanProperty("bow-sprint", true, () -> this.bowMode.getValue() != 0);
    public final BooleanProperty grimHeypixel = new BooleanProperty("grim-heypixel", false,
            () -> this.isGrim30Selected());
    private static final int GRIM_ROTATION_PRIORITY = 2;
    private LagRequest watchdogLag = null;
    private boolean releasedThisTick = false;
    private long animationEndMs = 0L;
    public NoSlow() {
        super("No Slow", false);
    }
    @Override
    public void onDisabled() {
        this.releaseWatchdogLag();
        this.releasedThisTick = false;
        this.animationEndMs = 0L;
    }

    public boolean isForcingBlockAnimation() {
        if (!this.isEnabled() || this.swordMode.getValue() != SWORD_WATCHDOG_LAG
                || this.animationEndMs == 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= this.animationEndMs) {
            this.animationEndMs = 0L;
            return false;
        }
        return mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null
                && ItemUtil.isHoldingSword();
    }

    @Override
    public String[] getSuffix() {
        if (this.swordMode.getValue() == SWORD_WATCHDOG_LAG) {
            return new String[]{"Watchdog"};
        }
        ModeProperty shown = this.swordMode.getValue() != 0 ? this.swordMode
                : this.foodMode.getValue() != 0 ? this.foodMode
                : this.bowMode.getValue() != 0 ? this.bowMode
                : null;
        if (shown == null) {
            return new String[0];
        }
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, shown.getModeString())};
    }

    public boolean isWatchdogReleaseTick() {
        return this.releasedThisTick;
    }
    public boolean isSwordActive() {
        if (this.swordMode.getValue() == SWORD_WATCHDOG_LAG) {
            return this.watchdogLag != null && ItemUtil.isHoldingSword();
        }
        return this.swordMode.getValue() != 0 && ItemUtil.isHoldingSword();
    }
    public boolean isFoodActive() {
        return this.foodMode.getValue() != 0 && ItemUtil.isEating();
    }
    public boolean isBowActive() {
        return this.bowMode.getValue() != 0 && ItemUtil.isUsingBow();
    }

    public boolean isFloatMode() {
        return this.foodMode.getValue() == 2 && ItemUtil.isEating()
                || this.bowMode.getValue() == 2 && ItemUtil.isUsingBow();
    }
    public boolean isGrim30Selected() {
        return this.swordMode.getValue() == SWORD_GRIM_30
                || this.foodMode.getValue() == ITEM_GRIM_30
                || this.bowMode.getValue() == ITEM_GRIM_30;
    }

    public boolean isGrim30Active() {
        if (!mc.thePlayer.isUsingItem()) {
            return false;
        }
        if (ItemUtil.isHoldingSword()) {
            return this.swordMode.getValue() == SWORD_GRIM_30;
        }
        if (ItemUtil.isEating()) {
            return this.foodMode.getValue() == ITEM_GRIM_30;
        }
        return ItemUtil.isUsingBow() && this.bowMode.getValue() == ITEM_GRIM_30;
    }
    private boolean isGrim30CancelTick() {
        return MovementTicks.ground() == 1
                || MovementTicks.air() % 2 == 0 && !mc.thePlayer.onGround
                || MovementTicks.ground() % 2 == 1 && mc.thePlayer.onGround;
    }
    public boolean isAnyActive() {
        if (!mc.thePlayer.isUsingItem()) {
            return false;
        }
        if (this.isGrim30Active()) {
            return this.isGrim30CancelTick();
        }
        return this.isSwordActive() || this.isFoodActive() || this.isBowActive();
    }
    public boolean canSprint() {
        return this.isGrim30Active()
                || this.isSwordActive() && this.swordSprint.getValue()
                || this.isFoodActive() && this.foodSprint.getValue()
                || this.isBowActive() && this.bowSprint.getValue();
    }
    public int getMotionMultiplier() {
        if (ItemUtil.isHoldingSword()) {
            return this.swordMode.getValue() == SWORD_WATCHDOG_LAG || this.swordMode.getValue() == SWORD_MATRIX
                    ? 100 : this.swordMotion.getValue();
        } else if (ItemUtil.isEating()) {
            return this.foodMode.getValue() == ITEM_MATRIX ? 100 : this.foodMotion.getValue();
        } else if (ItemUtil.isUsingBow()) {
            return this.bowMode.getValue() == ITEM_MATRIX ? 100 : this.bowMotion.getValue();
        } else {
            return 100;
        }
    }

    private boolean isMatrixActive() {
        if (!this.isEnabled() || mc.thePlayer == null || !mc.thePlayer.isUsingItem()) {
            return false;
        }
        if (ItemUtil.isHoldingSword()) {
            return this.swordMode.getValue() == SWORD_MATRIX;
        }
        if (ItemUtil.isEating()) {
            return this.foodMode.getValue() == ITEM_MATRIX;
        }
        return ItemUtil.isUsingBow() && this.bowMode.getValue() == ITEM_MATRIX;
    }

    @EventTarget
    public void onMatrixStrafe(StrafeEvent event) {
        if (!this.isMatrixActive()) {
            return;
        }
        if (MovementTicks.ground() > 1) {
            MoveUtil.strafe(MATRIX_GROUND_SPEED);
            return;
        }
        Speed speed = (Speed) Myau.moduleManager.modules.get(Speed.class);
        double factor = speed != null && speed.isEnabled() ? MATRIX_SPEED_FACTOR : MATRIX_SLOW_FACTOR;
        mc.thePlayer.motionX *= factor;
        mc.thePlayer.motionZ *= factor;
    }
    private boolean isWatchdogLagAllowed() {
        if (!this.isEnabled() || this.swordMode.getValue() != SWORD_WATCHDOG_LAG) {
            return false;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isManagingBlock()) {
            return false;
        }
        Autoblock autoblock = (Autoblock) Myau.moduleManager.modules.get(Autoblock.class);
        return autoblock != null && autoblock.isEnabled();
    }

    @EventTarget(Priority.LOW)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        this.releasedThisTick = false;
        if (!this.isWatchdogLagAllowed() || mc.thePlayer == null || mc.theWorld == null
                || !ItemUtil.isHoldingSword()) {
            this.releaseWatchdogLag();
            return;
        }
        Autoblock autoblock = (Autoblock) Myau.moduleManager.modules.get(Autoblock.class);
        if (!autoblock.isAutoBlocking()) {
            this.releaseWatchdogLag();
            return;
        }
        int blocked = autoblock.getBlockedTicks();
        int hold = autoblock.getHoldTicks();
        if (this.watchdogLag != null) {
            if (blocked >= hold) {
                this.releaseWatchdogLag();
            }
            return;
        }
        if (blocked >= WATCHDOG_LAG_START_TICKS && blocked < hold) {
            this.watchdogLag = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
            Myau.lagHandler.requestLag(this.watchdogLag);
            this.animationEndMs = this.watchdogAnimation.getValue() <= 0.0F
                    ? 0L
                    : System.currentTimeMillis() + (long) this.watchdogAnimation.getValue().floatValue();
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            if (mc.thePlayer.isUsingItem()) {
                AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
                PacketUtil.sendPacket(new C07PacketPlayerDigging(
                        C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
                mc.thePlayer.stopUsingItem();
                this.releasedThisTick = true;
            }
        }
    }

    @EventTarget
    public void onWatchdogUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            this.releasedThisTick = false;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onWatchdogLeftClick(LeftClickMouseEvent event) {
        if (this.releasedThisTick) {
            event.setCancelled(true);
        }
    }

    private void releaseWatchdogLag() {
        if (this.watchdogLag == null) {
            return;
        }
        this.watchdogLag.getTimeout().forceTimeOut();
        this.watchdogLag = null;
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled() && this.isGrim30Active() && mc.thePlayer.moveForward > 0.0F) {
            mc.thePlayer.setSprinting(true);
        }
        if (this.isEnabled() && this.isAnyActive()) {
            float multiplier = (float) this.getMotionMultiplier() / 100.0F;
            mc.thePlayer.movementInput.moveForward *= multiplier;
            mc.thePlayer.movementInput.moveStrafe *= multiplier;
            if (!this.canSprint()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }
    @EventTarget(Priority.LOW)
    public void onGrimPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || !this.isGrim30Active()) {
            return;
        }
        boolean speedActive = Myau.moduleManager.modules.get(Speed.class).isEnabled();
        boolean strafingSideways = mc.gameSettings.keyBindRight.isKeyDown() || mc.gameSettings.keyBindLeft.isKeyDown();
        if (!mc.thePlayer.onGround && !strafingSideways) {
            this.aimSideways();
        }
        if (AccessorEntity.getIsInWeb(mc.thePlayer)) {
            MoveUtil.setSpeed(0.64, MoveUtil.getMoveYaw());
        }
        if (MovementTicks.ground() > 1 && !mc.gameSettings.keyBindJump.isKeyDown()) {
            MoveUtil.addSpeed(speedActive ? 1.0E-4 : 2.0E-4, MoveUtil.getMoveYaw());
            if (!strafingSideways && !(mc.thePlayer.getHeldItem() != null
                    && mc.thePlayer.getHeldItem().getItem() instanceof ItemBow)) {
                this.aimSideways();
            }
        }
    }
    private void aimSideways() {
        Myau.rotationManager.setRotation(
                mc.thePlayer.rotationYaw + 45.0F, mc.thePlayer.rotationPitch, GRIM_ROTATION_PRIORITY, false);
    }
    @EventTarget
    public void onGrimPacket(PacketEvent event) {
        if (!this.isEnabled() || !this.grimHeypixel.getValue() || event.getType() != EventType.SEND) {
            return;
        }
        if (!(event.getPacket() instanceof C0FPacketConfirmTransaction) || !mc.thePlayer.isUsingItem()) {
            return;
        }
        if (ItemUtil.isEating() || ItemUtil.isUsingBow()) {
            event.setCancelled(true);
        }
    }
    @EventTarget(Priority.LOW)
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled() && this.isFloatMode()) {
            int item = mc.thePlayer.inventory.currentItem;
            if (this.lastSlot != item && PlayerUtil.isUsingItem()) {
                this.lastSlot = item;
                Myau.floatManager.setFloatState(true, FloatModules.NO_SLOW);
            }
        } else {
            this.lastSlot = -1;
            Myau.floatManager.setFloatState(false, FloatModules.NO_SLOW);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            if (mc.objectMouseOver != null) {
                switch (mc.objectMouseOver.typeOfHit) {
                    case BLOCK:
                        BlockPos blockPos = mc.objectMouseOver.getBlockPos();
                        if (BlockUtil.isInteractable(blockPos) && !PlayerUtil.isSneaking()) {
                            return;
                        }
                        break;
                    case ENTITY:
                        Entity entityHit = mc.objectMouseOver.entityHit;
                        if (entityHit instanceof EntityVillager) {
                            return;
                        }
                        if (entityHit instanceof EntityLivingBase && TeamUtil.isShop((EntityLivingBase) entityHit)) {
                            return;
                        }
                }
            }
            if (this.isGrim30Selected() && !mc.thePlayer.onGround && MovementTicks.air() % 2 == 1) {
                event.setCancelled(true);
                return;
            }
            if (this.isFloatMode() && !Myau.floatManager.isPredicted() && mc.thePlayer.onGround) {
                event.setCancelled(true);
                mc.thePlayer.motionY = 0.42F;
            }
        }
    }
}
