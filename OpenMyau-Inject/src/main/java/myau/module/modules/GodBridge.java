package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MouseButtonEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.access.AccessorRenderManager;
import myau.util.ChatUtil;
import myau.util.ItemUtil;
import myau.util.KeyBindUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GodBridge extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final double SENSITIVITY_QUANTUM = 0.03404715;

    private static final double ACTIVATION_ACROSS_MIN = 0.3;
    private static final double ACTIVATION_ACROSS_MAX = 0.7;
    private static final double ACTIVATION_HEIGHT_MIN = 0.6;
    private static final double ACTIVATION_HEIGHT_MAX = 1.0;
    private static final float ACTIVATION_YAW_TOLERANCE = 20.0F;
    private static final float ACTIVATION_PITCH = 65.0F;

    private static final long PROMPT_BREAK_MS = 300L;

    private static final float PITCH_STRAIGHT = 79.5F;
    private static final float PITCH_DIAGONAL = 75.6F;

    private static final long FREEZE_MS = 300L;
    private static final float TAKEOVER_LIMIT = 25.0F;
    private static final long TAKEOVER_GRACE_MS = 200L;

    private static final String[] REPLACEABLE_BLOCKS = {
            "air", "water", "flowing_water", "lava", "flowing_lava", "fire", "tallgrass",
            "deadbush", "snow_layer", "double_plant", "vine",
            "sapling", "yellow_flower", "red_flower", "brown_mushroom", "red_mushroom",
            "wheat", "carrots", "potatoes", "nether_wart", "reeds"
    };

    private static final double[] FACE_HIT_OFFSETS = {0.5, 0.4, 0.6, 0.35, 0.65};

    private static final EnumFacing[] ALLOWED_PLACE_FACES = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, EnumFacing.UP
    };

    private static final String[] UNPLACEABLE_EXACT = {
            "snow_layer", "web", "sapling", "daylight_detector", "beacon", "banner",
            "end_portal_frame", "end_portal", "lever", "stone_button", "wooden_button",
            "skull", "cactus", "double_plant", "waterlily", "carpet", "tripwire_hook",
            "tallgrass", "yellow_flower", "red_flower", "flower_pot", "sign", "ladder",
            "torch", "redstone_torch", "unlit_redstone_torch", "gravel", "clay", "sand",
            "soul_sand", "chest", "trapped_chest", "ender_chest", "furnace", "lit_furnace",
            "jukebox", "enchanting_table", "dropper", "dispenser", "hopper", "anvil",
            "noteblock", "crafting_table", "mob_spawner", "brewing_stand", "bed"
    };
    private static final String[] UNPLACEABLE_CONTAINS = {
            "stairs", "slab", "fence", "pane", "rail", "door",
            "torch", "pumpkin", "flower", "sapling", "banner", "button",
            "skull", "web", "carpet", "cactus", "sign", "mushroom"
    };

    private static final long SEARCH_BUDGET_MS = 4L;
    private static final int REJECT_TICKS = 4;

    public final BooleanProperty holdBlock = new BooleanProperty("hold-block", true);
    public final BooleanProperty onlyWhenSneak = new BooleanProperty("only-when-sneak", true);
    public final BooleanProperty autoSwap = new BooleanProperty("auto-swap", true);
    public final IntProperty autoJump = new IntProperty("auto-jump", 8, 0, 30, 1);
    public final BooleanProperty disableGuard = new BooleanProperty("disable-guard", true);
    public final BooleanProperty showBounds = new BooleanProperty("show-activation-bounds", true);
    public final IntProperty rotationSpeed = new IntProperty("rotation-speed", 280, 50, 1000, 10);
    public final IntProperty startDelay = new IntProperty("start-delay", 0, 0, 1000, 10);
    public final IntProperty activationDelay = new IntProperty("activation-delay", 150, 0, 1000, 10);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    private boolean armed = false;
    private boolean aiming = false;
    private boolean running = false;
    private boolean waitingForDelay = false;

    private long activatePromptAt = 0L;
    private long promptBrokeAt = 0L;
    private int promptFadeRgb = 0xFF5555;
    private float promptAlpha = 0.0F;
    private long promptFadeLastAt = 0L;
    private BlockPos boundsLastPos = null;
    private EnumFacing boundsLastFace = null;
    private boolean autoPlaceWindow = false;
    private boolean wasOnGround = false;

    private float currentPitch = PITCH_DIAGONAL;
    private float baseYaw = 0.0F;
    private int travelX = 0;
    private int travelZ = 0;
    private float autoStrafe = 0.0F;
    private boolean isDiagonalBridge = false;
    private int blocksPlacedSinceJump = 0;
    private boolean isAutoJumping = false;
    private double landPosX = 0.0;
    private double landPosZ = 0.0;

    private long delayEndTime = 0L;
    private long freezeLastTickAt = 0L;

    private boolean guardStateCaptured = false;
    private boolean guardWasEnabled = false;

    private long takeoverDetectionAt = 0L;
    private boolean takeoverCameraValid = false;
    private float takeoverAccumulated = 0.0F;
    private long takeoverLastFrameAt = 0L;

    private boolean rotationActive = false;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 250L;
    private float rotationStartYaw = 0.0F;
    private float rotationStartPitch = 0.0F;
    private float rotationTargetYaw = 0.0F;
    private float rotationTargetPitch = 0.0F;
    private float scriptedRotationYaw = 0.0F;
    private float scriptedRotationPitch = 0.0F;

    private final Set<Integer> heldFromBefore = new HashSet<Integer>();

    private final Set<BlockPos> cancelledGhostBlocks = new HashSet<BlockPos>();

    private int currentClientTick = Integer.MIN_VALUE;
    private int placementEvaluationTick = Integer.MIN_VALUE;
    private int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private int lastSuccessfulPlaceTick = Integer.MIN_VALUE;
    private int forceSuppressTick = Integer.MIN_VALUE;
    private long totalPlacePackets = 0L;
    private long placePacketsAtTickBoundary = 0L;
    private boolean placingViaModule = false;
    private boolean useSuppressed = false;

    private boolean hasLastSentServerPos = false;
    private double lastSentServerPosX;
    private double lastSentServerPosY;
    private double lastSentServerPosZ;

    private BlockPos lastPlacedPos = null;
    private BlockPos lastSupportPos = null;
    private EnumFacing lastSupportFace = null;
    private final Map<BlockPos, Integer> rejectedTargets = new HashMap<BlockPos, Integer>();

    private int bridgeLaneBlock = 0;
    private int bridgeStartProgress = 0;
    private int floorY = Integer.MIN_VALUE;

    public GodBridge() {
        super("God Bridge", false);
    }


    @Override
    public void onEnabled() {
        press(mc.gameSettings.keyBindAttack, false);
        this.initSystem();
    }

    @Override
    public void onDisabled() {
        this.haltSystem(false);
    }

    private void initSystem() {
        this.armed = true;
        this.running = false;
        this.aiming = false;
        this.waitingForDelay = false;
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        this.rotationActive = false;
        this.logMsg("&eReady.");
    }

    private void haltSystem(boolean announce) {
        this.armed = false;
        this.running = false;
        this.aiming = false;
        this.waitingForDelay = false;
        this.rotationActive = false;
        this.autoPlaceWindow = false;
        this.scriptedRotationYaw = 0.0F;
        this.scriptedRotationPitch = 0.0F;
        this.takeoverDetectionAt = 0L;
        this.takeoverCameraValid = false;
        this.takeoverAccumulated = 0.0F;
        this.takeoverLastFrameAt = 0L;
        this.blocksPlacedSinceJump = 0;
        this.isAutoJumping = false;
        this.isDiagonalBridge = false;
        this.cancelledGhostBlocks.clear();
        this.heldFromBefore.clear();
        this.resetPlacementState();

        if (mc.thePlayer != null) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            mc.thePlayer.movementInput.jump = false;
            mc.thePlayer.setSprinting(false);
        }
        this.releaseDrivenKeys();
        press(mc.gameSettings.keyBindAttack, KeyBindUtil.isKeyDown(
                mc.gameSettings.keyBindAttack.getKeyCode()));
        this.resetGuardState();

        this.freezeLastTickAt = 0L;
        this.armed = true;
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        if (announce) {
            this.logMsg("&eStopped.");
        }
    }


    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        this.verifyGuardState();

        if (this.aiming || this.waitingForDelay || this.running) {
            boolean holdingRmb = KeyBindUtil.isKeyDown(
                    mc.gameSettings.keyBindUseItem.getKeyCode());
            boolean holdingSneak = KeyBindUtil.isKeyDown(
                    mc.gameSettings.keyBindSneak.getKeyCode());
            boolean holdingBack = KeyBindUtil.isKeyDown(
                    mc.gameSettings.keyBindBack.getKeyCode());
            boolean validMovement = holdingBack;
            if (this.autoStrafe > 0.5F) {
                validMovement = validMovement
                        && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode());
            }
            if (this.autoStrafe < -0.5F) {
                validMovement = validMovement
                        && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode());
            }
            if (!holdingRmb || holdingSneak || !validMovement) {
                this.haltSystem(true);
                return;
            }
            if (this.checkManualOverride()) {
                return;
            }
        }

        if (this.running || this.aiming) {
            press(mc.gameSettings.keyBindAttack, false);
            this.updateRots();
        }

        if (this.armed && !this.running && !this.aiming && !this.waitingForDelay) {
            this.updatePrompt();
        }

        if (this.waitingForDelay) {
            if (System.currentTimeMillis() >= this.delayEndTime) {
                this.waitingForDelay = false;
                this.execMain();
            }
            return;
        }
        if (!this.running) {
            return;
        }

        long now = System.currentTimeMillis();
        if (this.freezeLastTickAt != 0L && now - this.freezeLastTickAt > FREEZE_MS) {
            this.haltSystem(true);
            return;
        }
        this.freezeLastTickAt = now;

        if (mc.thePlayer == null || mc.thePlayer.isDead || mc.thePlayer.fallDistance > 7.0F) {
            this.haltSystem(true);
            return;
        }
        this.doSwap();
        if (!isHoldingBlock()) {
            this.haltSystem(true);
            return;
        }
        this.syncTick();
        if (this.placementEvaluationTick != this.currentClientTick) {
            this.placementEvaluationTick = this.currentClientTick;
            this.processTick();
        }
    }

    @EventTarget
    public void onUpdatePost(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST && this.running) {
            this.placePacketsAtTickBoundary = this.totalPlacePackets;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (this.running && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.haltSystem(true);
            return;
        }
        if (event.getPacket() instanceof S23PacketBlockChange
                && !this.cancelledGhostBlocks.isEmpty()) {
            this.cancelledGhostBlocks.remove(
                    ((S23PacketBlockChange) event.getPacket()).getBlockPosition());
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacketSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer) {
            C03PacketPlayer movement = (C03PacketPlayer) event.getPacket();
            if (movement.isMoving()) {
                this.hasLastSentServerPos = true;
                this.lastSentServerPosX = movement.getPositionX();
                this.lastSentServerPosY = movement.getPositionY();
                this.lastSentServerPosZ = movement.getPositionZ();
            }
            return;
        }
        if (!(event.getPacket() instanceof C08PacketPlayerBlockPlacement)) {
            return;
        }
        C08PacketPlayerBlockPlacement placement =
                (C08PacketPlayerBlockPlacement) event.getPacket();
        if (placement.getPlacedBlockDirection() == 255) {
            if (this.shouldSuppressClicks()) {
                this.suppressUse();
                event.setCancelled(true);
            }
            return;
        }
        ItemStack stack = placement.getStack();
        if (stack != null && stack.getItem() instanceof ItemBlock) {
            this.totalPlacePackets++;
        }
    }


    private void updatePrompt() {
        if (mc.thePlayer == null || mc.currentScreen != null) {
            this.clearPrompt();
            return;
        }
        if (this.holdBlock.getValue() && !isHoldingBlock()) {
            this.abandonPrompt();
            return;
        }
        boolean lookingDown = mc.thePlayer.rotationPitch >= ACTIVATION_PITCH;
        boolean edge = lookingDown && this.checkEdge();

        if (isSneaking() && edge) {
            if (this.activatePromptAt == 0L) {
                this.activatePromptAt = System.currentTimeMillis();
            }
            this.promptBrokeAt = 0L;
            if (this.shouldSuppressUse()) {
                press(mc.gameSettings.keyBindUseItem, false);
            }
            if (this.isPromptReady()
                    && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
                this.captureGuard();
                this.verifyGuardState();
            } else if (this.guardStateCaptured) {
                this.resetGuardState();
            }
            return;
        }

        if (this.activatePromptAt == 0L) {
            return;
        }
        if (!this.isPromptReady()) {
            this.clearPrompt();
            return;
        }
        if (this.promptBrokeAt == 0L) {
            this.storeColor();
            this.promptBrokeAt = System.currentTimeMillis();
        }
        press(mc.gameSettings.keyBindUseItem, false);

        if (!isSneaking()
                && KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())
                && checkYaw(mc.thePlayer.rotationYaw)) {
            this.storeColor();
            this.activatePromptAt = 0L;
            this.promptBrokeAt = 0L;
            this.startAim();
            if (!this.running && !this.aiming && !this.waitingForDelay) {
                press(mc.gameSettings.keyBindUseItem, false);
            }
            return;
        }
        if (this.onlyWhenSneak.getValue() && !isSneaking()) {
            this.abandonPrompt();
            return;
        }
        if (System.currentTimeMillis() - this.promptBrokeAt > PROMPT_BREAK_MS) {
            this.clearPrompt();
        }
    }

    private void abandonPrompt() {
        if (this.activatePromptAt == 0L) {
            return;
        }
        this.storeColor();
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        press(mc.gameSettings.keyBindUseItem,
                KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode()));
        if (!this.running && !this.aiming && !this.waitingForDelay) {
            this.resetGuardState();
        }
    }

    private void clearPrompt() {
        this.storeColor();
        if (this.shouldSuppressUse()) {
            press(mc.gameSettings.keyBindUseItem, false);
        }
        this.activatePromptAt = 0L;
        this.promptBrokeAt = 0L;
        if (!this.running && !this.aiming && !this.waitingForDelay) {
            this.resetGuardState();
        }
    }

    private void storeColor() {
        if (this.activatePromptAt != 0L) {
            this.promptFadeRgb = this.isPromptReady() ? 0x55FF55 : 0xFF5555;
        }
    }

    private boolean isPromptReady() {
        return this.activatePromptAt != 0L
                && System.currentTimeMillis() - this.activatePromptAt >= this.activationDelay.getValue();
    }

    private boolean shouldSuppressUse() {
        return this.activatePromptAt != 0L
                && System.currentTimeMillis() - this.activatePromptAt >= this.activationDelay.getValue() / 2;
    }

    private boolean checkEdge() {
        if (!checkYaw(mc.thePlayer.rotationYaw)) {
            return false;
        }
        MovingObjectPosition hit = RotationUtil.rayTrace(mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch, 4.5, 1.0F);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null) {
            return false;
        }
        int face = hit.sideHit.ordinal();
        if (face < 2) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        Vec3 local = new Vec3(hit.hitVec.xCoord - pos.getX(), hit.hitVec.yCoord - pos.getY(),
                hit.hitVec.zCoord - pos.getZ());
        if (!checkFaceCenter(face, local)) {
            return false;
        }
        if (!this.checkBlock(pos)) {
            return false;
        }
        int aheadX = pos.getX();
        int aheadZ = pos.getZ();
        if (face == 2) {
            aheadZ--;
        } else if (face == 3) {
            aheadZ++;
        } else if (face == 4) {
            aheadX--;
        } else {
            aheadX++;
        }
        if (!isReplaceable(aheadX, pos.getY() + 1, aheadZ)) {
            return false;
        }
        double lipDistance;
        if (face == 5) {
            lipDistance = pos.getX() + 1 - mc.thePlayer.posX;
        } else if (face == 4) {
            lipDistance = mc.thePlayer.posX - pos.getX();
        } else if (face == 3) {
            lipDistance = pos.getZ() + 1 - mc.thePlayer.posZ;
        } else {
            lipDistance = mc.thePlayer.posZ - pos.getZ();
        }
        return lipDistance <= 0.85;
    }

    private static boolean checkYaw(float yaw) {
        float nearest45 = Math.round(yaw / 45.0F) * 45.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - nearest45))
                <= ACTIVATION_YAW_TOLERANCE;
    }

    private boolean checkBlock(BlockPos pos) {
        if (pos == null || pos.getY() != floor(mc.thePlayer.posY - 0.01)) {
            return false;
        }
        return Math.abs(mc.thePlayer.posX - (pos.getX() + 0.5)) <= 0.95
                && Math.abs(mc.thePlayer.posZ - (pos.getZ() + 0.5)) <= 0.95;
    }

    private static boolean checkFaceCenter(int face, Vec3 localHit) {
        if (localHit == null) {
            return false;
        }
        double across = face == 4 || face == 5 ? localHit.zCoord : localHit.xCoord;
        if (face == 3 || face == 4) {
            across = 1.0 - across;
        }
        return across >= ACTIVATION_ACROSS_MIN && across <= ACTIVATION_ACROSS_MAX
                && localHit.yCoord >= ACTIVATION_HEIGHT_MIN
                && localHit.yCoord <= ACTIVATION_HEIGHT_MAX;
    }


    private void startAim() {
        if (mc.thePlayer == null || !isHoldingBlock()) {
            this.logMsg("&cHold blocks");
            return;
        }
        this.captureGuard();
        this.baseYaw = Math.round(mc.thePlayer.rotationYaw / 45.0F) * 45.0F;

        boolean sideways = Math.abs(this.baseYaw % 90.0F) > 0.1F;
        boolean heldLeft = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode());
        boolean heldRight = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode());
        this.isDiagonalBridge = false;
        if (sideways) {
            if (heldLeft) {
                this.autoStrafe = 1.0F;
            } else if (heldRight) {
                this.autoStrafe = -1.0F;
            } else {
                this.autoStrafe = 0.0F;
                this.isDiagonalBridge = true;
            }
        } else {
            this.autoStrafe = 0.0F;
        }

        this.calcTravel(this.baseYaw, this.autoStrafe);
        this.blocksPlacedSinceJump = 0;
        this.isAutoJumping = false;
        this.cancelledGhostBlocks.clear();
        this.initLane();

        this.armed = false;
        this.running = false;
        this.aiming = true;
        this.waitingForDelay = false;
        this.autoPlaceWindow = false;

        this.currentPitch = !this.isDiagonalBridge && this.autoStrafe == 0.0F
                ? PITCH_STRAIGHT : PITCH_DIAGONAL;
        this.setRot(this.baseYaw, this.currentPitch, this.rotationSpeed.getValue());
        this.logMsg("&eAiming...");
    }

    private void calcTravel(float yaw, float strafe) {
        double radians = Math.toRadians(yaw);
        double moveX = -(-Math.sin(radians)) + strafe * Math.cos(radians);
        double moveZ = -Math.cos(radians) - strafe * -Math.sin(radians);
        if (Math.abs(moveX) > 0.1 && Math.abs(moveZ) > 0.1) {
            this.travelX = moveX > 0 ? 1 : -1;
            this.travelZ = moveZ > 0 ? 1 : -1;
        } else if (Math.abs(moveX) >= Math.abs(moveZ)) {
            this.travelX = moveX > 0 ? 1 : -1;
            this.travelZ = 0;
        } else {
            this.travelX = 0;
            this.travelZ = moveZ > 0 ? 1 : -1;
        }
    }

    private void execMain() {
        this.aiming = false;
        this.waitingForDelay = false;
        this.running = true;
        this.freezeLastTickAt = System.currentTimeMillis();
        this.autoPlaceWindow = true;
        this.scriptedRotationYaw = this.baseYaw;
        this.scriptedRotationPitch = this.currentPitch;
        this.takeoverDetectionAt = System.currentTimeMillis() + TAKEOVER_GRACE_MS;
        this.takeoverCameraValid = false;
        this.heldFromBefore.clear();
        this.saveInitialHolds();
        this.resetPlacementState();
        press(mc.gameSettings.keyBindAttack, false);

        if (mc.thePlayer != null) {
            this.landPosX = mc.thePlayer.posX;
            this.landPosZ = mc.thePlayer.posZ;
            this.wasOnGround = mc.thePlayer.onGround;
        }
        this.updateMovement(-1.0F, this.autoStrafe, false, false);
        press(mc.gameSettings.keyBindUseItem, true);
        this.logMsg("&aStarted");
    }


    private void setRot(float targetYaw, float targetPitch, long duration) {
        if (this.rotationActive && Math.abs(targetYaw - this.rotationTargetYaw) < 0.1F
                && Math.abs(targetPitch - this.rotationTargetPitch) < 0.1F) {
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        this.rotationStartYaw = mc.thePlayer.rotationYaw;
        this.rotationStartPitch = mc.thePlayer.rotationPitch;
        this.rotationTargetYaw = this.rotationStartYaw
                + MathHelper.wrapAngleTo180_float(targetYaw - this.rotationStartYaw);
        this.rotationTargetPitch = clamp(targetPitch, -90.0F, 90.0F);
        this.rotationStartedAt = System.currentTimeMillis();
        this.rotationDuration = Math.max(1L, duration);
        this.rotationActive = true;
    }

    private void updateRots() {
        if (!this.rotationActive || mc.thePlayer == null) {
            return;
        }
        double t = (double) (System.currentTimeMillis() - this.rotationStartedAt)
                / (double) this.rotationDuration;
        if (t > 1.0) {
            t = 1.0;
        }
        double overshoot = 1.70158;
        double p = t - 1.0;
        double progress = p * p * ((overshoot + 1.0) * p + overshoot) + 1.0;

        float desiredYaw = this.rotationStartYaw
                + (this.rotationTargetYaw - this.rotationStartYaw) * (float) progress;
        float desiredPitch = this.rotationStartPitch
                + (this.rotationTargetPitch - this.rotationStartPitch) * (float) progress;
        this.scriptedRotationYaw = quantize(this.rotationStartYaw, desiredYaw);
        this.scriptedRotationPitch =
                clamp(quantize(this.rotationStartPitch, desiredPitch), -90.0F, 90.0F);
        mc.thePlayer.rotationYaw = this.scriptedRotationYaw;
        mc.thePlayer.rotationPitch = this.scriptedRotationPitch;

        if (t >= 1.0) {
            this.rotationActive = false;
            if (this.aiming) {
                long delay = this.startDelay.getValue();
                if (delay > 0L) {
                    this.waitingForDelay = true;
                    this.delayEndTime = System.currentTimeMillis() + delay;
                } else {
                    this.execMain();
                }
            }
        }
    }

    private static float quantize(float origin, float value) {
        double steps = Math.round((value - origin) / SENSITIVITY_QUANTUM);
        return (float) (origin + steps * SENSITIVITY_QUANTUM);
    }


    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        this.updateFade();
        if (this.aiming || this.running) {
            this.updateRots();
        }
        if (this.running) {
            this.checkCamera();
            return;
        }
        this.drawActivationBounds();
    }

    private void drawActivationBounds() {
        if (!this.showBounds.getValue() || !this.armed || this.aiming || this.waitingForDelay
                || mc.thePlayer == null) {
            return;
        }
        this.boundsLastPos = null;
        this.boundsLastFace = null;
        if (mc.thePlayer.rotationPitch >= ACTIVATION_PITCH && checkYaw(mc.thePlayer.rotationYaw)) {
            MovingObjectPosition hit = RotationUtil.rayTrace(mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch, 4.5, 1.0F);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.getBlockPos() != null && hit.sideHit != null
                    && hit.sideHit.ordinal() >= 2) {
                this.boundsLastPos = hit.getBlockPos();
                this.boundsLastFace = hit.sideHit;
            }
        }
        if (this.boundsLastPos == null) {
            return;
        }
        this.drawRegion(this.boundsLastPos, this.boundsLastFace);
    }

    private void drawRegion(BlockPos pos, EnumFacing face) {
        double yMin = pos.getY() + ACTIVATION_HEIGHT_MIN;
        double yMax = pos.getY() + ACTIVATION_HEIGHT_MAX;
        double x1;
        double x2;
        double z1;
        double z2;
        if (face == EnumFacing.EAST) {
            x1 = pos.getX() + 1.005;
            x2 = x1;
            z1 = pos.getZ() + ACTIVATION_ACROSS_MIN;
            z2 = pos.getZ() + ACTIVATION_ACROSS_MAX;
        } else if (face == EnumFacing.WEST) {
            x1 = pos.getX() - 0.005;
            x2 = x1;
            z1 = pos.getZ() + (1.0 - ACTIVATION_ACROSS_MAX);
            z2 = pos.getZ() + (1.0 - ACTIVATION_ACROSS_MIN);
        } else if (face == EnumFacing.SOUTH) {
            z1 = pos.getZ() + 1.005;
            z2 = z1;
            x1 = pos.getX() + (1.0 - ACTIVATION_ACROSS_MAX);
            x2 = pos.getX() + (1.0 - ACTIVATION_ACROSS_MIN);
        } else {
            z1 = pos.getZ() - 0.005;
            z2 = z1;
            x1 = pos.getX() + ACTIVATION_ACROSS_MIN;
            x2 = pos.getX() + ACTIVATION_ACROSS_MAX;
        }

        int red;
        int green;
        int blue;
        int fillAlpha;
        int lineAlpha;
        if (this.activatePromptAt != 0L && this.promptAlpha > 0.05F) {
            red = this.promptFadeRgb >> 16 & 0xFF;
            green = this.promptFadeRgb >> 8 & 0xFF;
            blue = this.promptFadeRgb & 0xFF;
            fillAlpha = (int) (60.0F * this.promptAlpha);
            lineAlpha = (int) (220.0F * this.promptAlpha);
        } else {
            red = 255;
            green = 85;
            blue = 85;
            fillAlpha = 30;
            lineAlpha = 120;
        }
        fillAlpha = Math.max(4, fillAlpha);
        lineAlpha = Math.max(16, lineAlpha);

        double viewerX = AccessorRenderManager.getRenderPosX(mc.getRenderManager());
        double viewerY = AccessorRenderManager.getRenderPosY(mc.getRenderManager());
        double viewerZ = AccessorRenderManager.getRenderPosZ(mc.getRenderManager());

        GlStateManager.pushMatrix();
        RenderUtil.enableRenderState();
        GlStateManager.depthMask(false);
        GlStateManager.translate(-viewerX, -viewerY, -viewerZ);

        GlStateManager.color(red / 255.0F, green / 255.0F, blue / 255.0F, fillAlpha / 255.0F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x1, yMin, z1);
        GL11.glVertex3d(x2, yMin, z2);
        GL11.glVertex3d(x2, yMax, z2);
        GL11.glVertex3d(x1, yMax, z1);
        GL11.glEnd();

        GL11.glLineWidth(2.0F);
        GlStateManager.color(red / 255.0F, green / 255.0F, blue / 255.0F, lineAlpha / 255.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex3d(x1, yMin, z1);
        GL11.glVertex3d(x2, yMin, z2);
        GL11.glVertex3d(x2, yMax, z2);
        GL11.glVertex3d(x1, yMax, z1);
        GL11.glEnd();
        GL11.glLineWidth(1.0F);

        GlStateManager.depthMask(true);
        RenderUtil.disableRenderState();
        GlStateManager.resetColor();
        GlStateManager.popMatrix();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.promptAlpha < 0.05F || mc.thePlayer == null) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        String text = "Activate Module?";
        int alpha = Math.max(16, (int) (this.promptAlpha * 255.0F));
        int color = alpha << 24 | this.promptFadeRgb;
        float x = resolution.getScaledWidth() / 2.0F
                - mc.fontRendererObj.getStringWidth(text) / 2.0F;
        float y = resolution.getScaledHeight() / 2.0F + 10.0F;
        mc.fontRendererObj.drawStringWithShadow(text, x, y, color);
    }

    private void updateFade() {
        boolean show = this.armed && !this.running && !this.aiming && !this.waitingForDelay
                && this.activatePromptAt != 0L;
        if (show) {
            this.storeColor();
        }
        long now = System.currentTimeMillis();
        long elapsed = this.promptFadeLastAt == 0L ? 0L
                : Math.min(100L, now - this.promptFadeLastAt);
        this.promptFadeLastAt = now;
        float step = elapsed / 200.0F;
        this.promptAlpha += show ? step : -step;
        this.promptAlpha = Math.max(0.0F, Math.min(1.0F, this.promptAlpha));
    }

    private void checkCamera() {
        if (mc.thePlayer == null || System.currentTimeMillis() < this.takeoverDetectionAt) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!this.takeoverCameraValid) {
            this.takeoverCameraValid = true;
            this.takeoverAccumulated = 0.0F;
            this.takeoverLastFrameAt = now;
            return;
        }
        double yawInput = Math.abs(MathHelper.wrapAngleTo180_float(
                mc.thePlayer.rotationYaw - this.scriptedRotationYaw));
        double pitchInput = Math.abs(mc.thePlayer.rotationPitch - this.scriptedRotationPitch);
        double noiseFloor = SENSITIVITY_QUANTUM * 0.45;

        long elapsed = Math.max(0L, now - this.takeoverLastFrameAt);
        this.takeoverLastFrameAt = now;
        this.takeoverAccumulated -= (float) (elapsed * 0.045);
        if (this.takeoverAccumulated < 0.0F) {
            this.takeoverAccumulated = 0.0F;
        }
        if (yawInput > noiseFloor || pitchInput > noiseFloor) {
            this.takeoverAccumulated += (float) (yawInput + pitchInput);
        }
        if (this.takeoverAccumulated >= TAKEOVER_LIMIT) {
            this.haltSystem(true);
        }
    }


    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.aiming || this.waitingForDelay
                || this.armed && this.isPromptReady() && !this.running) {
            this.suppressInputs();
            this.verifyGuardState();
            if (!this.running) {
                return;
            }
        }
        if (!this.running) {
            return;
        }
        this.suppressSneak();
        this.verifyGuardState();

        boolean manualJump = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode());
        int autoJumpBlocks = this.autoJump.getValue();
        if (autoJumpBlocks > 0) {
            if (mc.thePlayer.onGround && !this.wasOnGround) {
                this.landPosX = mc.thePlayer.posX;
                this.landPosZ = mc.thePlayer.posZ;
            }
            double walked = Math.max(Math.abs(mc.thePlayer.posX - this.landPosX),
                    Math.abs(mc.thePlayer.posZ - this.landPosZ));
            if (mc.thePlayer.onGround && walked >= autoJumpBlocks - 0.8) {
                this.isAutoJumping = true;
            } else if (!mc.thePlayer.onGround) {
                this.isAutoJumping = false;
            }
            this.wasOnGround = mc.thePlayer.onGround;
        } else {
            this.isAutoJumping = false;
            this.wasOnGround = mc.thePlayer.onGround;
        }

        this.updateMovement(-1.0F, this.autoStrafe, manualJump || this.isAutoJumping, false);
        press(mc.gameSettings.keyBindUseItem, true);
        this.setRot(this.baseYaw, this.currentPitch, 0L);
    }

    private void updateMovement(float forward, float strafe, boolean jumping, boolean sprinting) {
        press(mc.gameSettings.keyBindForward, forward > 0.03F);
        press(mc.gameSettings.keyBindBack, forward < -0.03F);
        press(mc.gameSettings.keyBindLeft, strafe > 0.5F);
        press(mc.gameSettings.keyBindRight, strafe < -0.5F);
        press(mc.gameSettings.keyBindJump, jumping);
        press(mc.gameSettings.keyBindSprint, sprinting);
        mc.thePlayer.movementInput.moveForward = forward;
        mc.thePlayer.movementInput.moveStrafe = strafe;
        mc.thePlayer.movementInput.jump = jumping;
        mc.thePlayer.movementInput.sneak = false;
        mc.thePlayer.setSprinting(sprinting);
    }

    private void suppressInputs() {
        press(mc.gameSettings.keyBindForward, false);
        press(mc.gameSettings.keyBindBack, false);
        press(mc.gameSettings.keyBindLeft, false);
        press(mc.gameSettings.keyBindRight, false);
        press(mc.gameSettings.keyBindJump, false);
        press(mc.gameSettings.keyBindSprint, false);
        mc.thePlayer.movementInput.moveForward = 0.0F;
        mc.thePlayer.movementInput.moveStrafe = 0.0F;
        mc.thePlayer.movementInput.jump = false;
        mc.thePlayer.setSprinting(false);
    }

    private void suppressSneak() {
        press(mc.gameSettings.keyBindSneak, false);
        mc.thePlayer.movementInput.sneak = false;
    }

    private void releaseDrivenKeys() {
        for (KeyBinding binding : new KeyBinding[]{
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSprint,
                mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindUseItem}) {
            KeyBindUtil.setKeyBindState(binding.getKeyCode(),
                    KeyBindUtil.isKeyDown(binding.getKeyCode()));
        }
    }

    private boolean checkManualOverride() {
        for (KeyBinding binding : new KeyBinding[]{
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindSprint}) {
            int code = binding.getKeyCode();
            if (!KeyBindUtil.isKeyDown(code)) {
                this.heldFromBefore.remove(code);
                continue;
            }
            if (this.heldFromBefore.contains(code) || this.isDrivenKey(binding)) {
                continue;
            }
            this.haltSystem(true);
            this.logMsg("&cStopped by manual movement key override");
            return true;
        }
        return false;
    }

    private boolean isDrivenKey(KeyBinding binding) {
        if (binding == mc.gameSettings.keyBindBack) {
            return true;
        }
        if (binding == mc.gameSettings.keyBindLeft) {
            return this.autoStrafe > 0.5F;
        }
        if (binding == mc.gameSettings.keyBindRight) {
            return this.autoStrafe < -0.5F;
        }
        return false;
    }

    private void saveInitialHolds() {
        for (KeyBinding binding : new KeyBinding[]{
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak,
                mc.gameSettings.keyBindSprint}) {
            if (KeyBindUtil.isKeyDown(binding.getKeyCode())) {
                this.heldFromBefore.add(binding.getKeyCode());
            }
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onMouseButton(MouseButtonEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.running) {
            if (event.getButton() == 0) {
                press(mc.gameSettings.keyBindAttack, false);
                event.setCancelled(true);
            } else if (event.getButton() == 1) {
                press(mc.gameSettings.keyBindUseItem, this.autoPlaceWindow);
                event.setCancelled(true);
            }
            return;
        }
        if (this.armed && this.shouldSuppressUse() && event.getButton() == 1) {
            event.setCancelled(true);
        }
    }


    private void resetPlacementState() {
        this.currentClientTick = Integer.MIN_VALUE;
        this.placementEvaluationTick = Integer.MIN_VALUE;
        this.lastPlacementAttemptTick = Integer.MIN_VALUE;
        this.lastSuccessfulPlaceTick = Integer.MIN_VALUE;
        this.forceSuppressTick = Integer.MIN_VALUE;
        this.totalPlacePackets = 0L;
        this.placePacketsAtTickBoundary = 0L;
        this.hasLastSentServerPos = false;
        this.lastPlacedPos = null;
        this.lastSupportPos = null;
        this.lastSupportFace = null;
        this.rejectedTargets.clear();
        this.useSuppressed = false;
        this.placingViaModule = false;
    }

    private void initLane() {
        int startX = floor(mc.thePlayer.posX);
        int startY = floor(mc.thePlayer.posY) - 1;
        int startZ = floor(mc.thePlayer.posZ);
        if (this.travelX != 0 && this.travelZ != 0) {
            this.bridgeLaneBlock = 0;
        } else {
            this.bridgeLaneBlock = this.travelX != 0 ? startZ : startX;
        }
        this.bridgeStartProgress = startX * this.travelX + startZ * this.travelZ;

        MovingObjectPosition hit = RotationUtil.rayTrace(mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch, 4.5, 1.0F);
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos() != null) {
            BlockPos hitPos = hit.getBlockPos();
            boolean sameLane;
            if (this.travelX != 0 && this.travelZ != 0) {
                sameLane = Math.abs(hitPos.getX() - startX) <= 2
                        && Math.abs(hitPos.getZ() - startZ) <= 2;
            } else {
                int hitLane = this.travelX != 0 ? hitPos.getZ() : hitPos.getX();
                sameLane = hitLane == this.bridgeLaneBlock
                        && Math.abs(hitPos.getX() - startX) <= 2
                        && Math.abs(hitPos.getZ() - startZ) <= 2;
            }
            int hitProgress = this.laneProgress(hitPos);
            if (sameLane && hitProgress < this.bridgeStartProgress) {
                this.bridgeStartProgress = hitProgress;
            }
        }
        this.floorY = startY;
    }

    private int laneProgress(BlockPos pos) {
        return pos == null ? Integer.MIN_VALUE : pos.getX() * this.travelX + pos.getZ() * this.travelZ;
    }

    private boolean isValidTarget(BlockPos pos) {
        if (!this.running || pos == null) {
            return true;
        }
        if (this.travelX != 0 && this.travelZ != 0) {
            return this.laneProgress(pos) >= this.bridgeStartProgress;
        }
        if (this.autoStrafe != 0.0F) {
            return true;
        }
        int lane = this.travelX != 0 ? pos.getZ() : pos.getX();
        return lane == this.bridgeLaneBlock && this.laneProgress(pos) >= this.bridgeStartProgress;
    }

    private void syncTick() {
        int tick = mc.thePlayer.ticksExisted;
        if (tick == this.currentClientTick) {
            return;
        }
        this.currentClientTick = tick;
        if (mc.thePlayer.onGround) {
            this.floorY = floor(mc.thePlayer.posY) - 1;
        }
    }

    private void processTick() {
        this.pruneRejected();
        if (this.lastPlacedPos != null && !hasSupport(this.lastPlacedPos)) {
            this.lastPlacedPos = null;
            this.lastSupportPos = null;
            this.lastSupportFace = null;
        }
        if (!this.autoPlaceWindow || mc.currentScreen != null || isScaffoldRunning()) {
            if (this.useSuppressed) {
                this.restoreUseState();
            }
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!isValidBlock(held) || !this.canPlaceBelow()) {
            if (this.useSuppressed) {
                this.restoreUseState();
            }
            return;
        }

        Candidate candidate = this.findPlacement(mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch, System.currentTimeMillis() + SEARCH_BUDGET_MS);
        if (this.hasPlacedThisTick() || this.lastPlacementAttemptTick == this.currentClientTick) {
            this.suppressUse();
            return;
        }
        if (candidate == null) {
            return;
        }
        this.lastPlacementAttemptTick = this.currentClientTick;
        this.tryPlace(candidate);
    }

    private boolean tryPlace(Candidate candidate) {
        if (candidate == null || candidate.placed == null || candidate.support == null
                || candidate.face == null) {
            return false;
        }
        if (!this.isValidTarget(candidate.placed) || !this.canPlaceBelow()
                || !isValidBlock(mc.thePlayer.getHeldItem()) || this.hasPlacedThisTick()) {
            return false;
        }
        if (!this.checkFacing(candidate.support, candidate.face)) {
            return false;
        }
        Vec3 hit = this.calcHitVec(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                candidate.support, candidate.face);
        if (hit == null || this.cancelledGhostBlocks.contains(candidate.support)) {
            return false;
        }
        if (!isReplaceable(candidate.placed) || !hasSupport(candidate.support)
                || this.intersectsPlayer(candidate.placed)) {
            return false;
        }

        long before = this.totalPlacePackets;
        this.placingViaModule = true;
        boolean placed;
        try {
            placed = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                    mc.thePlayer.getHeldItem(), candidate.support, candidate.face, hit);
        } finally {
            this.placingViaModule = false;
        }
        boolean packetSent = this.totalPlacePackets > before;
        if (!placed && !packetSent) {
            return false;
        }
        if (!packetSent) {
            this.markRejected(candidate.placed);
            return false;
        }

        this.lastPlacedPos = candidate.placed;
        this.lastSupportPos = candidate.support;
        this.lastSupportFace = candidate.face;
        this.lastSuccessfulPlaceTick = this.currentClientTick;
        this.forceSuppressTick = this.currentClientTick;
        this.blocksPlacedSinceJump++;
        mc.thePlayer.swingItem();
        return true;
    }

    private boolean checkFacing(BlockPos support, EnumFacing face) {
        BlockPos placed = support.offset(face);
        double horizontalPlaced = Math.hypot(placed.getX() + 0.5 - mc.thePlayer.posX,
                placed.getZ() + 0.5 - mc.thePlayer.posZ);
        double horizontalSupport = Math.hypot(support.getX() + 0.5 - mc.thePlayer.posX,
                support.getZ() + 0.5 - mc.thePlayer.posZ);
        double verticalPlaced = Math.abs(placed.getY() + 0.5 - mc.thePlayer.posY);
        double verticalSupport = Math.abs(support.getY() + 0.5 - mc.thePlayer.posY);
        return horizontalPlaced <= horizontalSupport && verticalPlaced <= verticalSupport;
    }

    private boolean hasPlacedThisTick() {
        return this.totalPlacePackets > this.placePacketsAtTickBoundary;
    }

    private boolean canPlaceBelow() {
        return isReplaceable(new BlockPos(floor(mc.thePlayer.posX),
                floor(mc.thePlayer.posY) - 1, floor(mc.thePlayer.posZ)));
    }

    private void suppressUse() {
        press(mc.gameSettings.keyBindUseItem, false);
        this.useSuppressed = true;
    }

    private void restoreUseState() {
        press(mc.gameSettings.keyBindUseItem, this.running ? this.autoPlaceWindow
                : KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode()));
        this.useSuppressed = false;
    }

    private boolean shouldSuppressClicks() {
        if (!this.running || mc.thePlayer == null || mc.currentScreen != null
                || this.currentClientTick == Integer.MIN_VALUE) {
            return false;
        }
        return this.lastSuccessfulPlaceTick == this.currentClientTick
                || this.forceSuppressTick == this.currentClientTick;
    }

    private static boolean isScaffoldRunning() {
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }


    private static final class Candidate {
        private final float pitch;
        private final BlockPos support;
        private final EnumFacing face;
        private final Vec3 hit;
        private final BlockPos placed;

        private Candidate(float pitch, BlockPos support, EnumFacing face, Vec3 hit,
                          BlockPos placed) {
            this.pitch = pitch;
            this.support = support;
            this.face = face;
            this.hit = hit;
            this.placed = placed;
        }
    }

    private static final class Trace {
        private final BlockPos support;
        private final EnumFacing face;
        private final Vec3 hit;

        private Trace(BlockPos support, EnumFacing face, Vec3 hit) {
            this.support = support;
            this.face = face;
            this.hit = hit;
        }
    }

    private Candidate findPlacement(float yaw, float pitch, long deadline) {
        if (System.currentTimeMillis() >= deadline) {
            return null;
        }
        Candidate cursor = this.findCursorRay(yaw, pitch);
        if (cursor != null) {
            return cursor;
        }
        int currentY = this.getCurrentY();
        int strictY = this.getStrictY();
        int previousY = this.getPrevY();
        BlockPos feet = new BlockPos(floor(mc.thePlayer.posX), currentY, floor(mc.thePlayer.posZ));

        List<BlockPos> targets = new ArrayList<BlockPos>();
        this.addTarget(targets, feet);
        this.addTarget(targets, feet.offset(faceFromYaw(yaw)));
        for (int dy = 0; dy <= 2; dy++) {
            int targetY = dy == 0 ? currentY : dy == 1 ? strictY : previousY;
            if (targetY == Integer.MIN_VALUE
                    || dy == 1 && targetY == currentY
                    || dy == 2 && (targetY == currentY || targetY == strictY)) {
                continue;
            }
            this.addTarget(targets, new BlockPos(feet.getX(), targetY, feet.getZ()));
        }

        Candidate best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BlockPos target : targets) {
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            if (!this.isTargetAvailable(target)) {
                continue;
            }
            Candidate candidate = this.findPitch(yaw, pitch, target, deadline, true);
            if (candidate == null) {
                continue;
            }
            double score = this.scoreCandidate(pitch, candidate.pitch, candidate.face, 0.5, 0.5);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private Candidate findCursorRay(float yaw, float pitch) {
        Trace traced = this.raycast(yaw, pitch);
        if (traced == null || traced.face == EnumFacing.DOWN) {
            return null;
        }
        BlockPos target = traced.support.offset(traced.face);
        if (!this.isTargetAvailable(target) || !hasSupport(traced.support)
                || this.rejectSwitch(target, traced.face)) {
            return null;
        }
        return new Candidate(clamp(pitch, -89.0F, 89.0F), traced.support, traced.face,
                traced.hit, target);
    }

    private Candidate findPitch(float yaw, float currentPitchValue, BlockPos target, long deadline,
                                boolean allowNonCursorTarget) {
        if (System.currentTimeMillis() >= deadline || target == null) {
            return null;
        }
        boolean allowNonCursor = allowNonCursorTarget || this.allowNonCursor(target);
        if (!allowNonCursor && !this.isCursorTarget(target, yaw, currentPitchValue)) {
            return null;
        }
        if (!this.isTargetAvailable(target)) {
            return null;
        }
        Candidate best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (EnumFacing placeFace : ALLOWED_PLACE_FACES) {
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            if (this.rejectSwitch(target, placeFace)) {
                continue;
            }
            BlockPos support = target.offset(placeFace.getOpposite());
            if (!hasSupport(support) || !this.inReach(support)) {
                continue;
            }
            for (double primary : FACE_HIT_OFFSETS) {
                for (double secondary : FACE_HIT_OFFSETS) {
                    if (System.currentTimeMillis() >= deadline) {
                        break;
                    }
                    Vec3 hitVec = getFaceHit(support, placeFace, primary, secondary);
                    Candidate candidate =
                            this.buildCandidate(yaw, target, support, placeFace, hitVec);
                    if (candidate == null) {
                        continue;
                    }
                    double score = this.scoreCandidate(currentPitchValue, candidate.pitch,
                            placeFace, primary, secondary);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private double scoreCandidate(float currentPitchValue, float candidatePitch, EnumFacing face,
                                  double primary, double secondary) {
        double pitchPenalty = Math.abs(MathHelper.wrapAngleTo180_float(
                candidatePitch - currentPitchValue));
        double centrePenalty = Math.abs(primary - 0.5) + Math.abs(secondary - 0.5);
        double facePenalty = face == EnumFacing.UP ? 0.0 : 0.35;
        return pitchPenalty + centrePenalty * 2.0 + facePenalty + this.sidePenalty(face);
    }

    private double sidePenalty(EnumFacing face) {
        if (this.isDiagonal() || this.lastSupportFace == null
                || this.lastSupportFace.ordinal() < 2 || face == this.lastSupportFace) {
            return 0.0;
        }
        return 0.8;
    }

    private boolean rejectSwitch(BlockPos target, EnumFacing placeFace) {
        if (target == null || this.isDiagonal() || placeFace.ordinal() < 2
                || this.lastSupportFace == null || this.lastSupportFace.ordinal() < 2
                || placeFace == this.lastSupportFace || this.nearEdge()) {
            return false;
        }
        BlockPos laneSupport = target.offset(this.lastSupportFace.getOpposite());
        return hasSupport(laneSupport) && this.inReach(laneSupport);
    }

    private Candidate buildCandidate(float yaw, BlockPos target, BlockPos support,
                                     EnumFacing placeFace, Vec3 hitVec) {
        if (hitVec == null) {
            return null;
        }
        BlockPos offsetTarget = support.offset(placeFace);
        if (!offsetTarget.equals(target) || !this.heightAccepts(offsetTarget)) {
            return null;
        }
        float pitch = this.pitchTo(hitVec);
        if (!this.lookAligned(yaw, pitch, support, placeFace, target)
                || !this.faceVisible(support, placeFace, hitVec)) {
            return null;
        }
        return new Candidate(pitch, support, placeFace, hitVec, offsetTarget);
    }

    private boolean lookAligned(float yaw, float pitch, BlockPos support, EnumFacing placeFace,
                                BlockPos target) {
        Trace traced = this.raycast(yaw, pitch);
        return traced != null && traced.support.equals(support) && traced.face == placeFace
                && support.offset(placeFace).equals(target);
    }

    private boolean faceVisible(BlockPos support, EnumFacing placeFace, Vec3 hitVec) {
        Vec3 eyes = this.eyes();
        double dx = hitVec.xCoord - eyes.xCoord;
        double dy = hitVec.yCoord - eyes.yCoord;
        double dz = hitVec.zCoord - eyes.zCoord;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4) {
            return false;
        }
        float traceYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tracePitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        MovingObjectPosition hit =
                RotationUtil.rayTrace(traceYaw, tracePitch, distance + 0.5, 1.0F);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && support.equals(hit.getBlockPos()) && hit.sideHit == placeFace;
    }

    private static Vec3 getFaceHit(BlockPos support, EnumFacing placeFace, double primaryOffset,
                                   double secondaryOffset) {
        double primary = Math.max(0.001, Math.min(0.999, primaryOffset));
        double secondary = Math.max(0.001, Math.min(0.999, secondaryOffset));
        double x = support.getX();
        double y = support.getY();
        double z = support.getZ();
        switch (placeFace) {
            case NORTH:
                return new Vec3(x + primary, y + secondary, z + 0.001);
            case SOUTH:
                return new Vec3(x + primary, y + secondary, z + 0.999);
            case EAST:
                return new Vec3(x + 0.999, y + primary, z + secondary);
            case WEST:
                return new Vec3(x + 0.001, y + primary, z + secondary);
            case DOWN:
                return new Vec3(x + primary, y + 0.001, z + secondary);
            default:
                return new Vec3(x + primary, y + 0.999, z + secondary);
        }
    }

    private float pitchTo(Vec3 hitVec) {
        Vec3 eyes = this.eyes();
        double dx = hitVec.xCoord - eyes.xCoord;
        double dz = hitVec.zCoord - eyes.zCoord;
        double dy = hitVec.yCoord - eyes.yCoord;
        return clamp((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))),
                -89.0F, 89.0F);
    }

    private Vec3 calcHitVec(float yaw, float pitch, BlockPos support, EnumFacing face) {
        Vec3 eyes = this.eyes();
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double dirX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double t = -1.0;
        if (face == EnumFacing.NORTH && dirZ != 0.0) {
            t = (support.getZ() - eyes.zCoord) / dirZ;
        } else if (face == EnumFacing.SOUTH && dirZ != 0.0) {
            t = (support.getZ() + 1.0 - eyes.zCoord) / dirZ;
        } else if (face == EnumFacing.WEST && dirX != 0.0) {
            t = (support.getX() - eyes.xCoord) / dirX;
        } else if (face == EnumFacing.EAST && dirX != 0.0) {
            t = (support.getX() + 1.0 - eyes.xCoord) / dirX;
        } else if (face == EnumFacing.UP && dirY != 0.0) {
            t = (support.getY() + 1.0 - eyes.yCoord) / dirY;
        }
        if (t <= 0.0 || t > 5.0) {
            return null;
        }
        double hitX = eyes.xCoord + dirX * t;
        double hitY = eyes.yCoord + dirY * t;
        double hitZ = eyes.zCoord + dirZ * t;
        double clampedX = Math.max(support.getX() + 0.005, Math.min(support.getX() + 0.995, hitX));
        double clampedY = Math.max(support.getY() + 0.005, Math.min(support.getY() + 0.995, hitY));
        double clampedZ = Math.max(support.getZ() + 0.005, Math.min(support.getZ() + 0.995, hitZ));
        double errorSq = (clampedX - hitX) * (clampedX - hitX)
                + (clampedY - hitY) * (clampedY - hitY)
                + (clampedZ - hitZ) * (clampedZ - hitZ);
        return errorSq > 0.03 ? null : new Vec3(clampedX, clampedY, clampedZ);
    }

    private Trace raycast(float yaw, float pitch) {
        MovingObjectPosition hit = RotationUtil.rayTrace(yaw, pitch, this.getReach(), 1.0F);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null
                || hit.sideHit == EnumFacing.DOWN) {
            return null;
        }
        return new Trace(hit.getBlockPos(), hit.sideHit, hit.hitVec);
    }

    private double getReach() {
        return mc.playerController.isInCreativeMode() ? 5.0 : 4.5;
    }


    private boolean isCursorTarget(BlockPos target, float yaw, float pitch) {
        if (target == null) {
            return false;
        }
        if (!this.isDiagonal()) {
            int currentY = this.getCurrentY();
            if (target.equals(this.cursorStart(currentY)) || target.equals(this.cursorPlace(yaw, pitch, currentY))) {
                return true;
            }
            int strictY = this.getStrictY();
            if (strictY != currentY && (target.equals(this.cursorStart(strictY))
                    || target.equals(this.cursorPlace(yaw, pitch, strictY)))) {
                return true;
            }
            return this.cursorInside(target, currentY) || target.equals(this.cursorTarget(currentY));
        }
        return this.isBelowTarget(target, this.getStrictY())
                || this.isBelowTarget(target, this.getCurrentY());
    }

    private boolean isBelowTarget(BlockPos target, int targetY) {
        for (BlockPos candidate : this.getEndpoints(targetY)) {
            if (target.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> getEndpoints(int targetY) {
        List<BlockPos> endpoints = new ArrayList<BlockPos>();
        if (!this.isDiagonal()) {
            if (!mc.thePlayer.onGround) {
                this.addUnique(endpoints, new BlockPos(floor(mc.thePlayer.posX), targetY,
                        floor(mc.thePlayer.posZ)));
                this.addUnique(endpoints, this.motionTarget(targetY, 1.0));
                this.addUnique(endpoints, this.motionTarget(targetY, 1.7));
            }
            this.addUnique(endpoints, this.cursorStart(targetY));
            this.addUnique(endpoints, this.cursorPlace(mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch, targetY));
            this.addUnique(endpoints, this.cursorTarget(targetY));
            return endpoints;
        }
        this.addUnique(endpoints, this.motionTarget(targetY, 1.0));
        this.addUnique(endpoints, this.motionTarget(targetY, 1.7));
        return endpoints;
    }

    private BlockPos motionTarget(int targetY, double multiplier) {
        return new BlockPos(floor(mc.thePlayer.posX + mc.thePlayer.motionX * multiplier), targetY,
                floor(mc.thePlayer.posZ + mc.thePlayer.motionZ * multiplier));
    }

    private BlockPos cursorPlace(float yaw, float pitch, int targetY) {
        Trace traced = this.raycast(yaw, pitch);
        if (traced == null) {
            return null;
        }
        BlockPos offsetTarget = traced.support.offset(traced.face);
        return offsetTarget.getY() != targetY ? null : offsetTarget;
    }

    private BlockPos cursorStart(int targetY) {
        Vec3 point = this.lookIntersect(targetY);
        if (point == null) {
            return null;
        }
        Vec3 look = this.lookVec();
        return new BlockPos(floor(point.xCoord - look.xCoord * 0.03), targetY,
                floor(point.zCoord - look.zCoord * 0.03));
    }

    private BlockPos cursorTarget(int targetY) {
        Vec3 point = this.lookIntersect(targetY);
        return point == null ? null : new BlockPos(floor(point.xCoord), targetY,
                floor(point.zCoord));
    }

    private Vec3 lookIntersect(int targetY) {
        Vec3 eyes = this.eyes();
        Vec3 look = this.lookVec();
        if (Math.abs(look.yCoord) < 1.0E-4) {
            return null;
        }
        double t = (targetY - eyes.yCoord) / look.yCoord;
        if (t <= 0.0) {
            return null;
        }
        return new Vec3(eyes.xCoord + look.xCoord * t, targetY + 0.5,
                eyes.zCoord + look.zCoord * t);
    }

    private boolean cursorInside(BlockPos target, int targetY) {
        if (target == null || target.getY() != targetY) {
            return false;
        }
        Vec3 point = this.lookIntersect(targetY);
        if (point == null) {
            return false;
        }
        return point.xCoord >= target.getX() - 1.0E-6 && point.xCoord <= target.getX() + 1.0 + 1.0E-6
                && point.zCoord >= target.getZ() - 1.0E-6
                && point.zCoord <= target.getZ() + 1.0 + 1.0E-6;
    }

    private boolean allowNonCursor(BlockPos target) {
        if (target == null || this.isDiagonal() || mc.thePlayer.onGround
                || !this.insideColumn() || !this.hasLastFace()
                || this.lastSupportFace == EnumFacing.DOWN) {
            return false;
        }
        if (!target.equals(this.lastSupportPos.offset(this.lastSupportFace))) {
            return false;
        }
        int targetY = target.getY();
        if (targetY != this.getCurrentY() && targetY != this.getStrictY()) {
            return false;
        }
        return Math.abs(target.getX() - floor(mc.thePlayer.posX))
                + Math.abs(target.getZ() - floor(mc.thePlayer.posZ)) <= 1;
    }

    private boolean insideColumn() {
        double half = mc.thePlayer.width / 2.0;
        return floor(mc.thePlayer.posX - half + 1.0E-4) == floor(mc.thePlayer.posX + half - 1.0E-4)
                && floor(mc.thePlayer.posZ - half + 1.0E-4)
                == floor(mc.thePlayer.posZ + half - 1.0E-4);
    }

    private boolean hasLastFace() {
        return this.lastSupportPos != null && this.lastSupportFace != null
                && this.inReach(this.lastSupportPos) && hasSupport(this.lastSupportPos)
                && !isInteractive(this.lastSupportPos);
    }

    private boolean nearEdge() {
        if (this.lastSupportPos == null || this.lastSupportFace == null
                || this.lastSupportFace.ordinal() < 2) {
            return false;
        }
        double localX = mc.thePlayer.posX - this.lastSupportPos.getX();
        double localZ = mc.thePlayer.posZ - this.lastSupportPos.getZ();
        if (pastEdge(this.lastSupportFace, localX, localZ)) {
            return true;
        }
        double motionX = mc.thePlayer.motionX;
        double motionZ = mc.thePlayer.motionZ;
        if (motionX * motionX + motionZ * motionZ < 1.0E-4
                || !movingEdge(this.lastSupportFace, motionX, motionZ)) {
            return false;
        }
        return pastEdge(this.lastSupportFace, localX + motionX * 1.45, localZ + motionZ * 1.45);
    }

    private static boolean pastEdge(EnumFacing face, double localX, double localZ) {
        switch (face) {
            case EAST: return localX >= 0.52;
            case WEST: return localX <= 0.48;
            case SOUTH: return localZ >= 0.52;
            case NORTH: return localZ <= 0.48;
            default: return false;
        }
    }

    private static boolean movingEdge(EnumFacing face, double motionX, double motionZ) {
        switch (face) {
            case EAST: return motionX > 0.0;
            case WEST: return motionX < 0.0;
            case SOUTH: return motionZ > 0.0;
            case NORTH: return motionZ < 0.0;
            default: return false;
        }
    }

    private void addTarget(List<BlockPos> targets, BlockPos candidate) {
        this.addUnique(targets, candidate);
    }

    private void addUnique(List<BlockPos> targets, BlockPos candidate) {
        if (candidate == null || !this.heightAccepts(candidate) || targets.contains(candidate)) {
            return;
        }
        targets.add(candidate);
    }

    private boolean isTargetAvailable(BlockPos pos) {
        return pos != null && !this.isRejected(pos) && !this.intersectsPlayer(pos)
                && isReplaceable(pos) && this.heightAccepts(pos);
    }


    private boolean heightAccepts(BlockPos pos) {
        int targetY = pos.getY();
        int currentY = this.getCurrentY();
        if (targetY == currentY || targetY == this.getStrictY()) {
            return true;
        }
        int previousY = this.getPrevY();
        if (previousY != Integer.MIN_VALUE && targetY == previousY) {
            return true;
        }
        return this.isAscending() && targetY == currentY + 1;
    }

    private double stableRefY() {
        double referenceY = mc.thePlayer.posY;
        if (!mc.thePlayer.onGround && mc.thePlayer.motionY > -0.12 && mc.thePlayer.motionY <= 0.0) {
            referenceY = Math.max(referenceY, mc.thePlayer.lastTickPosY);
        }
        return referenceY;
    }

    private int getCurrentY() {
        return floor(this.stableRefY()) - 1;
    }

    private int getStrictY() {
        if (this.isDiagonal()) {
            return this.getCurrentY();
        }
        double projectedY = this.stableRefY();
        if (!mc.thePlayer.onGround && mc.thePlayer.motionY < -0.12) {
            projectedY = mc.thePlayer.posY + mc.thePlayer.motionY * 0.75;
        }
        return floor(projectedY) - 1;
    }

    private int getPrevY() {
        return floor(mc.thePlayer.lastTickPosY) - 1;
    }

    private boolean isAscending() {
        return !this.isDiagonal() && (mc.thePlayer.motionY > 0.0
                || mc.thePlayer.posY > mc.thePlayer.lastTickPosY + 1.0E-4);
    }


    private boolean isDiagonal() {
        float forwardInput = Math.abs(mc.thePlayer.movementInput.moveForward);
        float strafeInput = Math.abs(mc.thePlayer.movementInput.moveStrafe);
        if (forwardInput >= 0.08F || strafeInput >= 0.08F) {
            return !(forwardInput >= 0.08F && strafeInput >= 0.08F);
        }
        double[] direction = this.dirComponents();
        if (direction == null) {
            return false;
        }
        double angle = Math.toDegrees(Math.atan2(direction[1], direction[0]));
        double norm90 = (angle % 90.0 + 90.0) % 90.0;
        return Math.abs(norm90 - 45.0) <= 18.0;
    }

    private double[] dirComponents() {
        double dirX = mc.thePlayer.posX - mc.thePlayer.lastTickPosX;
        double dirZ = mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ;
        if (dirX * dirX + dirZ * dirZ < 1.0E-4) {
            dirX = mc.thePlayer.motionX;
            dirZ = mc.thePlayer.motionZ;
        }
        return dirX * dirX + dirZ * dirZ < 1.0E-4 ? null : new double[]{dirX, dirZ};
    }

    private static EnumFacing faceFromYaw(float yaw) {
        int index = floor(yaw / 90.0 + 0.5) & 3;
        switch (index) {
            case 0: return EnumFacing.SOUTH;
            case 1: return EnumFacing.WEST;
            case 2: return EnumFacing.NORTH;
            default: return EnumFacing.EAST;
        }
    }


    private boolean intersectsPlayer(BlockPos placePos) {
        if (placePos == null) {
            return false;
        }
        double half = mc.thePlayer.width / 2.0;
        double height = mc.thePlayer.height;
        if (insidePosCell(placePos, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)
                || boxIntersects(placePos, mc.thePlayer.posX, mc.thePlayer.posY,
                mc.thePlayer.posZ, half, height)) {
            return true;
        }
        if (!this.useHistoryChecks(placePos)) {
            return false;
        }
        if (insidePosCell(placePos, mc.thePlayer.lastTickPosX, mc.thePlayer.lastTickPosY,
                mc.thePlayer.lastTickPosZ)
                || boxIntersects(placePos, mc.thePlayer.lastTickPosX, mc.thePlayer.lastTickPosY,
                mc.thePlayer.lastTickPosZ, half, height)) {
            return true;
        }
        return this.hasLastSentServerPos
                && (insidePosCell(placePos, this.lastSentServerPosX, this.lastSentServerPosY,
                this.lastSentServerPosZ)
                || boxIntersects(placePos, this.lastSentServerPosX, this.lastSentServerPosY,
                this.lastSentServerPosZ, half, height));
    }

    private boolean useHistoryChecks(BlockPos placePos) {
        return mc.thePlayer.onGround && (placePos == null || placePos.getY() > this.getCurrentY());
    }

    private static boolean boxIntersects(BlockPos pos, double x, double y, double z, double half,
                                         double height) {
        return x + half > pos.getX() && x - half < pos.getX() + 1.0
                && y + height > pos.getY() && y < pos.getY() + 1.0
                && z + half > pos.getZ() && z - half < pos.getZ() + 1.0;
    }

    private static boolean insidePosCell(BlockPos placePos, double x, double y, double z) {
        int playerY = floor(y);
        return placePos.getX() == floor(x) && placePos.getZ() == floor(z)
                && (placePos.getY() == playerY || placePos.getY() == playerY + 1);
    }

    private boolean inReach(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Vec3 eyes = this.eyes();
        double cx = Math.max(pos.getX(), Math.min(eyes.xCoord, pos.getX() + 1.0));
        double cy = Math.max(pos.getY(), Math.min(eyes.yCoord, pos.getY() + 1.0));
        double cz = Math.max(pos.getZ(), Math.min(eyes.zCoord, pos.getZ() + 1.0));
        double dx = eyes.xCoord - cx;
        double dy = eyes.yCoord - cy;
        double dz = eyes.zCoord - cz;
        return dx * dx + dy * dy + dz * dz <= this.getReach() * this.getReach();
    }


    private boolean isRejected(BlockPos pos) {
        Integer rejectedAt = this.rejectedTargets.get(pos);
        return rejectedAt != null && this.currentClientTick - rejectedAt <= REJECT_TICKS;
    }

    private void markRejected(BlockPos pos) {
        if (pos != null) {
            this.rejectedTargets.put(pos, this.currentClientTick);
        }
    }

    private void pruneRejected() {
        if (this.rejectedTargets.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> iterator =
                this.rejectedTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (this.currentClientTick - entry.getValue() > REJECT_TICKS
                    || !isReplaceable(entry.getKey())) {
                iterator.remove();
            }
        }
    }


    private Vec3 eyes() {
        return new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);
    }

    private Vec3 lookVec() {
        double yawRad = Math.toRadians(mc.thePlayer.rotationYaw);
        double pitchRad = Math.toRadians(mc.thePlayer.rotationPitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch);
    }

    private static boolean hasSupport(BlockPos pos) {
        return !isInteractive(pos) && !isReplaceable(pos);
    }

    private static boolean isInteractive(BlockPos pos) {
        if (mc.theWorld == null) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block instanceof BlockTrapDoor || block instanceof BlockDoor
                || block instanceof BlockContainer || block instanceof BlockJukebox
                || block instanceof BlockFenceGate || block instanceof BlockEnchantmentTable
                || block instanceof BlockAnvil || block instanceof BlockBed
                || block instanceof BlockWorkbench;
    }

    private static boolean isReplaceable(BlockPos pos) {
        return mc.theWorld != null && isReplaceable(pos.getX(), pos.getY(), pos.getZ());
    }


    private void captureGuard() {
        if (this.guardStateCaptured) {
            this.verifyGuardState();
            return;
        }
        if (!this.disableGuard.getValue()) {
            return;
        }
        SafeWalk safeWalk = (SafeWalk) Myau.moduleManager.modules.get(SafeWalk.class);
        if (safeWalk == null) {
            return;
        }
        this.guardWasEnabled = safeWalk.isEnabled();
        this.guardStateCaptured = true;
        if (this.guardWasEnabled) {
            safeWalk.setEnabled(false);
        }
    }

    private void verifyGuardState() {
        if (!this.guardStateCaptured) {
            return;
        }
        SafeWalk safeWalk = (SafeWalk) Myau.moduleManager.modules.get(SafeWalk.class);
        if (safeWalk != null && safeWalk.isEnabled()) {
            safeWalk.setEnabled(false);
        }
    }

    private void resetGuardState() {
        if (!this.guardStateCaptured) {
            return;
        }
        boolean restore = this.guardWasEnabled;
        this.guardStateCaptured = false;
        SafeWalk safeWalk = (SafeWalk) Myau.moduleManager.modules.get(SafeWalk.class);
        if (safeWalk == null) {
            return;
        }
        if (restore != safeWalk.isEnabled()) {
            safeWalk.setEnabled(restore);
        }
    }


    private void doSwap() {
        if (!this.autoSwap.getValue() || mc.thePlayer == null) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        int heldCount = isValidBlock(held) ? held.stackSize : 0;
        if (heldCount > 5) {
            return;
        }
        int bestSlot = -1;
        int bestSize = heldCount;
        for (int slot = 0; slot <= 8; slot++) {
            if (slot == mc.thePlayer.inventory.currentItem) {
                continue;
            }
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (!isValidBlock(stack)) {
                continue;
            }
            if (stack.stackSize > bestSize) {
                bestSize = stack.stackSize;
                bestSlot = slot;
            }
        }
        if (bestSlot != -1) {
            mc.thePlayer.inventory.currentItem = bestSlot;
        }
    }

    private static boolean isValidBlock(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock) || stack.stackSize <= 0) {
            return false;
        }
        String name = Block.blockRegistry.getNameForObject(
                ((ItemBlock) stack.getItem()).getBlock()).getResourcePath().toLowerCase();
        for (String bad : UNPLACEABLE_EXACT) {
            if (name.equals(bad)) {
                return false;
            }
        }
        for (String bad : UNPLACEABLE_CONTAINS) {
            if (name.contains(bad)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHoldingBlock() {
        return isValidBlock(mc.thePlayer == null ? null : mc.thePlayer.getHeldItem());
    }


    private static boolean isSneaking() {
        return mc.thePlayer != null && mc.thePlayer.movementInput != null
                && mc.thePlayer.movementInput.sneak;
    }

    private static void press(KeyBinding binding, boolean state) {
        KeyBindUtil.setKeyBindState(binding.getKeyCode(), state);
    }

    private static boolean isReplaceable(int x, int y, int z) {
        if (mc.theWorld == null) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock();
        String name = Block.blockRegistry.getNameForObject(block).getResourcePath().toLowerCase();
        for (String replaceable : REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) {
                return true;
            }
        }
        return false;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    private void logMsg(String message) {
        if (this.debug.getValue()) {
            ChatUtil.sendFormatted("&bGod Bridge &7| " + message);
        }
    }


    public boolean isRunning() {
        return this.isEnabled() && this.running;
    }

    public float getBaseYaw() {
        return this.baseYaw;
    }

    public float getCurrentPitch() {
        return this.currentPitch;
    }

    public int getTravelX() {
        return this.travelX;
    }

    public int getTravelZ() {
        return this.travelZ;
    }

    public EnumFacing getTravelFacing() {
        return EnumFacing.getHorizontal(MathHelper.floor_double(this.baseYaw / 90.0 + 0.5) & 3);
    }
}
