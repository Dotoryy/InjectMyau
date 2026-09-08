package myau.util;

public final class ReflectionUtils {
    private static volatile boolean renderItemInUse;

    private ReflectionUtils() {
    }

    public static boolean setItemInUse(boolean blocking) {
        renderItemInUse = blocking;
        return blocking;
    }

    public static boolean isItemInUse() {
        return renderItemInUse;
    }
}
