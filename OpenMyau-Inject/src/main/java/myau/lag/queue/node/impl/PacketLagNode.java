package myau.lag.queue.node.impl;

import myau.lag.api.EnumLagDirection;
import myau.lag.handler.AbstractFastTrackProvider;
import myau.lag.queue.node.api.AbstractLagNode;
import myau.lag.queue.node.api.AbstractLagNode;
import net.minecraft.network.Packet;

public final class PacketLagNode extends AbstractLagNode {
    private final Packet<?> packet;
    private final EnumLagDirection direction;
    private final long queuedAtMs;

    public PacketLagNode(Packet<?> packet, EnumLagDirection direction) {
        this.packet = packet;
        this.direction = direction;
        this.queuedAtMs = System.currentTimeMillis();
    }

    public long getQueuedAtMs() {
        return this.queuedAtMs;
    }

    public void goThrough(AbstractFastTrackProvider fastTrack) {
        if (this.direction == EnumLagDirection.OUTBOUND) {
            fastTrack.forPacket(this.packet);
        }
        this.direction.passThroughChannel(this.packet);
    }
}
