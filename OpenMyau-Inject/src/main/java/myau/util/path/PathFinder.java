package myau.util.path;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBarrier;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockCactus;
import net.minecraft.block.BlockCarpet;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockEndPortal;
import net.minecraft.block.BlockEndPortalFrame;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.BlockPistonMoving;
import net.minecraft.block.BlockSkull;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockSnowBlock;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockVine;
import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockWeb;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

public final class PathFinder {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double MIN_DISTANCE_SQUARED = 9.5;
    private static final Vec3[] OFFSETS = new Vec3[]{
            new Vec3(1.0, 0.0, 0.0),
            new Vec3(-1.0, 0.0, 0.0),
            new Vec3(0.0, 0.0, 1.0),
            new Vec3(0.0, 0.0, -1.0)
    };
    private static final Comparator<PathNode> COMPARATOR =
            (first, second) -> (int) (first.getTargetDistance() + first.getTotalCost()
                    - (second.getTargetDistance() + second.getTotalCost()));

    private final ArrayList<PathNode> hubsToWork = new ArrayList<>();
    private final ArrayList<PathNode> hubs = new ArrayList<>();
    private final HashMap<BlockPos, PathNode> workIndex = new HashMap<>();
    private final HashMap<BlockPos, PathNode> hubIndex = new HashMap<>();
    private ArrayList<Vec3> path = new ArrayList<>();
    private final Vec3 start;
    private final Vec3 end;

    public PathFinder(Vec3 start, Vec3 end) {
        this.start = floor(start);
        this.end = floor(end);
    }

    public ArrayList<Vec3> getPath() {
        return this.path;
    }

    public void compute() {
        this.compute(1000, 4);
    }

    public void compute(int iterations, int branching) {
        this.path.clear();
        this.hubsToWork.clear();
        this.workIndex.clear();
        ArrayList<Vec3> startPath = new ArrayList<>();
        startPath.add(this.start);
        this.addToWork(new PathNode(this.start, null, startPath, this.start.squareDistanceTo(this.end), 0.0, 0.0));
        search:
        for (int iteration = 0; iteration < iterations; iteration++) {
            this.hubsToWork.sort(COMPARATOR);
            int worked = 0;
            if (this.hubsToWork.isEmpty()) {
                break;
            }
            for (PathNode node : new ArrayList<>(this.hubsToWork)) {
                if (++worked > branching) {
                    continue search;
                }
                this.hubsToWork.remove(node);
                this.workIndex.remove(toKey(node.getPosition()));
                this.hubs.add(node);
                this.hubIndex.put(toKey(node.getPosition()), node);
                for (Vec3 offset : OFFSETS) {
                    Vec3 next = floor(node.getPosition().addVector(offset.xCoord, offset.yCoord, offset.zCoord));
                    if (isPositionValid(next, false) && this.link(node, next, 0.0)) {
                        break search;
                    }
                }
                Vec3 above = floor(node.getPosition().addVector(0.0, 1.0, 0.0));
                if (isPositionValid(above, false) && this.link(node, above, 0.0)) {
                    break;
                }
                Vec3 below = floor(node.getPosition().addVector(0.0, -1.0, 0.0));
                if (isPositionValid(below, false) && this.link(node, below, 0.0)) {
                    break;
                }
            }
        }
        if (this.hubs.isEmpty()) {
            return;
        }
        this.hubs.sort(COMPARATOR);
        this.path = this.hubs.get(0).getPath();
    }

    private void addToWork(PathNode node) {
        this.hubsToWork.add(node);
        this.workIndex.put(toKey(node.getPosition()), node);
    }

    private PathNode findNode(Vec3 position) {
        BlockPos key = toKey(position);
        PathNode node = this.hubIndex.get(key);
        return node != null ? node : this.workIndex.get(key);
    }

    private static BlockPos toKey(Vec3 position) {
        return new BlockPos((int) position.xCoord, (int) position.yCoord, (int) position.zCoord);
    }

    private boolean link(PathNode parent, Vec3 position, double cost) {
        PathNode existing = this.findNode(position);
        double totalCost = cost;
        if (parent != null) {
            totalCost += parent.getTotalCost();
        }
        if (existing != null) {
            if (existing.getCost() <= cost) {
                return false;
            }
            ArrayList<Vec3> path = new ArrayList<>(parent.getPath());
            path.add(position);
            existing.setPosition(position);
            existing.setParent(parent);
            existing.setPath(path);
            existing.setTargetDistance(position.squareDistanceTo(this.end));
            existing.setCost(cost);
            existing.setTotalCost(totalCost);
            return false;
        }
        if (!samePosition(position, this.end) && position.squareDistanceTo(this.end) > MIN_DISTANCE_SQUARED) {
            ArrayList<Vec3> path = new ArrayList<>(parent.getPath());
            path.add(position);
            this.addToWork(new PathNode(position, parent, path, position.squareDistanceTo(this.end), cost, totalCost));
            return false;
        }
        if (parent == null) {
            return false;
        }
        this.path.clear();
        this.path = parent.getPath();
        this.path.add(position);
        return true;
    }

    public static boolean isPositionValid(Vec3 position, boolean requireGround) {
        return isPositionValid((int) position.xCoord, (int) position.yCoord, (int) position.zCoord, requireGround);
    }

    public static boolean isPositionValid(int x, int y, int z, boolean requireGround) {
        BlockPos below = new BlockPos(x, y - 1, z);
        if (isBlockSolid(new BlockPos(x, y, z))) {
            return false;
        }
        if (isBlockSolid(new BlockPos(x, y + 1, z))) {
            return false;
        }
        if (!isBlockSolid(below) && requireGround) {
            return false;
        }
        return isSafeToWalkOn(below);
    }

    private static boolean isBlockSolid(BlockPos blockPos) {
        if (mc.theWorld == null) {
            return true;
        }
        Block block = mc.theWorld.getBlockState(blockPos).getBlock();
        return block.isFullBlock()
                || block instanceof BlockSlab
                || block instanceof BlockStairs
                || block instanceof BlockCactus
                || block instanceof BlockChest
                || block instanceof BlockEnderChest
                || block instanceof BlockSkull
                || block instanceof BlockPane
                || block instanceof BlockFence
                || block instanceof BlockWall
                || block instanceof BlockGlass
                || block instanceof BlockPistonBase
                || block instanceof BlockPistonExtension
                || block instanceof BlockPistonMoving
                || block instanceof BlockStainedGlass
                || block instanceof BlockTrapDoor
                || block instanceof BlockEndPortalFrame
                || block instanceof BlockEndPortal
                || block instanceof BlockBed
                || block instanceof BlockWeb
                || block instanceof BlockBarrier
                || block instanceof BlockLadder
                || block instanceof BlockLeaves
                || block instanceof BlockSnow
                || block instanceof BlockSnowBlock
                || block instanceof BlockCarpet
                || block instanceof BlockDoor
                || block instanceof BlockVine
                || block instanceof BlockLilyPad;
    }

    private static boolean isSafeToWalkOn(BlockPos blockPos) {
        if (mc.theWorld == null) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(blockPos).getBlock();
        return !(block instanceof BlockFence) && !(block instanceof BlockWall);
    }

    private static boolean samePosition(Vec3 first, Vec3 second) {
        return first.xCoord == second.xCoord && first.yCoord == second.yCoord && first.zCoord == second.zCoord;
    }

    public static Vec3 floor(Vec3 vector) {
        return new Vec3(Math.floor(vector.xCoord), Math.floor(vector.yCoord), Math.floor(vector.zCoord));
    }
}
