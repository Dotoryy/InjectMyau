package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

public final class CombatTargeting {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private CombatTargeting() {
    }

    public static EntityPlayer findTarget(double maxDistanceSq) {
        return findTarget(maxDistanceSq, true);
    }

    public static EntityPlayer findTarget(double maxDistanceSq, boolean ignoreTeammates) {
        EntityPlayer mouseOverTarget = getMouseOverTarget(maxDistanceSq, ignoreTeammates);
        return mouseOverTarget != null
                ? mouseOverTarget : findClosestTarget(maxDistanceSq, ignoreTeammates);
    }

    public static EntityPlayer findClosestTarget(double maxDistanceSq) {
        return findClosestTarget(maxDistanceSq, true);
    }

    public static EntityPlayer findClosestTarget(double maxDistanceSq, boolean ignoreTeammates) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        EntityPlayer closest = null;
        double closestDistanceSq = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!isValidPlayer(player, maxDistanceSq, ignoreTeammates)) {
                continue;
            }
            double distanceSq = distanceSqFromEyeToClosestOnAABB(player);
            if (distanceSq < closestDistanceSq) {
                closestDistanceSq = distanceSq;
                closest = player;
            }
        }
        return closest;
    }

    public static EntityPlayer getMouseOverTarget(double maxDistanceSq) {
        return getMouseOverTarget(maxDistanceSq, true);
    }

    public static EntityPlayer getMouseOverTarget(double maxDistanceSq, boolean ignoreTeammates) {
        if (mc == null || mc.objectMouseOver == null) {
            return null;
        }
        MovingObjectPosition objectMouseOver = mc.objectMouseOver;
        return asValidPlayer(objectMouseOver.entityHit, maxDistanceSq, ignoreTeammates);
    }

    public static EntityPlayer asValidPlayer(Entity entity, double maxDistanceSq) {
        return asValidPlayer(entity, maxDistanceSq, true);
    }

    public static EntityPlayer asValidPlayer(Entity entity, double maxDistanceSq,
                                             boolean ignoreTeammates) {
        if (!(entity instanceof EntityPlayer)) {
            return null;
        }
        EntityPlayer player = (EntityPlayer) entity;
        return isValidPlayer(player, maxDistanceSq, ignoreTeammates) ? player : null;
    }

    public static boolean isValidPlayer(EntityPlayer player, double maxDistanceSq) {
        return isValidPlayer(player, maxDistanceSq, true);
    }

    public static boolean isValidPlayer(EntityPlayer player, double maxDistanceSq,
                                        boolean ignoreTeammates) {
        return isTrackablePlayer(player, ignoreTeammates) && isWithinRange(player, maxDistanceSq);
    }

    public static boolean isTrackablePlayer(EntityPlayer player) {
        return isTrackablePlayer(player, true);
    }

    public static boolean isTrackablePlayer(EntityPlayer player, boolean ignoreTeammates) {
        if (mc.thePlayer == null || mc.theWorld == null || player == null
                || player == mc.thePlayer || player.isDead || player.deathTime != 0) {
            return false;
        }
        if (TeamUtil.isFriend(player) || TeamUtil.isBot(player)) {
            return false;
        }
        return !ignoreTeammates || !TeamUtil.isSameTeam(player);
    }

    public static boolean isWithinRange(EntityPlayer player, double maxDistanceSq) {
        return player != null && distanceSqFromEyeToClosestOnAABB(player) <= maxDistanceSq;
    }

    public static double distanceSqFromEyeToClosestOnAABB(Entity entity) {
        if (entity == null || mc.thePlayer == null) {
            return Double.MAX_VALUE;
        }
        double distance = RotationUtil.distanceToEntity(entity);
        return distance * distance;
    }
}
