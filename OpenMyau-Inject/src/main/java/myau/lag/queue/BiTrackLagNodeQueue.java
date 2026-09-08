package myau.lag.queue;

import myau.lag.api.EnumLagDirection;
import myau.lag.api.LagRequest;
import myau.lag.handler.AbstractFastTrackProvider;
import myau.lag.queue.node.api.AbstractLagNode;
import myau.lag.queue.node.impl.AddRequestLagNode;
import myau.lag.queue.node.impl.PacketLagNode;
import net.minecraft.network.Packet;

import java.util.ArrayList;
import java.util.List;

public final class BiTrackLagNodeQueue {
    private final TrackState incomingState;
    private final TrackState outgoingState;

    public BiTrackLagNodeQueue(AbstractFastTrackProvider fastTrackProvider) {
        this.incomingState = new TrackState(new ArrayList<AbstractLagNode>(), fastTrackProvider);
        this.outgoingState = new TrackState(new ArrayList<AbstractLagNode>(), fastTrackProvider);
    }

    public void clear() {
        this.incomingState.clear();
        this.outgoingState.clear();
    }

    public boolean tick(Packet<?> packet, EnumLagDirection direction) {
        if ((packet == null) != (direction == null)) {
            throw new NullPointerException();
        }
        if (direction == null) {
            this.incomingState.tick(null, null);
            this.outgoingState.tick(null, null);
            return false;
        }
        switch (direction) {
            case INBOUND:
                return this.incomingState.tick(packet, direction);
            case OUTBOUND:
                return this.outgoingState.tick(packet, direction);
            default:
                return false;
        }
    }

    public void requestLag(LagRequest request) {
        for (EnumLagDirection direction : request.getDirections()) {
            switch (direction) {
                case INBOUND:
                    this.incomingState.addRequest(request);
                    break;
                case OUTBOUND:
                    this.outgoingState.addRequest(request);
                    break;
                default:
                    break;
            }
        }
    }

    public void releaseExpiredPackets(EnumLagDirection direction, long maxAgeMs) {
        switch (direction) {
            case INBOUND:
                this.incomingState.releaseExpiredPackets(maxAgeMs);
                break;
            case OUTBOUND:
                this.outgoingState.releaseExpiredPackets(maxAgeMs);
                break;
            default:
                break;
        }
    }

    public void releaseNextPacket(EnumLagDirection direction) {
        switch (direction) {
            case INBOUND:
                this.incomingState.releaseNextPacket();
                break;
            case OUTBOUND:
                this.outgoingState.releaseNextPacket();
                break;
            default:
                break;
        }
    }

    private static final class TrackState {
        private final List<AbstractLagNode> track;
        private final AbstractFastTrackProvider fastTrackProvider;
        private LagRequest currentlyAwaiting;

        private TrackState(List<AbstractLagNode> track, AbstractFastTrackProvider fastTrackProvider) {
            this.currentlyAwaiting = null;
            this.track = track;
            this.fastTrackProvider = fastTrackProvider;
        }

        private synchronized void addRequest(LagRequest request) {
            this.track.add(new AddRequestLagNode(request));
        }

        private synchronized boolean tick(Packet<?> packet, EnumLagDirection direction) {
            if (this.track.isEmpty()
                    && (this.currentlyAwaiting == null
                    || this.currentlyAwaiting.getTimeout().isTimedOut())) {
                this.currentlyAwaiting = null;
                return false;
            }
            if (packet != null) {
                this.track.add(new PacketLagNode(packet, direction));
            }
            LagRequest awaiting = this.currentlyAwaiting;
            try {
                while (awaiting == null || awaiting.getTimeout().isTimedOut()) {
                    AbstractLagNode popped = this.pop();
                    if (popped == null) {
                        awaiting = null;
                        break;
                    }
                    if (popped instanceof PacketLagNode) {
                        ((PacketLagNode) popped).goThrough(this.fastTrackProvider);
                    } else if (popped instanceof AddRequestLagNode) {
                        awaiting = ((AddRequestLagNode) popped).getRequest();
                    }
                }
            } catch (Exception failed) {
                failed.printStackTrace();
            }
            this.currentlyAwaiting = awaiting;
            return true;
        }

        private synchronized void releaseExpiredPackets(long maxAgeMs) {
            long cutoff = System.currentTimeMillis() - maxAgeMs;
            List<PacketLagNode> toRelease = new ArrayList<PacketLagNode>();
            for (AbstractLagNode node : new ArrayList<AbstractLagNode>(this.track)) {
                if (node instanceof PacketLagNode) {
                    PacketLagNode held = (PacketLagNode) node;
                    if (held.getQueuedAtMs() <= cutoff) {
                        toRelease.add(held);
                    }
                }
            }
            if (toRelease.isEmpty()) {
                return;
            }
            this.track.removeAll(toRelease);
            for (PacketLagNode held : toRelease) {
                held.goThrough(this.fastTrackProvider);
            }
        }

        private synchronized void releaseNextPacket() {
            for (int i = 0; i < this.track.size(); i++) {
                AbstractLagNode node = this.track.get(i);
                if (node instanceof PacketLagNode) {
                    PacketLagNode held = (PacketLagNode) node;
                    this.track.remove(i);
                    held.goThrough(this.fastTrackProvider);
                    return;
                }
            }
        }

        private synchronized void clear() {
            this.track.clear();
            this.currentlyAwaiting = null;
        }

        private AbstractLagNode pop() {
            return this.track.isEmpty() ? null : this.track.remove(0);
        }
    }
}
