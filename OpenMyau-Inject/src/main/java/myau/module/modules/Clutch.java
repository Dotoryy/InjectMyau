package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MouseButtonEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.TextProperty;
import myau.util.KeyBindUtil;
import myau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double HALF_WIDTH = 0.3;
    private static final double[][] CORNERS =
            {{-HALF_WIDTH, -HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
             {-HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, HALF_WIDTH}};
    private static final Map<String, Integer> BLOCK_SCORE = new HashMap<String, Integer>();
    private static final int ROTATION_PRIORITY = 3;
    private static final int AUTO_CLUTCH_ARM_TICKS = 40;

    static {
        BLOCK_SCORE.put("obsidian", 0);
        BLOCK_SCORE.put("end_stone", 1);
        BLOCK_SCORE.put("planks", 2);
        BLOCK_SCORE.put("log", 2);
        BLOCK_SCORE.put("log2", 2);
        BLOCK_SCORE.put("glass", 3);
        BLOCK_SCORE.put("stained_glass", 3);
        BLOCK_SCORE.put("hardened_clay", 4);
        BLOCK_SCORE.put("stained_hardened_clay", 4);
        BLOCK_SCORE.put("stone", 5);
        BLOCK_SCORE.put("wool", 5);
    }

    public final FloatProperty reach = new FloatProperty("reach", 4.5F, 0.5F, 4.5F, 0.1F);
    public final IntProperty speed = new IntProperty("speed", 8, 0, 100, 1);
    public final IntProperty snapbackSpeed = new IntProperty("snapback-speed", 12, 0, 100, 1);
    public final IntProperty maxDistance = new IntProperty("max-distance", 10, 0, 20, 1);
    public final IntProperty rotationTolerance = new IntProperty("rotation-tolerance", 25, 20, 100, 1);
    public final IntProperty minimumFallDistance =
            new IntProperty("minimum-fall-distance", 10, 3, 20, 1);
    public final BooleanProperty simulateFuturePosition =
            new BooleanProperty("simulate-future-position", true);
    public final BooleanProperty autoClutch = new BooleanProperty("auto-clutch", false);
    public final BooleanProperty requireVoid = new BooleanProperty("require-void", false);
    public final BooleanProperty silent = new BooleanProperty("silent", true);
    public final BooleanProperty preferWeakBlocks = new BooleanProperty("prefer-weak-blocks", false);
    public final KeyProperty selectKey = new KeyProperty("select-key", KeyProperty.NONE);
    public final TextProperty itemBlacklist = new TextProperty("item-blacklist", "");

    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placing;
    private boolean slotWasSwapped;
    private boolean autoClickerWasOn;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private boolean hasAim;
    private boolean resetting;
    private BlockPos lastPlaced;
    private int clutchBlocksPlaced;
    private boolean autoClutchActive;
    private boolean autoClutchChecking;
    private int autoClutchCheckCounter;
    private boolean autoClutchLandedGuard;
    private int autoClutchLandedTick;
    private int prevHurtTime = -1;
    private float serverYaw;
    private float serverPitch;
    private float restoreYaw;
    private float restorePitch;
    private boolean restoreCaptured;

    public Clutch() {
        super("Clutch", false);
    }

    @Override
    public void onEnabled() {
        this.hasAim = false;
        this.resetting = false;
        this.clutchBlocksPlaced = 0;
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchCheckCounter = 0;
        this.autoClutchLandedGuard = false;
        this.autoClutchLandedTick = 0;
        this.prevHurtTime = -1;
        this.restoreCaptured = false;
    }

    @Override
    public void onDisabled() {
        this.clearAim(false);
        this.disablePlacing(true);
        this.autoClutchActive = false;
        this.autoClutchChecking = false;
        this.autoClutchLandedGuard = false;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null
                || mc.theWorld == null) {
            return;
        }
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker != null && bedNuker.isEnabled() && bedNuker.isReady()) {
            return;
        }
        float baseYaw = event.isRotated() ? event.getNewYaw() : event.getYaw();
        float basePitch = event.isRotated() ? event.getNewPitch() : event.getPitch();
        this.serverYaw = event.getYaw();
        this.serverPitch = event.getPitch();
        this.runPrePlayerInteract();
        if (mc.currentScreen != null) {
            this.disablePlacing(false);
        }
        if (this.resetting) {
            if (this.silent.getValue() || !this.restoreCaptured) {
                this.aimYaw = mc.thePlayer.rotationYaw;
                this.aimPitch = mc.thePlayer.rotationPitch;
            } else {
                this.aimYaw = this.restoreYaw;
                this.aimPitch = this.restorePitch;
            }
            float[] smoothed =
                    this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, true);
            if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - this.aimYaw)) < 0.5F
                    && Math.abs(smoothed[1] - this.aimPitch) < 0.5F) {
                this.resetting = false;
                this.restoreCaptured = false;
                this.restoreInputsAndAutoClicker();
            } else {
                this.submitRotation(event, smoothed);
            }
            return;
        }
        if (!this.hasAim) {
            return;
        }
        float[] smoothed =
                this.getRotationsSmoothed(baseYaw, basePitch, this.aimYaw, this.aimPitch, false);
        this.submitRotation(event, smoothed);
        this.tryPlace(smoothed);
    }

    private void submitRotation(UpdateEvent event, float[] smoothed) {
        event.setRotation(smoothed[0], smoothed[1], ROTATION_PRIORITY);
        event.setPervRotation(smoothed[0], ROTATION_PRIORITY);
        if (this.silent.getValue()) {
            return;
        }
        Myau.rotationManager.setRotation(smoothed[0], smoothed[1], ROTATION_PRIORITY, true);
    }

    private void tryPlace(float[] smoothed) {
        if (!this.placing || this.targetHitPos == null || !this.canClutchHere()) {
            return;
        }
        int maxBlocks = this.maxDistance.getValue();
        if (maxBlocks != 0 && this.clutchBlocksPlaced >= maxBlocks) {
            return;
        }
        double tolerance = this.rotationTolerance.getValue();
        if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - this.serverYaw)) > tolerance
                || Math.abs(smoothed[1] - this.serverPitch) > tolerance) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        MovingObjectPosition mop = RotationUtil.rayTrace(
                smoothed[0], smoothed[1], this.reach.getValue(), 1.0F);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || mop.getBlockPos() == null || mop.sideHit == null) {
            return;
        }
        if (!this.targetHitPos.equals(mop.getBlockPos()) || this.targetSide != mop.sideHit) {
            return;
        }
        if (mop.sideHit == EnumFacing.DOWN || !canPlaceBlockOnSide(held, mop.getBlockPos(), mop.sideHit)) {
            return;
        }
        this.placeAtBlock = mop.getBlockPos();
        this.hitSide = mop.sideHit;
        this.hitVec = mop.hitVec;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
            if (mop.sideHit != EnumFacing.UP) {
                this.clutchBlocksPlaced++;
            }
            this.lastPlaced = mop.getBlockPos();
            mc.thePlayer.swingItem();
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onMouseButton(MouseButtonEvent event) {
        if (this.isEnabled() && (this.placing || this.resetting || this.hasAim)) {
            event.setCancelled(true);
        }
    }

    private void runPrePlayerInteract() {
        if (mc.thePlayer.onGround) {
            this.clutchBlocksPlaced = 0;
        }
        this.updateAutoClutch(mc.thePlayer.ticksExisted);
        boolean active = this.selectKey.isHeld() || this.autoClutchActive;
        if (mc.currentScreen != null || !active || !this.canClutchHere()) {
            this.clearAim(true);
            this.disablePlacing(false);
            return;
        }
        BlockPos below = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!canPlaceThrough(below)) {
            this.disablePlacing(false);
            return;
        }
        int slot = this.pickBlockSlot();
        if (slot == -1) {
            this.disablePlacing(false);
            return;
        }
        this.plannedSlot = slot;
        AimResult target = this.clutchAim();
        if (target != null) {
            this.targetHitPos = target.ray.getBlockPos();
            this.targetSide = target.ray.sideHit;
            this.aimYaw = target.yaw;
            this.aimPitch = target.pitch;
            this.hasAim = true;
            this.resetting = false;
        }
        if (this.hasAim && !this.placing) {
            this.enablePlacing();
        }
        if (this.placing || this.resetting || this.hasAim) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            this.equipPlannedSlot();
        }
    }

    private void updateAutoClutch(int ticksExisted) {
        if (!this.autoClutch.getValue()) {
            this.autoClutchActive = false;
            this.autoClutchChecking = false;
            this.autoClutchLandedGuard = false;
            this.prevHurtTime = mc.thePlayer.hurtTime;
            return;
        }
        int currentHurtTime = mc.thePlayer.hurtTime;
        if (this.prevHurtTime >= 0 && currentHurtTime > this.prevHurtTime) {
            this.autoClutchChecking = true;
            this.autoClutchCheckCounter = 0;
            this.autoClutchLandedGuard = false;
        }
        this.prevHurtTime = currentHurtTime;

        if (this.autoClutchChecking && !this.autoClutchActive && !this.autoClutchLandedGuard) {
            if ((this.autoClutchCheckCounter == 0 || this.autoClutchCheckCounter % 3 == 0)
                    && this.willFallFar(this.minimumFallDistance.getValue())) {
                this.autoClutchActive = true;
            }
            this.autoClutchCheckCounter++;
            if (this.autoClutchCheckCounter > AUTO_CLUTCH_ARM_TICKS) {
                this.autoClutchChecking = false;
                this.autoClutchCheckCounter = 0;
            }
        }
        if (this.autoClutchLandedGuard) {
            boolean expired = ticksExisted - this.autoClutchLandedTick >= 10;
            boolean jumped = mc.gameSettings.keyBindJump.isKeyDown();
            boolean airborneUp = !mc.thePlayer.onGround && mc.thePlayer.motionY > 0.0;
            if (expired || jumped || airborneUp) {
                this.autoClutchActive = false;
                this.autoClutchChecking = false;
                this.autoClutchLandedGuard = false;
            }
        }
        if (this.autoClutchActive && mc.thePlayer.onGround
                && mc.thePlayer.hurtTime < mc.thePlayer.maxHurtTime - 2
                && !this.autoClutchLandedGuard) {
            this.autoClutchLandedGuard = true;
            this.autoClutchLandedTick = ticksExisted;
            if (!this.willFallSoon()) {
                this.autoClutchActive = false;
                this.autoClutchChecking = false;
                this.autoClutchLandedGuard = false;
            }
        }
        if (!this.autoClutchActive && !this.autoClutchLandedGuard && mc.thePlayer.onGround
                && mc.thePlayer.hurtTime == 0) {
            this.autoClutchChecking = false;
            this.autoClutchCheckCounter = 0;
        }
    }

    private void enablePlacing() {
        if (this.placing) {
            return;
        }
        this.placing = true;
        if (!this.restoreCaptured) {
            this.restoreYaw = mc.thePlayer.rotationYaw;
            this.restorePitch = mc.thePlayer.rotationPitch;
            this.restoreCaptured = true;
        }
        if (!this.slotWasSwapped) {
            this.prevSlot = mc.thePlayer.inventory.currentItem;
        }
        AutoClicker autoClicker = (AutoClicker) Myau.moduleManager.modules.get(AutoClicker.class);
        this.autoClickerWasOn = this.autoClickerWasOn
                || autoClicker != null && autoClicker.isEnabled();
        if (this.autoClickerWasOn && autoClicker != null) {
            autoClicker.setEnabled(false);
        }
    }

    private void disablePlacing(boolean forceRestore) {
        if (!this.placing && !forceRestore) {
            return;
        }
        this.placing = false;
        this.plannedSlot = -1;
        if ((forceRestore || !this.hasAim) && this.slotWasSwapped && this.prevSlot != -1
                && this.prevSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = this.prevSlot;
            this.slotWasSwapped = false;
        }
        if (forceRestore) {
            this.prevSlot = -1;
            this.restoreInputsAndAutoClicker();
        }
    }

    private void clearAim(boolean allowSnapback) {
        if (this.slotWasSwapped && this.prevSlot != -1
                && this.prevSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = this.prevSlot;
            this.slotWasSwapped = false;
        }
        this.targetHitPos = null;
        this.targetSide = null;
        this.lastPlaced = null;
        this.clutchBlocksPlaced = 0;
        if (allowSnapback && this.hasAim) {
            this.resetting = true;
        }
        this.hasAim = false;
        this.prevSlot = -1;
    }

    private void restoreInputsAndAutoClicker() {
        if (mc.currentScreen == null) {
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
        }
        if (this.autoClickerWasOn) {
            AutoClicker autoClicker =
                    (AutoClicker) Myau.moduleManager.modules.get(AutoClicker.class);
            if (autoClicker != null) {
                autoClicker.setEnabled(true);
            }
            this.autoClickerWasOn = false;
        }
    }


    private boolean willFallFar(double minFall) {
        double startY = mc.thePlayer.posY;
        PredictionState prediction = PredictionState.fromPlayer();
        for (int tick = 0; tick < 60; tick++) {
            prediction.tick(false);
            if (prediction.onGround) {
                return false;
            }
            if (startY - prediction.posY > minFall) {
                return true;
            }
        }
        return false;
    }

    private boolean willFallSoon() {
        PredictionState prediction = PredictionState.fromPlayer();
        for (int tick = 0; tick < 10; tick++) {
            prediction.tick(true);
            if (!prediction.onGround && prediction.motionY < 0.0) {
                return true;
            }
        }
        return false;
    }


    private AimResult clutchAim() {
        Vec3 playerPos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 futurePos = playerPos;
        if (this.simulateFuturePosition.getValue()) {
            PredictionState prediction = PredictionState.fromPlayer();
            for (int tick = 0; tick < 20; tick++) {
                prediction.tick(false);
                if (prediction.posY < playerPos.yCoord - 2.0 || prediction.onGround) {
                    break;
                }
            }
            futurePos = prediction.getPos();
        }

        int feetX = MathHelper.floor_double(playerPos.xCoord);
        int feetY = MathHelper.floor_double(playerPos.yCoord);
        int feetZ = MathHelper.floor_double(playerPos.zCoord);
        List<BlockCandidate> candidates = new ArrayList<BlockCandidate>();
        for (int y = feetY - 1; y >= feetY - 4; y--) {
            for (int x = feetX - 5; x <= feetX + 4; x++) {
                for (int z = feetZ - 5; z <= feetZ + 4; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (canPlaceThrough(pos)) {
                        continue;
                    }
                    double currentDist = distanceSquaredToBlock(playerPos, pos);
                    double futureDist = distanceSquaredToBlock(futurePos, pos);
                    double score = this.simulateFuturePosition.getValue()
                            ? currentDist * 0.3 + futureDist * 0.7
                            : currentDist;
                    if (pos.equals(this.lastPlaced)) {
                        score *= 0.95;
                    }
                    candidates.add(new BlockCandidate(score, pos));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.score, b.score));

        ItemStack held = this.plannedSlot >= 0 && this.plannedSlot <= 8
                ? mc.thePlayer.inventory.mainInventory[this.plannedSlot] : null;
        for (BlockCandidate candidate : candidates) {
            boolean underPlayer = isBlockUnderPlayer(candidate.pos, playerPos);
            AimResult result = this.getBestRotationsToBlock(
                    held, candidate.pos, eye, this.reach.getValue(), underPlayer);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static boolean isBlockUnderPlayer(BlockPos blockPos, Vec3 position) {
        if (blockPos.getY() >= MathHelper.floor_double(position.yCoord)) {
            return false;
        }
        for (double[] corner : CORNERS) {
            int cornerX = MathHelper.floor_double(position.xCoord + corner[0]);
            int cornerZ = MathHelper.floor_double(position.zCoord + corner[1]);
            if (blockPos.getX() == cornerX && blockPos.getZ() == cornerZ) {
                return true;
            }
        }
        return false;
    }

    private AimResult getBestRotationsToBlock(ItemStack held, BlockPos targetCell, Vec3 eye,
                                              double reachValue, boolean underPlayer) {
        double inset = 0.05;
        double step = 0.2;
        double jitter = step * 0.1;
        boolean faceSouth = Math.abs(eye.zCoord - (targetCell.getZ() + 1))
                < Math.abs(eye.zCoord - targetCell.getZ());
        boolean faceEast = Math.abs(eye.xCoord - (targetCell.getX() + 1))
                < Math.abs(eye.xCoord - targetCell.getX());
        float baseYaw = normaliseYaw(this.serverYaw);
        float basePitch = this.serverPitch;
        int steps = (int) Math.round(1.0 / step);

        List<RotationCandidate> candidates = new ArrayList<RotationCandidate>();
        candidates.add(new RotationCandidate(0.0, baseYaw, basePitch));
        for (int row = 0; row <= steps; row++) {
            double v = clamp01(row * step + randomRange(-jitter, jitter));
            for (int col = 0; col <= steps; col++) {
                double u = clamp01(col * step + randomRange(-jitter, jitter));
                if (underPlayer) {
                    float[] rotations = getRotationsWrapped(eye, targetCell.getX() + u,
                            targetCell.getY() + 1 - inset, targetCell.getZ() + v);
                    candidates.add(new RotationCandidate(
                            cost(baseYaw, basePitch, rotations), rotations[0], rotations[1]));
                }
                float[] alongZ = getRotationsWrapped(eye, targetCell.getX() + u,
                        targetCell.getY() + v,
                        faceSouth ? targetCell.getZ() + 1 - inset : targetCell.getZ() + inset);
                candidates.add(new RotationCandidate(
                        cost(baseYaw, basePitch, alongZ), alongZ[0], alongZ[1]));
                float[] alongX = getRotationsWrapped(eye,
                        faceEast ? targetCell.getX() + 1 - inset : targetCell.getX() + inset,
                        targetCell.getY() + v, targetCell.getZ() + u);
                candidates.add(new RotationCandidate(
                        cost(baseYaw, basePitch, alongX), alongX[0], alongX[1]));
            }
        }
        candidates.sort((a, b) -> Double.compare(a.cost, b.cost));

        for (RotationCandidate candidate : candidates) {
            float yaw = unwrapYaw(candidate.yaw, this.serverYaw);
            MovingObjectPosition ray =
                    RotationUtil.rayTrace(yaw, candidate.pitch, reachValue, 1.0F);
            if (ray == null || ray.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                continue;
            }
            EnumFacing face = ray.sideHit;
            if (face == EnumFacing.DOWN || face == EnumFacing.UP && !underPlayer) {
                continue;
            }
            if (targetCell.equals(ray.getBlockPos()) && canPlaceBlockOnSide(held, ray.getBlockPos(), face)) {
                return new AimResult(ray, yaw, candidate.pitch);
            }
        }
        return null;
    }

    private static double cost(float baseYaw, float basePitch, float[] rotations) {
        return Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - baseYaw))
                + Math.abs(rotations[1] - basePitch);
    }

    private static boolean canPlaceBlockOnSide(ItemStack stack, BlockPos pos, EnumFacing side) {
        return stack != null && stack.getItem() instanceof ItemBlock
                && ((ItemBlock) stack.getItem())
                        .canPlaceBlockOnSide(mc.theWorld, pos, side, mc.thePlayer, stack);
    }


    private int pickBlockSlot() {
        if (!this.preferWeakBlocks.getValue()) {
            int current = mc.thePlayer.inventory.currentItem;
            if (this.isBlockSlot(current)) {
                return current;
            }
            for (int slot = 8; slot >= 0; slot--) {
                if (this.isBlockSlot(slot)) {
                    return slot;
                }
            }
            return -1;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 8; slot >= 0; slot--) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize == 0 || !(stack.getItem() instanceof ItemBlock)
                    || this.isBlacklisted(stack)) {
                continue;
            }
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            ResourceLocation id = (ResourceLocation) Block.blockRegistry.getNameForObject(block);
            if (id == null) {
                continue;
            }
            Integer score = BLOCK_SCORE.get(id.getResourcePath());
            if (score != null && score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private boolean isBlockSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock
                && !this.isBlacklisted(stack);
    }

    private boolean isBlacklisted(ItemStack stack) {
        String list = this.itemBlacklist.getValue();
        if (list == null || list.trim().isEmpty()) {
            return false;
        }
        String name = stack.getDisplayName();
        if (name == null) {
            return false;
        }
        name = name.toLowerCase();
        for (String entry : list.split(",")) {
            String trimmed = entry.trim().toLowerCase();
            if (!trimmed.isEmpty() && name.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private void equipPlannedSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (this.plannedSlot != -1 && this.plannedSlot != current) {
            mc.thePlayer.inventory.currentItem = this.plannedSlot;
            this.slotWasSwapped = true;
        }
    }


    private float[] getRotationsSmoothed(float currentYaw, float currentPitch,
                                         float targetYaw, float targetPitch, boolean snapback) {
        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;
        if (Math.abs(deltaYaw) < 0.1F) {
            currentYaw = targetYaw;
        }
        if (Math.abs(deltaPitch) < 0.1F) {
            currentPitch = targetPitch;
        }
        if (currentYaw == targetYaw && currentPitch == targetPitch) {
            return new float[]{currentYaw, clampPitch(currentPitch)};
        }
        float maxStep = snapback ? this.snapbackSpeed.getValue() : this.speed.getValue();
        maxStep *= 1.0F - (float) randomRange(0.0, 0.2);
        float totalDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        if (totalDelta <= maxStep) {
            currentYaw = targetYaw;
            currentPitch = targetPitch;
        } else if (maxStep > 0.0F) {
            float scale = maxStep / totalDelta;
            currentYaw += deltaYaw * scale;
            currentPitch += deltaPitch * scale;
        }
        return new float[]{currentYaw, clampPitch(currentPitch)};
    }


    private static boolean canPlaceThrough(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        return material == Material.air || material == Material.water || material == Material.lava
                || block == Blocks.fire;
    }

    private boolean canClutchHere() {
        return !this.requireVoid.getValue()
                || overVoid(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    private static boolean overVoid(double x, double y, double z) {
        for (int level = (int) y; level > -1; level--) {
            if (!(mc.theWorld.getBlockState(new BlockPos(x, level, z)).getBlock()
                    instanceof BlockAir)) {
                return false;
            }
        }
        return true;
    }

    private static double distanceSquaredToBlock(Vec3 point, BlockPos block) {
        double closestX = Math.max(block.getX(), Math.min(block.getX() + 1, point.xCoord));
        double closestY = Math.max(block.getY(), Math.min(block.getY() + 1, point.yCoord));
        double closestZ = Math.max(block.getZ(), Math.min(block.getZ() + 1, point.zCoord));
        double dx = point.xCoord - closestX;
        double dy = point.yCoord - closestY;
        double dz = point.zCoord - closestZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static float clampPitch(float pitch) {
        return pitch < -90.0F ? -90.0F : Math.min(pitch, 90.0F);
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    private static double randomRange(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private static float normaliseYaw(float yaw) {
        yaw = (yaw % 360.0F + 360.0F) % 360.0F;
        return yaw > 180.0F ? yaw - 360.0F : yaw;
    }

    private static float unwrapYaw(float yaw, float previousYaw) {
        return previousYaw + MathHelper.wrapAngleTo180_float(yaw - previousYaw);
    }

    private static float[] getRotationsWrapped(Vec3 eye, double x, double y, double z) {
        double dx = x - eye.xCoord;
        double dy = y - eye.yCoord;
        double dz = z - eye.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[]{normaliseYaw(yaw), clampPitch(pitch)};
    }

    private static final class BlockCandidate {
        private final double score;
        private final BlockPos pos;

        private BlockCandidate(double score, BlockPos pos) {
            this.score = score;
            this.pos = pos;
        }
    }

    private static final class RotationCandidate {
        private final double cost;
        private final float yaw;
        private final float pitch;

        private RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class AimResult {
        private final MovingObjectPosition ray;
        private final float yaw;
        private final float pitch;

        private AimResult(MovingObjectPosition ray, float yaw, float pitch) {
            this.ray = ray;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class PredictionState {
        private AxisAlignedBB box;
        private double motionX;
        private double motionY;
        private double motionZ;
        private double posY;
        private boolean onGround;

        private static PredictionState fromPlayer() {
            PredictionState state = new PredictionState();
            state.box = mc.thePlayer.getEntityBoundingBox();
            state.motionX = mc.thePlayer.motionX;
            state.motionY = mc.thePlayer.motionY;
            state.motionZ = mc.thePlayer.motionZ;
            state.posY = mc.thePlayer.posY;
            state.onGround = mc.thePlayer.onGround;
            return state;
        }

        private Vec3 getPos() {
            return new Vec3((this.box.minX + this.box.maxX) / 2.0, this.box.minY,
                    (this.box.minZ + this.box.maxZ) / 2.0);
        }

        private void tick(boolean stopHorizontal) {
            if (stopHorizontal) {
                this.motionX = 0.0;
                this.motionZ = 0.0;
            }
            this.motionY -= 0.08;
            this.move(this.motionX, this.motionY, this.motionZ);
            this.motionY *= 0.98;
            this.motionX *= 0.91;
            this.motionZ *= 0.91;
        }

        private void move(double x, double y, double z) {
            double originalX = x;
            double originalY = y;
            double originalZ = z;
            List<AxisAlignedBB> collisions =
                    mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, this.box.addCoord(x, y, z));
            for (AxisAlignedBB collision : collisions) {
                y = collision.calculateYOffset(this.box, y);
            }
            this.box = this.box.offset(0.0, y, 0.0);
            for (AxisAlignedBB collision : collisions) {
                x = collision.calculateXOffset(this.box, x);
            }
            this.box = this.box.offset(x, 0.0, 0.0);
            for (AxisAlignedBB collision : collisions) {
                z = collision.calculateZOffset(this.box, z);
            }
            this.box = this.box.offset(0.0, 0.0, z);
            this.onGround = originalY != y && originalY < 0.0;
            this.posY = this.box.minY;
            if (originalX != x) {
                this.motionX = 0.0;
            }
            if (originalY != y) {
                this.motionY = 0.0;
            }
            if (originalZ != z) {
                this.motionZ = 0.0;
            }
        }
    }
}
