package myau.lag.handler;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.queue.BiTrackLagNodeQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.Vec3;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class UnifiedLagHandler extends AbstractFastTrackProvider {
    private final BiTrackLagNodeQueue queue = new BiTrackLagNodeQueue(this);

    private final Set<Packet<?>> packetFastTrack =
            Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>()));

    private volatile Vec3 serverPosition;

    public void requestLag(LagRequest request) {
        this.queue.requestLag(request);
    }

    public void releaseExpiredPackets(EnumLagDirection direction, long maxAgeMs) {
        this.queue.releaseExpiredPackets(direction, maxAgeMs);
    }

    public void releaseNextPacket(EnumLagDirection direction) {
        this.queue.releaseNextPacket(direction);
    }

    public Vec3 getLastReleasedServerPosition() {
        return this.serverPosition;
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (Minecraft.getMinecraft().getNetHandler() == null) {
            this.queue.clear();
            this.clearServerPositions();
            return;
        }
        Packet<?> packet = event.getPacket();
        boolean fastTracked = this.consumeFastTrack(packet);
        if (event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            if (fastTracked) {
                this.updateServerPosition(packet);
            } else if (this.queue.tick(packet, EnumLagDirection.OUTBOUND)) {
                event.setCancelled(true);
            } else {
                this.updateServerPosition(packet);
            }
            return;
        }
        if (!fastTracked && this.queue.tick(packet, EnumLagDirection.INBOUND)) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (Minecraft.getMinecraft().getNetHandler() == null) {
            this.queue.clear();
            this.clearServerPositions();
            return;
        }
        this.queue.tick(null, null);
    }

    @Override
    public void forPacket(Packet<?> packet) {
        this.packetFastTrack.add(packet);
    }

    private boolean consumeFastTrack(Packet<?> packet) {
        return this.packetFastTrack.remove(packet);
    }

    private void updateServerPosition(Packet<?> packet) {
        if (!(packet instanceof C03PacketPlayer)) {
            return;
        }
        C03PacketPlayer movement = (C03PacketPlayer) packet;
        if (movement.isMoving()) {
            this.serverPosition = new Vec3(movement.getPositionX(), movement.getPositionY(),
                    movement.getPositionZ());
        }
    }

    private void clearServerPositions() {
        this.serverPosition = null;
    }
}
