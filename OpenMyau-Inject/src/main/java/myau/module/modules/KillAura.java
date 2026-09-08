package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.access.AccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.*;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.DataWatcher.WatchableObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class KillAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));
    private final TimerUtil timer = new TimerUtil();
    private AttackData target = null;
    private int switchTick = 0;
    private boolean hitRegistered = false;
    private boolean blockingState = false;
    private boolean isBlocking = false;
    private boolean fakeBlockState = false;
    private boolean blinkReset = false;
    private long attackDelayMS = 0L;
    private int blockTick = 0;
    private int lastTickProcessed;
    private static final int CLICK_NORMAL = 0;
    private static final int CLICK_HIT_SELECT = 1;
    private static final int CLICK_MODERN = 2;
    private static final int CLICK_MODERN_18_ANIMATIONS = 3;
    private static final int BLOCK_DELAY_PASSTHROUGH = 0;
    private static final int BLOCK_DELAY_BYPASS = 1;
    private static final int BLOCK_DELAY_SUPPRESS = 2;
    private static final int VELOCITY_TICKS_CAP = 1000;
    private int ticksSinceVelocity = VELOCITY_TICKS_CAP;
    private static final int AUTOBLOCK_NONE = 0;
    private static final int AUTOBLOCK_WATCHDOG = 9;

    private int watchdogStage;
    private boolean watchdogShort;
    private boolean watchdogBlinkAfterBlock;
    private static final int ROTATION_NONE = 0;
    private static final int ROTATION_LEGIT = 1;
    private static final int ROTATION_SILENT = 2;
    private static final int ROTATION_LOCK = 3;
    private static final int ROTATION_LOCK_ON = 4;
    public final ModeProperty mode;
    public final ModeProperty sort;
    public final ModeProperty autoBlock;
    public final BooleanProperty autoBlockRequirePress;
    public final BooleanProperty watchdogShortCycle;
    public final BooleanProperty watchdogBlockSlowdown;
    public final FloatProperty autoBlockMinCPS;
    public final FloatProperty autoBlockMaxCPS;
    public final FloatProperty autoBlockRange;
    public final FloatProperty swingRange;
    public final FloatProperty attackRange;
    public final IntProperty fov;
    public final IntProperty minCPS;
    public final IntProperty maxCPS;
    public final ModeProperty clickDelayMode;
    public final BooleanProperty randomize19Speed;
    public final FloatProperty randomizeFactor;
    public final IntProperty switchDelay;
    public final ModeProperty rotations;
    public final ModeProperty moveFix;
    public final FloatProperty rotationSpeedMin;
    public final FloatProperty rotationSpeedMax;
    public final PercentProperty multiPointHorizontal;
    public final PercentProperty multiPointVertical;
    public final PercentProperty smoothing;
    public final IntProperty angleStep;
    public final BooleanProperty throughWalls;
    public final BooleanProperty requirePress;
    public final BooleanProperty allowMining;
    public final BooleanProperty weaponsOnly;
    public final BooleanProperty allowTools;
    public final BooleanProperty inventoryCheck;
    public final BooleanProperty players;
    public final BooleanProperty bosses;
    public final BooleanProperty mobs;
    public final BooleanProperty animals;
    public final BooleanProperty golems;
    public final BooleanProperty silverfish;
    public final BooleanProperty teams;
    public final ModeProperty showTarget;
    public final ModeProperty debugLog;
    private boolean isModernClickDelay() {
        return this.clickDelayMode.getValue() == CLICK_MODERN
                || this.clickDelayMode.getValue() == CLICK_MODERN_18_ANIMATIONS;
    }
    private long getAttackDelay() {
        if (this.isModernClickDelay()) {
            return this.getModernAttackDelay();
        }
        return this.isBlocking ? (long) (1000.0F / RandomUtil.nextLong(this.autoBlockMinCPS.getValue().longValue(), this.autoBlockMaxCPS.getValue().longValue())) : 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
    }
    private long getModernAttackDelay() {
        double speed = 4.0;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null) {
            Item item = held.getItem();
            if (item instanceof ItemSword) {
                speed = 1.6;
            } else if (item instanceof ItemSpade) {
                speed = 1.0;
            } else if (item instanceof ItemPickaxe) {
                speed = 1.2;
            } else if (item instanceof ItemAxe) {
                Item.ToolMaterial material = ((ItemTool) item).getToolMaterial();
                if (material == Item.ToolMaterial.WOOD || material == Item.ToolMaterial.STONE) {
                    speed = 0.8;
                } else if (material == Item.ToolMaterial.IRON) {
                    speed = 0.9;
                } else {
                    speed = 1.0;
                }
            } else if (item instanceof ItemHoe) {
                Item.ToolMaterial material = ((ItemTool) item).getToolMaterial();
                if (material == Item.ToolMaterial.WOOD || material == Item.ToolMaterial.GOLD) {
                    speed = 1.0;
                } else if (material == Item.ToolMaterial.STONE) {
                    speed = 2.0;
                } else if (material == Item.ToolMaterial.IRON) {
                    speed = 3.0;
                }
            }
        }
        if (this.randomize19Speed.getValue()) {
            speed -= Math.random() * this.randomizeFactor.getValue();
        }
        if (speed < 0.1) {
            speed = 0.1;
        }
        long delay = (long) ((20.0 / speed - 1.0) * 50.0 - 50.0);
        return delay < 0L ? 0L : delay;
    }

    private void playModernSwingAnimation() {
        EventManager.call(new AttackEvent(this.target.getEntity()));
        if (!mc.thePlayer.isSwingInProgress
                || mc.thePlayer.swingProgressInt >= getArmSwingAnimationEnd() / 2
                || mc.thePlayer.swingProgressInt < 0) {
            mc.thePlayer.swingProgressInt = -1;
            mc.thePlayer.isSwingInProgress = true;
        }
        if (mc.thePlayer.fallDistance > 0.0F) {
            mc.thePlayer.onCriticalHit(this.target.getEntity());
        }
    }
    private static int getArmSwingAnimationEnd() {
        if (mc.thePlayer.isPotionActive(Potion.digSpeed)) {
            return 6 - (1 + mc.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier());
        }
        if (mc.thePlayer.isPotionActive(Potion.digSlowdown)) {
            return 6 + (1 + mc.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) * 2;
        }
        return 6;
    }
    private boolean attackReduceOverride() {
        Velocity velocity = (Velocity) Myau.moduleManager.modules.get(Velocity.class);
        return velocity != null && velocity.isAttackReduceActive();
    }
    private boolean isHitSelectSatisfied() {
        if (this.clickDelayMode.getValue() != CLICK_HIT_SELECT || this.attackReduceOverride()) {
            return true;
        }
        long window = Math.max(0L, this.getPing() / 50L - 1L);
        return this.target.getEntity().hurtTime <= window
                || this.ticksSinceVelocity <= 11;
    }
    private static final double SPRINT_STOP_RANGE_SLACK = 0.5;
    private boolean isSprintStopPending() {
        KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null || this.target == null) {
            return false;
        }

        if (RotationUtil.distanceToBox(this.target.getBox())
                > this.attackRange.getValue() + SPRINT_STOP_RANGE_SLACK) {
            return false;
        }
        return keepSprint.shouldDeferAttack(this.target.getEntity());
    }

    private long getPing() {
        return ServerPing.getPing();
    }
    private boolean attackGates = false;
    private int attackGatesTick = Integer.MIN_VALUE;
    private boolean attackGatesPassed() {
        int tick = mc.thePlayer == null ? Integer.MIN_VALUE : mc.thePlayer.ticksExisted;
        if (tick != this.attackGatesTick) {
            this.attackGatesTick = tick;
            this.attackGates = this.computeAttackGates();
        }
        return this.attackGates;
    }
    private boolean computeAttackGates() {
        if (Myau.playerStateManager.digging || Myau.playerStateManager.placing) {
            return false;
        }
        if (this.isPlayerBlocking() && this.autoBlock.getValue() != 1) {
            return false;
        }
        int adjusted = this.adjustAttackDelay();
        if (adjusted == BLOCK_DELAY_SUPPRESS) {
            return false;
        }
        if (adjusted != BLOCK_DELAY_BYPASS && this.attackDelayMS > 0L) {
            return false;
        }
        return this.isHitSelectSatisfied() && !this.blockedByHitSelect();
    }
    private int adjustAttackDelay() {
        if (this.autoBlock.getValue() != AUTOBLOCK_WATCHDOG) {
            return BLOCK_DELAY_PASSTHROUGH;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && held.getItem() instanceof ItemSword) {
            boolean allowed = this.watchdogStage == 1 && Math.random() > 0.2
                    || this.watchdogStage == 2
                    || this.autoBlockRequirePress.getValue() && !PlayerUtil.isUsingItem();
            return allowed ? BLOCK_DELAY_BYPASS : BLOCK_DELAY_SUPPRESS;
        }
        if (this.target != null && held != null) {
            return BLOCK_DELAY_BYPASS;
        }
        return BLOCK_DELAY_PASSTHROUGH;
    }
    private int lastAttackTick = -1;
    private boolean performAttack(float yaw, float pitch) {
        if (mc.thePlayer != null && this.lastAttackTick == mc.thePlayer.ticksExisted) {
            return false;
        }
        if (this.attackGatesPassed()) {
            if (this.isSprintStopPending()) {
                return false;
            } else {
                this.attackDelayMS = this.attackDelayMS + this.getAttackDelay();
                mc.thePlayer.swingItem();
                if ((this.rotations.getValue() != ROTATION_NONE || !this.isBoxInAttackRange(this.target.getBox()))
                        && RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, this.attackRange.getValue()) == null) {
                    return false;
                } else {
                    AttackEvent event = new AttackEvent(this.target.getEntity());
                    EventManager.call(event);
                    if (event.isCancelled()) {
                        return false;
                    }
                    KeepSprint keepSprint =
                            (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
                    if (keepSprint != null) {
                        keepSprint.confirmAttack();
                    }
                    AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
                    PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.ATTACK));
                    if (mc.playerController.getCurrentGameType() != GameType.SPECTATOR) {
                        PlayerUtil.attackEntity(this.target.getEntity());
                    }
                    this.hitRegistered = true;
                    this.lastAttackTick = mc.thePlayer.ticksExisted;
                    this.tellHitSelect();
                    return true;
                }
            }
        } else {
            return false;
        }
    }
    private boolean blockedByHitSelect() {
        if (this.attackReduceOverride()) {
            return false;
        }
        HitSelect hitSelect = (HitSelect) Myau.moduleManager.modules.get(HitSelect.class);
        return hitSelect != null
                && hitSelect.isEnabled()
                && hitSelect.shouldBlockAttack(this.target.getEntity());
    }
    private void tellHitSelect() {
        HitSelect hitSelect = (HitSelect) Myau.moduleManager.modules.get(HitSelect.class);
        if (hitSelect != null && hitSelect.isEnabled()) {
            hitSelect.confirmHit(this.target.getEntity());
        }
    }
    private void sendUseItem() {
        AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
        this.startBlock(mc.thePlayer.getHeldItem());
    }
    private void startBlock(ItemStack itemStack) {
        PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(itemStack));
        mc.thePlayer.setItemInUse(itemStack, itemStack.getMaxItemUseDuration());
        this.blockingState = true;
    }
    private boolean watchdogCycle() {
        if (AccessorPlayerControllerMP.getIsHittingBlock(mc.playerController)
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            this.watchdogStage = 0;
        }
        this.watchdogStage++;
        if (this.watchdogStage >= (this.watchdogShort ? 3 : 4)) {
            this.watchdogStage = 1;
        }
        boolean sendBlock = false;
        this.watchdogBlinkAfterBlock = false;
        switch (this.watchdogStage) {
            case 1:
                this.watchdogShort = this.watchdogShortCycle.getValue();
                if (this.watchdogShort) {
                    sendBlock = this.watchdogBlock();
                } else if (!this.watchdogBlockSlowdown.getValue()) {
                    this.watchdogFlickSlot();
                    this.stopBlock();
                }
                this.watchdogBlinkAfterBlock = true;
                break;
            case 2:
                if (this.watchdogShort) {
                    this.watchdogTearDown();
                } else {
                    sendBlock = this.watchdogBlock();
                    this.watchdogBlinkAfterBlock = true;
                }
                break;
            case 3:
                this.watchdogTearDown();
                break;
            default:
                break;
        }
        return sendBlock;
    }

    private boolean watchdogBlock() {
        this.fakeBlockState = false;
        return !this.isPlayerBlocking();
    }
    private void watchdogTearDown() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        if (!this.watchdogBlockSlowdown.getValue()) {
            this.watchdogFlickSlot();
        }
        this.stopBlock();
        this.fakeBlockState = true;
    }
    private void watchdogFlickSlot() {
        int current = AccessorPlayerControllerMP.getCurrentPlayerItem(mc.playerController);
        int other = ThreadLocalRandom.current().nextInt(9);
        while (other == current) {
            other = ThreadLocalRandom.current().nextInt(9);
        }
        PacketUtil.sendPacket(new C09PacketHeldItemChange(other));
        PacketUtil.sendPacket(new C09PacketHeldItemChange(current));
    }

    private void stopBlock() {
        PacketUtil.sendPacket(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
        this.blockingState = false;
    }
    private float[] computeRotations(UpdateEvent event) {
        float[] target = RotationUtil.getRotationsToBox(
                this.target.getBox(),
                event.getYaw(),
                event.getPitch(),
                this.rotations.getValue() == ROTATION_LOCK
                        ? (float) this.angleStep.getValue() + RandomUtil.nextFloat(-5.0F, 5.0F)
                        : 180.0F,
                this.rotations.getValue() == ROTATION_LOCK
                        ? (float) this.smoothing.getValue() / 100.0F
                        : 0.0F,
                (float) this.multiPointHorizontal.getValue(),
                (float) this.multiPointVertical.getValue()
        );
        if (this.rotations.getValue() == ROTATION_LOCK
                || this.rotations.getValue() == ROTATION_LOCK_ON) {
            return target;
        }
        double speed = RandomUtil.nextFloat(
                Math.min(this.rotationSpeedMin.getValue(), this.rotationSpeedMax.getValue()),
                Math.max(this.rotationSpeedMin.getValue(), this.rotationSpeedMax.getValue())
        );
        return RiseRotation.step(target[0], target[1], speed,
                (yaw, pitch) -> RotationUtil.rayTrace(
                        this.target.getBox(), yaw, pitch, this.attackRange.getValue()) != null);
    }
    private void interactAttack(float yaw, float pitch) {
        if (this.target != null) {
            MovingObjectPosition mop = RotationUtil.rayTrace(this.target.getBox(), yaw, pitch, 8.0);
            if (mop != null) {
                AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
                PacketUtil.sendPacket(
                        new C02PacketUseEntity(
                                this.target.getEntity(),
                                new Vec3(mop.hitVec.xCoord - this.target.getX(), mop.hitVec.yCoord - this.target.getY(), mop.hitVec.zCoord - this.target.getZ())
                        )
                );
                PacketUtil.sendPacket(new C02PacketUseEntity(this.target.getEntity(), Action.INTERACT));
                PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
                mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                this.blockingState = true;
            }
        }
    }

    private static boolean isBlockingIn() {
        BlockIn blockIn = (BlockIn) Myau.moduleManager.modules.get(BlockIn.class);
        return blockIn != null && blockIn.isEnabled() && blockIn.isPlacing();
    }
    private boolean canAttack() {
        if (this.inventoryCheck.getValue() && mc.currentScreen instanceof GuiContainer) {
            return false;
        } else if (!(Boolean) this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            if (AccessorPlayerControllerMP.getIsHittingBlock(mc.playerController)) {
                return false;
            } else if ((ItemUtil.isEating() || ItemUtil.isUsingBow()) && PlayerUtil.isUsingItem()) {
                return false;
            } else {
                AutoHeal autoHeal = (AutoHeal) Myau.moduleManager.modules.get(AutoHeal.class);
                if (autoHeal.isEnabled() && autoHeal.isSwitching()) {
                    return false;
                } else {
                    BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
                    if (bedNuker.isEnabled() && bedNuker.isReady()) {
                        return false;
                    } else if (Myau.moduleManager.modules.get(Scaffold.class).isEnabled()) {
                        return false;
                    } else if (isBlockingIn()) {
                        return false;
                    } else if (this.requirePress.getValue()) {
                        return PlayerUtil.isAttacking();
                    } else {
                        return !this.allowMining.getValue() || !mc.objectMouseOver.typeOfHit.equals(MovingObjectType.BLOCK) || !PlayerUtil.isAttacking();
                    }
                }
            }
        } else {
            return false;
        }
    }
    private boolean canAutoBlock() {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        } else {
            return !this.autoBlockRequirePress.getValue() || PlayerUtil.isUsingItem();
        }
    }
    private boolean hasValidTarget() {
        return mc.theWorld
                .loadedEntityList
                .stream()
                .anyMatch(
                        entity -> entity instanceof EntityLivingBase
                                && this.isValidTarget((EntityLivingBase) entity)
                                && this.isInBlockRange((EntityLivingBase) entity)
                );
    }
    private boolean isValidTarget(EntityLivingBase entityLivingBase) {
        if (!mc.theWorld.loadedEntityList.contains(entityLivingBase)) {
            return false;
        } else if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.thePlayer.ridingEntity) {
            if (entityLivingBase == mc.getRenderViewEntity() || entityLivingBase == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityLivingBase.deathTime > 0) {
                return false;
            } else if (RotationUtil.angleToEntity(entityLivingBase) > this.fov.getValue().floatValue()) {
                return false;
            } else if (!this.throughWalls.getValue() && RotationUtil.rayTrace(entityLivingBase) != null) {
                return false;
            } else if (entityLivingBase instanceof EntityOtherPlayerMP) {
                if (!this.players.getValue()) {
                    return false;
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return false;
                } else {

                    return (!this.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entityLivingBase))
                            && !TeamUtil.isBot((EntityPlayer) entityLivingBase);
                }
            } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
                return this.bosses.getValue();
            } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
                if (entityLivingBase instanceof EntityAnimal
                        || entityLivingBase instanceof EntityBat
                        || entityLivingBase instanceof EntitySquid
                        || entityLivingBase instanceof EntityVillager) {
                    return this.animals.getValue();
                } else if (!(entityLivingBase instanceof EntityIronGolem)) {
                    return false;
                } else {
                    return this.golems.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
                }
            } else if (!(entityLivingBase instanceof EntitySilverfish)) {
                return this.mobs.getValue();
            } else {
                return this.silverfish.getValue() && (!this.teams.getValue() || !TeamUtil.hasTeamColor(entityLivingBase));
            }
        } else {
            return false;
        }
    }
    private boolean isInRange(EntityLivingBase entityLivingBase) {
        return this.isInBlockRange(entityLivingBase) || this.isInSwingRange(entityLivingBase) || this.isInAttackRange(entityLivingBase);
    }
    private boolean isInBlockRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.autoBlockRange.getValue();
    }
    private boolean isInSwingRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.swingRange.getValue();
    }
    private boolean isBoxInSwingRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.swingRange.getValue();
    }
    private boolean isInAttackRange(EntityLivingBase entityLivingBase) {
        return RotationUtil.distanceToEntity(entityLivingBase) <= (double) this.attackRange.getValue();
    }
    private boolean isBoxInAttackRange(AxisAlignedBB axisAlignedBB) {
        return RotationUtil.distanceToBox(axisAlignedBB) <= (double) this.attackRange.getValue();
    }
    private boolean isPlayerTarget(EntityLivingBase entityLivingBase) {
        return entityLivingBase instanceof EntityPlayer && TeamUtil.isTarget((EntityPlayer) entityLivingBase);
    }
    private int findEmptySlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot && mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null && !stack.hasDisplayName()) {
                    return i;
                }
            }
        }
        return Math.floorMod(currentSlot - 1, 9);
    }
    private int findSwordSlot(int currentSlot) {
        for (int i = 0; i < 9; i++) {
            if (i != currentSlot) {
                ItemStack item = mc.thePlayer.inventory.getStackInSlot(i);
                if (item != null && item.getItem() instanceof ItemSword) {
                    return i;
                }
            }
        }
        return -1;
    }

    public KillAura() {
        super("Kill Aura", false);
        this.lastTickProcessed = 0;
        this.mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "SWITCH"});
        this.sort = new ModeProperty("sort", 0, new String[]{"DISTANCE", "HEALTH", "HURT_TIME", "FOV"});
        this.autoBlock = new ModeProperty(
                "auto-block", 2,
                new String[]{"NONE", "VANILLA", "SPOOF", "HYPIXEL", "BLINK", "INTERACT", "SWAP", "LEGIT", "FAKE", "WATCHDOG"}
        );
        this.watchdogShortCycle = new BooleanProperty("watchdog-short-cycle", true,
                () -> this.autoBlock.getValue() == AUTOBLOCK_WATCHDOG);
        this.watchdogBlockSlowdown = new BooleanProperty("watchdog-block-slowdown", false,
                () -> this.autoBlock.getValue() == AUTOBLOCK_WATCHDOG);
        this.autoBlockRequirePress = new BooleanProperty("auto-block-require-press", false);
        this.autoBlockMinCPS = new FloatProperty("auto-block-min-aps", 8.0F, 1.0F, 20.0F);
        this.autoBlockMaxCPS = new FloatProperty("auto-block-max-aps", 10.0F, 1.0F, 20.0F);
        this.autoBlockRange = new FloatProperty("auto-block-range", 6.0F, 3.0F, 8.0F);
        this.swingRange = new FloatProperty("swing-range", 3.5F, 3.0F, 6.0F);
        this.attackRange = new FloatProperty("attack-range", 3.0F, 3.0F, 6.0F);
        this.fov = new IntProperty("fov", 360, 30, 360);
        this.minCPS = new IntProperty("min-aps", 14, 1, 20);
        this.maxCPS = new IntProperty("max-aps", 14, 1, 20);
        this.clickDelayMode = new ModeProperty("click-delay-mode", CLICK_NORMAL,
                new String[]{"NORMAL", "HIT_SELECT", "1.9+", "1.9+_WITH_1.8_ANIMATIONS"});
        this.randomize19Speed = new BooleanProperty("randomize-1.9-speed", false,
                this::isModernClickDelay);
        this.randomizeFactor = new FloatProperty("randomize-factor", 0.2F, 0.05F, 1.0F,
                () -> this.randomize19Speed.getValue() && this.isModernClickDelay());
        this.switchDelay = new IntProperty("switch-delay", 150, 0, 1000);
        this.rotations = new ModeProperty("rotations", ROTATION_SILENT,
                new String[]{"NONE", "LEGIT", "SILENT", "LOCK", "LOCK-ON"});
        this.moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT"});
        this.rotationSpeedMin = new FloatProperty("rotation-speed-min", 5.0F, 0.0F, 10.0F,
                () -> this.rotations.getValue() == ROTATION_SILENT);
        this.rotationSpeedMax = new FloatProperty("rotation-speed-max", 10.0F, 0.0F, 10.0F,
                () -> this.rotations.getValue() == ROTATION_SILENT);
        this.smoothing = new PercentProperty("smoothing", 0, () -> this.rotations.getValue() == ROTATION_LOCK);
        this.multiPointHorizontal = new PercentProperty("multipoint-horizontal", 0,
                () -> this.rotations.getValue() != ROTATION_NONE);
        this.multiPointVertical = new PercentProperty("multipoint-vertical", 0,
                () -> this.rotations.getValue() != ROTATION_NONE);
        this.angleStep = new IntProperty("angle-step", 90, 30, 180,
                () -> this.rotations.getValue() == ROTATION_LOCK);
        this.throughWalls = new BooleanProperty("through-walls", true);
        this.requirePress = new BooleanProperty("require-press", false);
        this.allowMining = new BooleanProperty("allow-mining", true);
        this.weaponsOnly = new BooleanProperty("weapons-only", true);
        this.allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
        this.inventoryCheck = new BooleanProperty("inventory-check", true);
        this.players = new BooleanProperty("players", true);
        this.bosses = new BooleanProperty("bosses", false);
        this.mobs = new BooleanProperty("mobs", false);
        this.animals = new BooleanProperty("animals", false);
        this.golems = new BooleanProperty("golems", false);
        this.silverfish = new BooleanProperty("silverfish", false);
        this.teams = new BooleanProperty("teams", true);
        this.showTarget = new ModeProperty("show-target", 0, new String[]{"NONE", "DEFAULT", "HUD"});
        this.debugLog = new ModeProperty("debug-log", 0, new String[]{"NONE", "HEALTH"});
    }
    public EntityLivingBase getTarget() {
        return this.target != null ? this.target.getEntity() : null;
    }
    public boolean isAttackAllowed() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (!this.weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
            return !this.requirePress.getValue() || KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        } else {
            return false;
        }
    }
    public boolean shouldAutoBlock() {
        if (this.isPlayerBlocking() && this.isBlocking) {
            return !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava() && (this.autoBlock.getValue() == 3
                    || this.autoBlock.getValue() == 4
                    || this.autoBlock.getValue() == 5
                    || this.autoBlock.getValue() == 6
                    || this.autoBlock.getValue() == 7
                    || this.autoBlock.getValue() == AUTOBLOCK_WATCHDOG);
        } else {
            return false;
        }
    }
    public boolean isBlocking() {
        return this.fakeBlockState && ItemUtil.isHoldingSword();
    }
    public boolean isPlayerBlocking() {
        return (mc.thePlayer.isUsingItem() || this.blockingState) && ItemUtil.isHoldingSword();
    }
    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST && this.blinkReset) {
            this.blinkReset = false;
            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
            Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
        }
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.attackDelayMS > 0L) {
                this.attackDelayMS -= 50L;
            }
            boolean attack = this.target != null && this.canAttack();
            boolean block = attack && this.canAutoBlock();
            if (!block) {
                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                this.isBlocking = false;
                this.fakeBlockState = false;
                this.blockTick = 0;
            }
            if (attack) {
                boolean swap = false;
                boolean blocked = false;
                if (block) {
                    switch (this.autoBlock.getValue()) {
                        case 0:
                            if (PlayerUtil.isUsingItem()) {
                                this.isBlocking = true;
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                            } else {
                                this.isBlocking = false;
                                if (this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    this.stopBlock();
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.fakeBlockState = false;
                            break;
                        case 1:
                            if (this.hasValidTarget()) {
                                if (!this.isPlayerBlocking() && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    swap = true;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 2:
                            if (this.hasValidTarget()) {
                                int item = AccessorPlayerControllerMP.getCurrentPlayerItem(mc.playerController);
                                if (Myau.playerStateManager.digging
                                        || Myau.playerStateManager.placing
                                        || mc.thePlayer.inventory.currentItem != item
                                        || this.isPlayerBlocking() && this.blockTick != 0
                                        || this.attackDelayMS > 0L && this.attackDelayMS <= 50L) {
                                    this.blockTick = 0;
                                } else {
                                    int slot = this.findEmptySlot(item);
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(item));
                                    swap = true;
                                    this.blockTick = 1;
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 3:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            blocked = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                if(Myau.moduleManager.modules.get(NoSlow.class).isEnabled()){
                                                    int randomSlot = new Random().nextInt(9);
                                                    while (randomSlot == mc.thePlayer.inventory.currentItem) {
                                                        randomSlot = new Random().nextInt(9);
                                                    }
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(randomSlot));
                                                    PacketUtil.sendPacket(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                                }
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 4:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 5:
                            if (this.hasValidTarget()) {
                                int item = AccessorPlayerControllerMP.getCurrentPlayerItem(mc.playerController);
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blinkReset = true;
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                int slot = this.findEmptySlot(item);
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
                                                AccessorPlayerControllerMP.setCurrentPlayerItem(mc.playerController, slot);
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                this.isBlocking = true;
                                this.fakeBlockState = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 6:
                            if (this.hasValidTarget()) {
                                int item = AccessorPlayerControllerMP.getCurrentPlayerItem(mc.playerController);
                                if (mc.thePlayer.inventory.currentItem == item && !Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            int slot = this.findSwordSlot(item);
                                            if (slot != -1) {
                                                if (!this.isPlayerBlocking()) {
                                                    swap = true;
                                                }
                                                this.blockTick = 1;
                                            }
                                            break;
                                        case 1:
                                            int swordsSlot = this.findSwordSlot(item);
                                            if (swordsSlot == -1) {
                                                this.blockTick = 0;
                                            } else if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            } else if (this.attackDelayMS <= 50L) {
                                                PacketUtil.sendPacket(new C09PacketHeldItemChange(swordsSlot));
                                                AccessorPlayerControllerMP.setCurrentPlayerItem(mc.playerController, swordsSlot);
                                                this.startBlock(mc.thePlayer.inventory.getStackInSlot(swordsSlot));
                                                attack = false;
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                    this.isBlocking = true;
                                    this.fakeBlockState = true;
                                    break;
                                }
                            }
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            break;
                        case 7:
                            if (this.hasValidTarget()) {
                                if (!Myau.playerStateManager.digging && !Myau.playerStateManager.placing) {
                                    switch (this.blockTick) {
                                        case 0:
                                            if (!this.isPlayerBlocking()) {
                                                swap = true;
                                            }
                                            this.blockTick = 1;
                                            break;
                                        case 1:
                                            if (this.isPlayerBlocking()) {
                                                this.stopBlock();
                                                attack = false;
                                            }
                                            if (this.attackDelayMS <= 50L) {
                                                this.blockTick = 0;
                                            }
                                            break;
                                        default:
                                            this.blockTick = 0;
                                    }
                                }
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = true;
                                this.fakeBlockState = false;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case AUTOBLOCK_WATCHDOG:
                            if (this.hasValidTarget()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = this.watchdogCycle();
                                blocked = this.watchdogBlinkAfterBlock;
                                this.isBlocking = true;
                            } else {
                                Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                                this.watchdogStage = 0;
                                this.isBlocking = false;
                                this.fakeBlockState = false;
                            }
                            break;
                        case 8:
                            Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                            this.isBlocking = false;
                            this.fakeBlockState = this.hasValidTarget();
                            if (PlayerUtil.isUsingItem()
                                    && !this.isPlayerBlocking()
                                    && !Myau.playerStateManager.digging
                                    && !Myau.playerStateManager.placing) {
                                swap = true;
                            }
                    }
                }
                boolean attacked = false;
                if (this.isBoxInSwingRange(this.target.getBox())) {
                    if (this.rotations.getValue() >= ROTATION_SILENT) {
                        float[] rotations = this.computeRotations(event);
                        event.setRotation(rotations[0], rotations[1], 1);
                        if (this.rotations.getValue() == ROTATION_LOCK
                                || this.rotations.getValue() == ROTATION_LOCK_ON) {
                            Myau.rotationManager.setRotation(rotations[0], rotations[1], 1, true);
                        }
                        if (this.moveFix.getValue() != 0 || this.rotations.getValue() == ROTATION_LOCK
                                || this.rotations.getValue() == ROTATION_LOCK_ON) {
                            event.setPervRotation(rotations[0], 1);
                        }
                    }
                    boolean deferred = attack && this.attackGatesPassed() && this.isSprintStopPending();
                    if (!deferred) {
                        if (this.clickDelayMode.getValue() == CLICK_MODERN_18_ANIMATIONS
                                && Math.random() > 0.2) {
                            this.playModernSwingAnimation();
                        }
                        if (attack) {
                            attacked = this.performAttack(event.getNewYaw(), event.getNewPitch());
                        }
                    }
                }
                if (swap) {
                    if (attacked) {
                        this.interactAttack(event.getNewYaw(), event.getNewPitch());
                    } else {
                        this.sendUseItem();
                    }
                }
                if (blocked) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    Myau.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK);
                }
            }
        }
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled()) {
            switch (event.getType()) {
                case PRE:
                    if (this.ticksSinceVelocity < VELOCITY_TICKS_CAP) {
                        this.ticksSinceVelocity++;
                    }
                    if (this.target == null
                            || !this.isValidTarget(this.target.getEntity())
                            || !this.isBoxInAttackRange(this.target.getBox())
                            || !this.isBoxInSwingRange(this.target.getBox())
                            || this.timer.hasTimeElapsed(this.switchDelay.getValue().longValue())) {
                        this.timer.reset();
                        ArrayList<EntityLivingBase> targets = new ArrayList<>();
                        for (Entity entity : mc.theWorld.loadedEntityList) {
                            if (entity instanceof EntityLivingBase
                                    && this.isValidTarget((EntityLivingBase) entity)
                                    && this.isInRange((EntityLivingBase) entity)) {
                                targets.add((EntityLivingBase) entity);
                            }
                        }
                        if (targets.isEmpty()) {
                            this.target = null;
                        } else {
                            if (targets.stream().anyMatch(this::isInSwingRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInSwingRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isInAttackRange)) {
                                targets.removeIf(entityLivingBase -> !this.isInAttackRange(entityLivingBase));
                            }
                            if (targets.stream().anyMatch(this::isPlayerTarget)) {
                                targets.removeIf(entityLivingBase -> !this.isPlayerTarget(entityLivingBase));
                            }
                            targets.sort(
                                    (entityLivingBase1, entityLivingBase2) -> {
                                        int sortBase = 0;
                                        switch (this.sort.getValue()) {
                                            case 1:
                                                sortBase = Float.compare(TeamUtil.getHealthScore(entityLivingBase1), TeamUtil.getHealthScore(entityLivingBase2));
                                                break;
                                            case 2:
                                                sortBase = Integer.compare(entityLivingBase1.hurtResistantTime, entityLivingBase2.hurtResistantTime);
                                                break;
                                            case 3:
                                                sortBase = Float.compare(
                                                        RotationUtil.angleToEntity(entityLivingBase1),
                                                        RotationUtil.angleToEntity(entityLivingBase2)
                                                );
                                        }
                                        return sortBase != 0
                                                ? sortBase
                                                : Double.compare(RotationUtil.distanceToEntity(entityLivingBase1), RotationUtil.distanceToEntity(entityLivingBase2));
                                    }
                            );
                            if (this.mode.getValue() == 1 && this.hitRegistered) {
                                this.hitRegistered = false;
                                this.switchTick++;
                            }
                            if (this.mode.getValue() == 0 || this.switchTick >= targets.size()) {
                                this.switchTick = 0;
                            }
                            this.target = new AttackData(targets.get(this.switchTick));
                        }
                    }
                    if (this.target != null) {
                        this.target = new AttackData(this.target.getEntity());
                    }
                    break;
                case POST:
                    if (this.isPlayerBlocking() && !mc.thePlayer.isBlocking()) {
                        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), mc.thePlayer.getHeldItem().getMaxItemUseDuration());
                    }
            }
        }
    }
    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && !event.isCancelled() && mc.thePlayer != null && mc.theWorld != null) {
            if (event.getPacket() instanceof C07PacketPlayerDigging) {
                C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
                if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                    this.blockingState = false;
                }
            }
            if (event.getPacket() instanceof S12PacketEntityVelocity
                    && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
                this.ticksSinceVelocity = 0;
            }
            if (event.getPacket() instanceof C09PacketHeldItemChange) {
                this.blockingState = false;
                if (this.isBlocking) {
                    mc.thePlayer.stopUsingItem();
                }
            }
            if (this.debugLog.getValue() == 1 && this.isAttackAllowed()) {
                if (event.getPacket() instanceof S06PacketUpdateHealth) {
                    float packet = ((S06PacketUpdateHealth) event.getPacket()).getHealth() - mc.thePlayer.getHealth();
                    if (packet != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                        this.lastTickProcessed = mc.thePlayer.ticksExisted;
                        ChatUtil.sendFormatted(
                                String.format(
                                        "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                        Myau.clientName,
                                        packet > 0.0F ? "&a" : "&c",
                                        df.format(packet),
                                        mc.thePlayer.ticksExisted
                                )
                        );
                    }
                }
                if (event.getPacket() instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) event.getPacket();
                    if (packet.getEntityId() == mc.thePlayer.getEntityId()) {
                        for (WatchableObject watchableObject : packet.func_149376_c()) {
                            if (watchableObject.getDataValueId() == 6) {
                                float diff = (Float) watchableObject.getObject() - mc.thePlayer.getHealth();
                                if (diff != 0.0F && this.lastTickProcessed != mc.thePlayer.ticksExisted) {
                                    this.lastTickProcessed = mc.thePlayer.ticksExisted;
                                    ChatUtil.sendFormatted(
                                            String.format(
                                                    "%sHealth: %s&l%s&r (&otick: %d&r)&r",
                                                    Myau.clientName,
                                                    diff > 0.0F ? "&a" : "&c",
                                                    df.format(diff),
                                                    mc.thePlayer.ticksExisted
                                            )
                                    );
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.shouldAutoBlock()) {
                mc.thePlayer.movementInput.jump = false;
            }
        }
    }
    @EventTarget
    public void onRender(Render3DEvent event) {
        if (this.isEnabled() && target != null) {
            if (this.showTarget.getValue() != 0
                    && TeamUtil.isEntityLoaded(this.target.getEntity())
                    && this.isAttackAllowed()) {
                Color color = new Color(-1);
                switch (this.showTarget.getValue()) {
                    case 1:
                        if (this.target.getEntity().hurtTime > 0) {
                            color = new Color(16733525);
                        } else {
                            color = new Color(5635925);
                        }
                        break;
                    case 2:
                        color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
                }
                RenderUtil.enableRenderState();
                RenderUtil.drawEntityBox(this.target.getEntity(), color.getRed(), color.getGreen(), color.getBlue());
                RenderUtil.disableRenderState();
            }
        }
    }
    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        } else {
            if (this.isEnabled() && this.target != null && this.canAttack()) {
                event.setCancelled(true);
            }
        }
    }
    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!ItemUtil.isHoldingSword()) {
            return;
        }
        if (this.isBlocking) {
            event.setCancelled(true);
        } else if (this.isEnabled() && this.isManagingBlock() && this.target != null
                && this.canAttack()) {
            event.setCancelled(true);
        }
    }

    private boolean isManagingBlock() {
        return this.autoBlock.getValue() != AUTOBLOCK_NONE;
    }
    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (!ItemUtil.isHoldingSword()) {
            return;
        }
        if (this.isBlocking) {
            event.setCancelled(true);
        } else if (this.isEnabled() && this.target != null && this.canAttack()) {
            event.setCancelled(true);
        }
    }
    @EventTarget
    public void onCancelUse(CancelUseEvent event) {
        if (this.isBlocking) {
            event.setCancelled(true);
        }
    }
    @Override
    public void onEnabled() {
        this.target = null;
        this.switchTick = 0;
        this.hitRegistered = false;
        this.attackDelayMS = 0L;
        this.blockTick = 0;
        this.watchdogStage = 0;
        this.watchdogShort = this.watchdogShortCycle.getValue();
        this.ticksSinceVelocity = VELOCITY_TICKS_CAP;
        RiseRotation.reset();
    }
    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
        this.blockingState = false;
        this.isBlocking = false;
        this.fakeBlockState = false;
        this.watchdogStage = 0;
        RiseRotation.reset();
    }
    @Override
    public void verifyValue(String value) {
        boolean badCps = this.autoBlock.getValue() == 2
                || this.autoBlock.getValue() == 3
                || this.autoBlock.getValue() == 4
                || this.autoBlock.getValue() == 5
                || this.autoBlock.getValue() == 6
                || this.autoBlock.getValue() == 7
                || this.autoBlock.getValue() == AUTOBLOCK_WATCHDOG;
        if (!this.autoBlock.getName().equals(value)) {
            if (this.swingRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.attackRange.setValue(this.swingRange.getValue());
                }
            } else if (this.attackRange.getName().equals(value)) {
                if (this.swingRange.getValue() < this.attackRange.getValue()) {
                    this.swingRange.setValue(this.attackRange.getValue());
                }
            } else if (this.minCPS.getName().equals(value)) {
                if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.maxCPS.setValue(this.minCPS.getValue());
                }
            } else if (this.autoBlockMinCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMaxCPS.setValue(this.autoBlockMinCPS.getValue());
                }
                if(autoBlockMinCPS.getValue() > 10.0F && badCps){
                    autoBlockMinCPS.setValue(10.0F);
                }
            } else if (this.autoBlockMaxCPS.getName().equals(value)) {
                if (this.autoBlockMinCPS.getValue() > this.autoBlockMaxCPS.getValue()) {
                    this.autoBlockMinCPS.setValue(this.autoBlockMaxCPS.getValue());
                }
                if(autoBlockMaxCPS.getValue() > 10.0F && badCps){
                    autoBlockMaxCPS.setValue(10.0F);
                }
            } else {
                if (this.maxCPS.getName().equals(value) && this.minCPS.getValue() > this.maxCPS.getValue()) {
                    this.minCPS.setValue(this.maxCPS.getValue());
                }
            }
        } else {
            if (badCps && (this.autoBlockMinCPS.getValue() > 10.0F || this.autoBlockMaxCPS.getValue() > 10.0F)) {
                this.autoBlockMinCPS.setValue(8.0F);
                this.autoBlockMaxCPS.setValue(10.0F);
            }
        }
    }
    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
    public static class AttackData {
        private final EntityLivingBase entity;
        private final AxisAlignedBB box;
        private final double x;
        private final double y;
        private final double z;
        public AttackData(EntityLivingBase entityLivingBase) {
            this.entity = entityLivingBase;
            double collisionBorderSize = entityLivingBase.getCollisionBorderSize();
            this.box = entityLivingBase.getEntityBoundingBox().expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
            this.x = entityLivingBase.posX;
            this.y = entityLivingBase.posY;
            this.z = entityLivingBase.posZ;
        }
        public EntityLivingBase getEntity() {
            return this.entity;
        }
        public AxisAlignedBB getBox() {
            return this.box;
        }
        public double getX() {
            return this.x;
        }
        public double getY() {
            return this.y;
        }
        public double getZ() {
            return this.z;
        }
    }
}
