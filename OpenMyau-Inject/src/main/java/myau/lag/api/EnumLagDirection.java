package myau.lag.api;

import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.ThreadQuickExitException;

import java.util.EnumSet;
import java.util.Set;

public enum EnumLagDirection {
    INBOUND {
        @Override
        @SuppressWarnings("unchecked")
        public void passThroughChannel(Packet packet) {
            try {
                packet.processPacket(Minecraft.getMinecraft().getNetHandler());
            } catch (ThreadQuickExitException expected) {
            } catch (Exception failed) {
                ChatUtil.sendFormatted("&cerror while handling packet: "
                        + packet.getClass().getSimpleName());
            }
        }
    },
    OUTBOUND {
        @Override
        public void passThroughChannel(Packet packet) {
            Minecraft.getMinecraft().getNetHandler().addToSendQueue(packet);
        }
    };

    public static final Set<EnumLagDirection> ONLY_INBOUND = EnumSet.of(INBOUND);
    public static final Set<EnumLagDirection> ONLY_OUTBOUND = EnumSet.of(OUTBOUND);
    public static final Set<EnumLagDirection> BIDIRECTIONAL = EnumSet.allOf(EnumLagDirection.class);

    public abstract void passThroughChannel(Packet packet);
}
