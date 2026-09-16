package myau.util.path;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class TeleportPath {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double DEFAULT_MAX_DISTANCE = 9.5;

    private TeleportPath() {
    }

    public static List<Vec3> build(Vec3 start, Vec3 end, boolean includeEnd) {
        return build(start, end, includeEnd, DEFAULT_MAX_DISTANCE);
    }

    public static List<Vec3> build(Vec3 start, Vec3 end, boolean includeEnd, double maxDistance) {
        if (mc.theWorld == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(new BlockPos(start));
        if (state == null) {
            return null;
        }
        Block block = state.getBlock();
        if (block == null) {
            return null;
        }
        if (!canPassThrough(block)) {
            start = start.addVector(0.0, 1.0, 0.0);
        }
        PathFinder finder = new PathFinder(start, end);
        finder.compute();
        List<Vec3> points = new ArrayList<>();
        ArrayList<Vec3> raw = finder.getPath();
        int index = 0;
        Vec3 previous = null;
        Vec3 anchor = null;
        for (Vec3 point : raw) {
            if (index == 0 || index == raw.size() - 1) {
                points.add(point.addVector(0.5, 0.0, 0.5));
                anchor = point;
            } else {
                boolean reachable = true;
                if (point.squareDistanceTo(anchor) > maxDistance * maxDistance) {
                    reachable = false;
                } else {
                    double minX = Math.min(anchor.xCoord, point.xCoord);
                    double minY = Math.min(anchor.yCoord, point.yCoord);
                    double minZ = Math.min(anchor.zCoord, point.zCoord);
                    double maxX = Math.max(anchor.xCoord, point.xCoord);
                    double maxY = Math.max(anchor.yCoord, point.yCoord);
                    double maxZ = Math.max(anchor.zCoord, point.zCoord);
                    check:
                    for (int x = (int) minX; x <= maxX; x++) {
                        for (int y = (int) minY; y <= maxY; y++) {
                            for (int z = (int) minZ; z <= maxZ; z++) {
                                if (!PathFinder.isPositionValid(x, y, z, false)) {
                                    reachable = false;
                                    break check;
                                }
                            }
                        }
                    }
                }
                if (!reachable) {
                    points.add(previous.addVector(0.5, 0.0, 0.5));
                    anchor = previous;
                }
            }
            previous = point;
            index++;
        }
        if (includeEnd) {
            points.add(end);
        }
        return points;
    }

    private static boolean canPassThrough(Block block) {
        Material material = block.getMaterial();
        return material == Material.air
                || material == Material.plants
                || material == Material.vine
                || block == Blocks.ladder
                || block == Blocks.water
                || block == Blocks.flowing_water
                || block == Blocks.wall_sign
                || block == Blocks.standing_sign;
    }
}
