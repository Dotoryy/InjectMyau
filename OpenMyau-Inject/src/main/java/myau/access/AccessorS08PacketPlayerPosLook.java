package myau.access;

import myau.inject.MappingBridge;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.lang.reflect.Field;

public final class AccessorS08PacketPlayerPosLook {
    private static final String OWNER = "net.minecraft.network.play.server.S08PacketPlayerPosLook";
    private static final Field F_YAW = MappingBridge.field(OWNER, "yaw", float.class);
    private static final Field F_PITCH = MappingBridge.field(OWNER, "pitch", float.class);
    private AccessorS08PacketPlayerPosLook() {
    }
    public static void setYaw(S08PacketPlayerPosLook owner, float value) {
        try {
            F_YAW.setFloat(owner, value);
        } catch (Throwable t) {
            Access.report(OWNER, "yaw", t);
        }
    }
    public static void setPitch(S08PacketPlayerPosLook owner, float value) {
        try {
            F_PITCH.setFloat(owner, value);
        } catch (Throwable t) {
            Access.report(OWNER, "pitch", t);
        }
    }
}
