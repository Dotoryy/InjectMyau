package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.access.AccessorC03PacketPlayer;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S08PacketPlayerPosLook.EnumFlags;

public class NoRotate extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double TELEPORT_DISTANCE_SQ = 100.0;
    private static final long PENDING_LIFETIME_MS = 1000L;
    private static final double POSITION_EPSILON = 1.0E-4;
    private static final int MODE_TICK = 0;
    private static final int MODE_EDIT = 1;
    public final ModeProperty mode =
            new ModeProperty("mode", MODE_TICK, new String[]{"TICK", "EDIT"});
    public final BooleanProperty ignoreTeleports = new BooleanProperty("ignore-teleports", true,
            () -> this.mode.getValue() == MODE_TICK);
    private float editYaw;
    private float editPitch;
    private boolean editPending;
    private long editPendingSince;
    private double editX;
    private double editY;
    private double editZ;
    private boolean pending;
    private long pendingSince;
    private double pendingX;
    private double pendingY;
    private double pendingZ;
    private float clientYaw;
    private float clientPitch;
    public NoRotate() {
        super("No Rotate", false);
    }
    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == MODE_EDIT ? "Edit" : "Tick"};
    }
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S08PacketPlayerPosLook
                    && this.mode.getValue() != MODE_EDIT) {
                this.onTeleport((S08PacketPlayerPosLook) event.getPacket());
            }
        } else if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof C06PacketPlayerPosLook) {
                if (this.mode.getValue() == MODE_EDIT) {
                    this.onEditConfirmation((C06PacketPlayerPosLook) event.getPacket());
                } else {
                    this.onConfirmation((C06PacketPlayerPosLook) event.getPacket());
                }
            }
        }
    }

    public float[] onServerTeleport(double x, double y, double z, float yaw, float pitch) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_EDIT || mc.thePlayer == null) {
            return null;
        }
        this.editYaw = yaw;
        this.editPitch = pitch;
        this.editX = x;
        this.editY = y;
        this.editZ = z;
        this.editPending = true;
        this.editPendingSince = System.currentTimeMillis();
        return new float[]{mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch};
    }

    private void onEditConfirmation(C06PacketPlayerPosLook packet) {
        if (!this.editPending) {
            return;
        }
        if (System.currentTimeMillis() - this.editPendingSince > PENDING_LIFETIME_MS) {
            this.editPending = false;
            return;
        }
        if (Math.abs(packet.getPositionX() - this.editX) >= POSITION_EPSILON
                || Math.abs(packet.getPositionY() - this.editY) >= POSITION_EPSILON
                || Math.abs(packet.getPositionZ() - this.editZ) >= POSITION_EPSILON) {
            return;
        }
        AccessorC03PacketPlayer.setYaw(packet, this.editYaw);
        AccessorC03PacketPlayer.setPitch(packet, this.editPitch);
        this.editPending = false;
    }

    private void onTeleport(S08PacketPlayerPosLook packet) {
        boolean absoluteYaw = !packet.func_179834_f().contains(EnumFlags.Y_ROT);
        boolean absolutePitch = !packet.func_179834_f().contains(EnumFlags.X_ROT);
        if (!absoluteYaw && !absolutePitch) {
            return;
        }

        double x = packet.getX()
                + (packet.func_179834_f().contains(EnumFlags.X) ? mc.thePlayer.posX : 0.0);
        double y = packet.getY()
                + (packet.func_179834_f().contains(EnumFlags.Y) ? mc.thePlayer.posY : 0.0);
        double z = packet.getZ()
                + (packet.func_179834_f().contains(EnumFlags.Z) ? mc.thePlayer.posZ : 0.0);
        if (this.ignoreTeleports.getValue() && this.isRealTeleport(x, y, z)) {
            this.pending = false;
            return;
        }
        this.clientYaw = mc.thePlayer.rotationYaw;
        this.clientPitch = mc.thePlayer.rotationPitch;
        this.pendingX = x;
        this.pendingY = y;
        this.pendingZ = z;
        this.pending = true;
        this.pendingSince = System.currentTimeMillis();
    }
    private boolean isRealTeleport(double x, double y, double z) {
        double dx = x - mc.thePlayer.posX;
        double dy = y - mc.thePlayer.posY;
        double dz = z - mc.thePlayer.posZ;
        return dx * dx + dy * dy + dz * dz >= TELEPORT_DISTANCE_SQ;
    }
    private void onConfirmation(C06PacketPlayerPosLook packet) {
        if (!this.pending) {
            return;
        }
        if (System.currentTimeMillis() - this.pendingSince > PENDING_LIFETIME_MS) {
            this.pending = false;
            return;
        }
        if (!this.matchesPendingPosition(packet)) {
            return;
        }
        this.pending = false;
        mc.thePlayer.rotationYaw = this.clientYaw;
        mc.thePlayer.rotationPitch = this.clientPitch;
        mc.thePlayer.prevRotationYaw = this.clientYaw;
        mc.thePlayer.prevRotationPitch = this.clientPitch;
    }

    private boolean matchesPendingPosition(C03PacketPlayer packet) {
        return Math.abs(packet.getPositionX() - this.pendingX) < POSITION_EPSILON
                && Math.abs(packet.getPositionY() - this.pendingY) < POSITION_EPSILON
                && Math.abs(packet.getPositionZ() - this.pendingZ) < POSITION_EPSILON;
    }
    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.pending = false;
        this.editPending = false;
    }
    @Override
    public void onDisabled() {
        this.pending = false;
        this.editPending = false;
    }
}
