package myau.module.modules;

import myau.Myau;
import myau.access.AccessorEntityPlayerSP;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.JumpEvent;
import myau.event.types.EventType;
import myau.events.UpdateEvent;
import myau.management.LateRotation;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class Jump45 extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float INPUT_DAMPING = 0.98F;
    private static final float SPRINT_JUMP_BOOST = 0.2F;
    private static final float DEGREES_TO_RADIANS = (float) Math.PI / 180.0F;
    private static final float TRIG_TABLE_STEP = 0.005493164F;
    private static final int GCD_SEARCH_RADIUS = 4;
    private static final int JUMP_YAW_SEARCH_RADIUS = 4;
    private static final float AIR_STRAFE_OFFSET = 45.0F;
    private static final int AIR_STRAFE_PRIORITY = 0;

    public final BooleanProperty holdingBlocks = new BooleanProperty("holding-blocks", true);
    public final BooleanProperty holdingThrowable = new BooleanProperty("holding-throwable", true);
    public final BooleanProperty holdingRod = new BooleanProperty("holding-rod", true);
    public final BooleanProperty targeting = new BooleanProperty("targeting", false);
    public final BooleanProperty autoStrafe = new BooleanProperty("auto-strafe", true);

    private boolean airStrafe = false;

    public Jump45() {
        super("45 Jump", false);
    }

    @Override
    public void onDisabled() {
        LateRotation.clear();
        this.airStrafe = false;
    }

    @EventTarget(Priority.LOWEST)
    public void onJump(JumpEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.thePlayer.movementInput == null) {
            return;
        }
        if (!mc.thePlayer.onGround || RotationState.isActived() || !this.canActivateForHeldItem()) {
            return;
        }
        if (this.targeting.getValue() && !this.isTargeting()) {
            return;
        }
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward < 0.99F) {
            return;
        }
        if (Math.abs(strafe) < 0.99F) {
            if (this.autoStrafe.getValue() && strafe == 0.0F && this.isForwardOnly()) {
                this.airStrafe = true;
            }
            return;
        }
        RotationPlan plan = this.createRotationPlan(strafe);
        if (plan == null) {
            return;
        }
        LateRotation.setYaw(plan.packetYaw, mc.thePlayer.ticksExisted);
        event.setYaw(plan.jumpYaw);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || !this.airStrafe) {
            return;
        }
        if (!this.isEnabled() || !this.autoStrafe.getValue() || mc.thePlayer == null
                || mc.thePlayer.onGround || !this.isForwardOnly()) {
            this.airStrafe = false;
            return;
        }
        if (event.isRotated()) {
            return;
        }
        float yaw = mc.thePlayer.rotationYaw + AIR_STRAFE_OFFSET;
        event.setRotation(yaw, mc.thePlayer.rotationPitch, AIR_STRAFE_PRIORITY);
        event.setPervRotation(yaw, AIR_STRAFE_PRIORITY);
    }

    private boolean isForwardOnly() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                && !mc.gameSettings.keyBindBack.isKeyDown()
                && !mc.gameSettings.keyBindLeft.isKeyDown()
                && !mc.gameSettings.keyBindRight.isKeyDown();
    }

    private RotationPlan createRotationPlan(float strafeInput) {
        float acceleration = this.getGroundAcceleration();
        if (!Float.isFinite(acceleration) || acceleration <= 0.0F) {
            return null;
        }
        double[] actualInput = this.getMovementAddition(mc.thePlayer.rotationYaw,
                strafeInput * INPUT_DAMPING, INPUT_DAMPING, acceleration);
        double actualInputLength = length(actualInput);
        float predictedForward = INPUT_DAMPING * acceleration;
        double predictedLength = (double) (SPRINT_JUMP_BOOST + predictedForward);
        if (actualInputLength <= 0.0 || predictedLength <= 0.0) {
            return null;
        }
        double denominator = 2.0 * predictedLength * actualInputLength;
        double cosine = (predictedLength * predictedLength + actualInputLength * actualInputLength
                - (double) (SPRINT_JUMP_BOOST * SPRINT_JUMP_BOOST)) / denominator;
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        float inputYaw = yawFromVector(actualInput, mc.thePlayer.rotationYaw);
        float correction = (float) Math.toDegrees(Math.acos(cosine));
        float idealPacketYaw = inputYaw + Math.copySign(correction, strafeInput);
        float lastReportedYaw = AccessorEntityPlayerSP.getLastReportedYaw(mc.thePlayer);
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        double gcd = (double) (sensitivity * sensitivity * sensitivity) * 1.2;
        if (gcd <= 0.0) {
            return this.evaluatePacketYaw(unwrapYaw(idealPacketYaw, lastReportedYaw), actualInput);
        }
        float delta = MathHelper.wrapAngleTo180_float(idealPacketYaw - lastReportedYaw);
        long closestStep = Math.round((double) delta / gcd);
        RotationPlan best = null;
        for (int i = -GCD_SEARCH_RADIUS; i <= GCD_SEARCH_RADIUS; i++) {
            float packetYaw = lastReportedYaw + (float) ((double) (closestStep + (long) i) * gcd);
            RotationPlan candidate = this.evaluatePacketYaw(packetYaw, actualInput);
            if (best == null || candidate.error < best.error) {
                best = candidate;
            }
        }
        return best;
    }

    private RotationPlan evaluatePacketYaw(float packetYaw, double[] actualInput) {
        double[] predicted = this.getMovementAddition(packetYaw, 0.0F, INPUT_DAMPING,
                this.getGroundAcceleration());
        double[] boost = getForwardVector(packetYaw, SPRINT_JUMP_BOOST);
        predicted[0] += boost[0];
        predicted[1] += boost[1];
        double[] requiredJump = new double[]{predicted[0] - actualInput[0], predicted[1] - actualInput[1]};
        float baseJumpYaw = yawFromVector(requiredJump, packetYaw);
        RotationPlan best = null;
        for (int i = -JUMP_YAW_SEARCH_RADIUS; i <= JUMP_YAW_SEARCH_RADIUS; i++) {
            float jumpYaw = baseJumpYaw + (float) i * TRIG_TABLE_STEP;
            double[] jump = getForwardVector(jumpYaw, SPRINT_JUMP_BOOST);
            double[] actual = new double[]{actualInput[0] + jump[0], actualInput[1] + jump[1]};
            double error = distance(actual, predicted);
            if (best == null || error < best.error) {
                best = new RotationPlan(packetYaw, jumpYaw, error);
            }
        }
        return best;
    }

    private boolean canActivateForHeldItem() {
        boolean hasHeldItemFilter = this.holdingBlocks.getValue() || this.holdingRod.getValue()
                || this.holdingThrowable.getValue();
        if (!hasHeldItemFilter) {
            return true;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return false;
        }
        Item item = held.getItem();
        return this.holdingBlocks.getValue() && item instanceof ItemBlock
                || this.holdingRod.getValue() && item instanceof ItemFishingRod
                || this.holdingThrowable.getValue() && (item instanceof ItemSnowball
                || item instanceof ItemEgg
                || item instanceof ItemEnderPearl
                || item == Items.experience_bottle
                || item instanceof ItemPotion && ItemPotion.isSplash(held.getMetadata()));
    }

    private boolean isTargeting() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.getTarget() != null) {
            return true;
        }
        return mc.gameSettings.keyBindAttack.isKeyDown() && mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit != null;
    }

    private float getGroundAcceleration() {
        BlockPos underPlayer = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        Block block = mc.theWorld.getBlockState(underPlayer).getBlock();
        float friction = block.slipperiness * 0.91F;
        return mc.thePlayer.getAIMoveSpeed() * (0.16277136F / (friction * friction * friction));
    }

    private double[] getMovementAddition(float yaw, float strafe, float forward, float acceleration) {
        float length = strafe * strafe + forward * forward;
        if (length < 1.0E-4F) {
            return new double[]{0.0, 0.0};
        }
        length = MathHelper.sqrt_float(length);
        if (length < 1.0F) {
            length = 1.0F;
        }
        float scale = acceleration / length;
        strafe *= scale;
        forward *= scale;
        float sin = MathHelper.sin(yaw * DEGREES_TO_RADIANS);
        float cos = MathHelper.cos(yaw * DEGREES_TO_RADIANS);
        return new double[]{(double) (strafe * cos - forward * sin), (double) (forward * cos + strafe * sin)};
    }

    private static double[] getForwardVector(float yaw, float magnitude) {
        float radians = yaw * DEGREES_TO_RADIANS;
        return new double[]{(double) (-MathHelper.sin(radians) * magnitude),
                (double) (MathHelper.cos(radians) * magnitude)};
    }

    private static float yawFromVector(double[] vector, float referenceYaw) {
        float yaw = (float) Math.toDegrees(Math.atan2(-vector[0], vector[1]));
        return unwrapYaw(yaw, referenceYaw);
    }

    private static float unwrapYaw(float yaw, float prevYaw) {
        return prevYaw + (((yaw - prevYaw + 180.0F) % 360.0F + 360.0F) % 360.0F - 180.0F);
    }

    private static double length(double[] vector) {
        return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]);
    }

    private static double distance(double[] self, double[] other) {
        double deltaX = self[0] - other[0];
        double deltaZ = self[1] - other[1];
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    private static final class RotationPlan {
        private final float packetYaw;
        private final float jumpYaw;
        private final double error;

        private RotationPlan(float packetYaw, float jumpYaw, double error) {
            this.packetYaw = packetYaw;
            this.jumpYaw = jumpYaw;
            this.error = error;
        }
    }
}
