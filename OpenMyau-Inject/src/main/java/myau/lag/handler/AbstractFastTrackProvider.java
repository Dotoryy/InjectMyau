package myau.lag.handler;

import net.minecraft.network.Packet;

public abstract class AbstractFastTrackProvider {
    public abstract void forPacket(Packet<?> packet);
}
