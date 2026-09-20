package myau.inject;

import myau.Myau;
import myau.access.AccessorEntityLivingBase;
import myau.access.AccessorEntityPlayerSP;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.LivingUpdateEvent;
import myau.events.SprintEvent;
import myau.events.MoveInputEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.PreSlowDownEvent;
import myau.events.UpdateEvent;
import myau.management.LateRotation;
import myau.management.RotationState;
import myau.module.modules.AntiDebuff;
import myau.module.modules.Freecam;
import myau.module.modules.NoSlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public final class LocalPlayerCallbacks {
    public static final String OWNER = "myau/inject/LocalPlayerCallbacks";
    private static float overrideYaw = Float.NaN;
    private static float overridePitch = Float.NaN;
    private static float pendingYaw = Float.NaN;
    private static float pendingPitch = Float.NaN;
    private LocalPlayerCallbacks() {
    }
    private static boolean ready(EntityPlayerSP player) {
        return player.worldObj != null
                && player.worldObj.isBlockLoaded(new BlockPos(player.posX, 0.0, player.posZ));
    }

    public static void onUpdatePre(Object self) {
        try {
            EntityPlayerSP player = (EntityPlayerSP) self;
            if (!ready(player)) {
                return;
            }
            UpdateEvent event = new UpdateEvent(EventType.PRE,
                    AccessorEntityPlayerSP.getLastReportedYaw(player),
                    AccessorEntityPlayerSP.getLastReportedPitch(player),
                    player.rotationYaw, player.rotationPitch);
            EventManager.call(event);
            RotationState.applyState(event.isRotated() && !player.isRiding(),
                    event.getNewYaw(), event.getNewPitch(), event.getPreYaw(),
                    event.isRotating());
            if (event.isRotated()) {
                pendingYaw = player.rotationYaw;
                pendingPitch = player.rotationPitch;
                overrideYaw = event.getNewYaw();
                overridePitch = event.getNewPitch();
            } else {
                pendingYaw = Float.NaN;
                pendingPitch = Float.NaN;
                overrideYaw = Float.NaN;
                overridePitch = Float.NaN;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }

    public static void onUpdatePost() {
        try {
            EntityPlayerSP player = net.minecraft.client.Minecraft.getMinecraft().thePlayer;
            if (player == null || !ready(player)) {
                return;
            }
            if (!Float.isNaN(pendingYaw) && !Float.isNaN(pendingPitch)) {
                AccessorEntityPlayerSP.setLastReportedYaw(player, player.rotationYaw);
                AccessorEntityPlayerSP.setLastReportedPitch(player, player.rotationPitch);
                player.rotationYaw += MathHelper.wrapAngleTo180_float(
                        pendingYaw - player.rotationYaw);
                player.rotationPitch = pendingPitch;
                player.prevRotationYaw = player.rotationYaw;
                player.prevRotationPitch = player.rotationPitch;
                player.prevRenderArmYaw = player.rotationYaw
                        - (player.renderArmYaw - player.prevRenderArmYaw) * 2.0F;
                player.renderArmYaw = player.rotationYaw;
            }
            EventManager.call(new UpdateEvent(EventType.POST,
                    AccessorEntityPlayerSP.getLastReportedYaw(player),
                    AccessorEntityPlayerSP.getLastReportedPitch(player),
                    player.rotationYaw, player.rotationPitch));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static boolean isRidingDuringUpdate(EntityPlayerSP player) {
        try {
            float lateBodyYaw = LateRotation.consumeBodyYaw(player.ticksExisted);
            if (!Float.isNaN(lateBodyYaw)) {
                player.renderYawOffset = RotationState.bodyYawToward(lateBodyYaw, player.prevRenderYawOffset);
                player.rotationYawHead = lateBodyYaw;
            }
            float lateYaw = LateRotation.consumeYaw(player.ticksExisted);
            if (!Float.isNaN(lateYaw) && Float.isNaN(overrideYaw)) {
                pendingYaw = player.rotationYaw;
                pendingPitch = player.rotationPitch;
                overrideYaw = lateYaw;
                overridePitch = player.rotationPitch;
                player.rotationYawHead = lateYaw;
                LateRotation.scheduleBodyYaw(lateYaw, player.ticksExisted + 1);
            }
            if (!Float.isNaN(overrideYaw) && !Float.isNaN(overridePitch)) {
                player.rotationYaw = overrideYaw;
                player.rotationPitch = overridePitch;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return player.isRiding();
    }
    public static boolean isCurrentViewEntityForMotion(EntityPlayerSP player) {
        try {
            return Minecraft.getMinecraft().getRenderViewEntity() == player || Freecam.freeEntity != null;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return Minecraft.getMinecraft().getRenderViewEntity() == player;
        }
    }
    public static void onMotionUpdate() {
        try {
            EventManager.call(new PlayerUpdateEvent());
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void onLivingUpdate() {
        try {
            EventManager.call(new LivingUpdateEvent());
            EventManager.call(new SprintEvent());
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void onMoveInput() {
        try {
            EventManager.call(new MoveInputEvent());
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    private static int slowDownTick = Integer.MIN_VALUE;
    private static boolean slowDownCancelled = false;

    public static boolean isUsingItem(EntityPlayerSP player) {
        try {
            if (Myau.moduleManager != null && player.ticksExisted != slowDownTick) {
                slowDownTick = player.ticksExisted;
                PreSlowDownEvent event = new PreSlowDownEvent();
                EventManager.call(event);
                slowDownCancelled = event.isCancelled();
            }
            if (slowDownCancelled) {
                return false;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return player.isUsingItem();
    }
    public static boolean isPotionActive(EntityPlayerSP player, Potion potion) {
        try {
            if (potion == Potion.confusion && Myau.moduleManager != null) {
                AntiDebuff antiDebuff = (AntiDebuff) Myau.moduleManager.modules.get(AntiDebuff.class);
                if (antiDebuff.isEnabled() && antiDebuff.nausea.getValue()) {
                    return false;
                }
            }
            java.util.Map effects = AccessorEntityLivingBase.getActivePotionsMap(player);
            return effects != null && effects.containsKey(Integer.valueOf(potion.id));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
}
