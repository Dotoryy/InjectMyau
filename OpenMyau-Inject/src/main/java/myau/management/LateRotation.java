package myau.management;

public final class LateRotation {
    private static float yaw = Float.NaN;
    private static int tick = Integer.MIN_VALUE;
    private static float bodyYaw = Float.NaN;
    private static int bodyTick = Integer.MIN_VALUE;

    private LateRotation() {
    }

    public static void setYaw(float value, int currentTick) {
        yaw = value;
        tick = currentTick;
    }

    public static float consumeYaw(int currentTick) {
        float value = tick == currentTick ? yaw : Float.NaN;
        yaw = Float.NaN;
        tick = Integer.MIN_VALUE;
        return value;
    }

    public static void scheduleBodyYaw(float value, int targetTick) {
        bodyYaw = value;
        bodyTick = targetTick;
    }

    public static float consumeBodyYaw(int currentTick) {
        if (bodyTick > currentTick) {
            return Float.NaN;
        }
        float value = bodyTick == currentTick ? bodyYaw : Float.NaN;
        bodyYaw = Float.NaN;
        bodyTick = Integer.MIN_VALUE;
        return value;
    }

    public static void clear() {
        yaw = Float.NaN;
        tick = Integer.MIN_VALUE;
        bodyYaw = Float.NaN;
        bodyTick = Integer.MIN_VALUE;
    }
}
