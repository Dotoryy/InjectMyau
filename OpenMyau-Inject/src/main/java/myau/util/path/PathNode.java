package myau.util.path;

import net.minecraft.util.Vec3;

import java.util.ArrayList;

public class PathNode {
    private Vec3 position;
    private PathNode parent;
    private ArrayList<Vec3> path;
    private double targetDistance;
    private double cost;
    private double totalCost;

    public PathNode(Vec3 position, PathNode parent, ArrayList<Vec3> path, double targetDistance, double cost, double totalCost) {
        this.position = position;
        this.parent = parent;
        this.path = path;
        this.targetDistance = targetDistance;
        this.cost = cost;
        this.totalCost = totalCost;
    }

    public Vec3 getPosition() {
        return this.position;
    }

    public PathNode getParent() {
        return this.parent;
    }

    public ArrayList<Vec3> getPath() {
        return this.path;
    }

    public double getTargetDistance() {
        return this.targetDistance;
    }

    public double getCost() {
        return this.cost;
    }

    public double getTotalCost() {
        return this.totalCost;
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public void setParent(PathNode parent) {
        this.parent = parent;
    }

    public void setPath(ArrayList<Vec3> path) {
        this.path = path;
    }

    public void setTargetDistance(double targetDistance) {
        this.targetDistance = targetDistance;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }
}
