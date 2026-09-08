package myau.inject;

import myau.Myau;
import myau.event.EventManager;
import myau.events.KnockbackEvent;
import myau.events.MoveEvent;
import myau.events.SafeWalkEvent;
import myau.events.StrafeEvent;
import myau.management.RotationState;
import myau.module.modules.Jesus;
import myau.module.modules.KeepSprint;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

public final class EntityCallbacks {
    public static final String OWNER = "myau/inject/EntityCallbacks";
    private EntityCallbacks() {
    }
    private static Object movingEntity;
    private static Object jumpingEntity;
    private static Object headingEntity;

    public static void enterMoveEntity(Object self) {
        movingEntity = self;
    }
    public static void enterJump(Object self) {
        jumpingEntity = self;
    }
    public static void enterMoveEntityWithHeading(Object self) {
        headingEntity = self;
    }
    public static boolean setVelocity(Object self, double x, double y, double z) {
        try {
            if (!(self instanceof EntityPlayerSP)) {
                return false;
            }
            KnockbackEvent event = new KnockbackEvent(x, y, z);
            EventManager.call(event);
            if (!event.isCancelled()) {
                return false;
            }
            Entity entity = (Entity) self;
            entity.motionX = event.getX();
            entity.motionY = event.getY();
            entity.motionZ = event.getZ();
            return true;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static boolean setAngles(Object self) {
        try {
            return self instanceof EntityPlayerSP
                    && Myau.rotationManager != null
                    && Myau.rotationManager.isRotated();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    public static boolean safeWalk(boolean value) {
        Object self = movingEntity;
        try {
            if (!(self instanceof EntityPlayerSP)) {
                return value;
            }
            SafeWalkEvent event = new SafeWalkEvent(value);
            EventManager.call(event);
            return event.isSafeWalk();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return value;
        }
    }
    public static float jumpYaw(float value) {
        Object self = jumpingEntity;
        try {
            return self instanceof EntityPlayerSP && RotationState.isActived()
                    ? RotationState.getSmoothedYaw() * (float) (Math.PI / 180.0)
                    : value;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return value;
        }
    }
    public static void moveFlying(EntityLivingBase entity, float strafe, float forward,
                                  float friction) {
        try {
            if (!(entity instanceof EntityPlayerSP)) {
                entity.moveFlying(strafe, forward, friction);
                return;
            }
            StrafeEvent event = new StrafeEvent(strafe, forward, friction);
            EventManager.call(event);
            boolean active = RotationState.isActived();
            float yaw = entity.rotationYaw;
            if (active) {
                entity.rotationYaw = RotationState.getSmoothedYaw();
            }
            entity.moveFlying(event.getStrafe(), event.getForward(), event.getFriction());
            if (active) {
                entity.rotationYaw = yaw;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            entity.moveFlying(strafe, forward, friction);
        }
    }
    public static void moveEntity(EntityLivingBase entity, double x, double y, double z) {
        try {
            if (!(entity instanceof EntityPlayerSP)) {
                entity.moveEntity(x, y, z);
                return;
            }
            MoveEvent event = new MoveEvent(x, y, z);
            EventManager.call(event);
            entity.moveEntity(event.getPosX(), event.getPosY(), event.getPosZ());
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            entity.moveEntity(x, y, z);
        }
    }
    public static float depthStrider(float value) {
        Object self = headingEntity;
        try {
            if (!(self instanceof EntityPlayerSP)) {
                return value;
            }
            EntityLivingBase entity = (EntityLivingBase) self;
            if (value != (float) EnchantmentHelper.getDepthStriderModifier(entity)) {
                return value;
            }
            if (Myau.moduleManager == null) {
                return value;
            }
            Jesus jesus = (Jesus) Myau.moduleManager.modules.get(Jesus.class);
            if (jesus.isEnabled() && (!jesus.groundOnly.getValue() || entity.onGround)) {
                return Math.max(value, jesus.speed.getValue());
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return value;
    }
    public static double attackSlowdown(double speed) {
        try {
            if (Myau.moduleManager == null) {
                return speed;
            }
            KeepSprint keepSprint = (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
            if (keepSprint.isEnabled() && keepSprint.shouldKeepSprint()) {
                return speed + (1.0 - speed)
                        * (1.0 - keepSprint.slowdown.getValue().doubleValue() / 100.0);
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return speed;
    }

    public static void setSprinting(EntityPlayer player, boolean sprinting) {
        try {
            if (Myau.moduleManager != null) {
                KeepSprint keepSprint =
                        (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
                if (keepSprint.isEnabled() && keepSprint.shouldKeepSprint()
                        && !keepSprint.shouldDropSprintAfterHit()) {
                    return;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        player.setSprinting(sprinting);
    }
}
