package myau.access;

import myau.inject.MappingBridge;
import net.minecraft.network.play.client.C03PacketPlayer;

import java.lang.reflect.Field;

public final class AccessorC03PacketPlayer {
    private static final String OWNER = "net.minecraft.network.play.client.C03PacketPlayer";
    private static final Field F_ONGROUND =
            MappingBridge.field(OWNER, "onGround", boolean.class);
    private static final Field F_YAW =
            MappingBridge.field(OWNER, "yaw", float.class);
    private static final Field F_PITCH =
            MappingBridge.field(OWNER, "pitch", float.class);
    private AccessorC03PacketPlayer() {
    }
    public static void setPitch(C03PacketPlayer owner, float value) {
        try {
            F_PITCH.setFloat(owner, value);
        } catch (Throwable t) {
            Access.report(OWNER, "pitch", t);
        }
    }
    public static void setYaw(C03PacketPlayer owner, float value) {
        try {
            F_YAW.setFloat(owner, value);
        } catch (Throwable t) {
            Access.report(OWNER, "yaw", t);
        }
    }
    public static void setOnGround(C03PacketPlayer owner, boolean value) {
        try {
            F_ONGROUND.setBoolean(owner, value);
        } catch (Throwable t) {
            Access.report(OWNER, "onGround", t);
        }
    }
}
