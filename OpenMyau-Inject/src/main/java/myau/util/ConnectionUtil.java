package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.INetHandlerHandshakeServer;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.INetHandlerLoginServer;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.login.server.S00PacketDisconnect;
import net.minecraft.network.login.server.S01PacketEncryptionRequest;
import net.minecraft.network.login.server.S02PacketLoginSuccess;
import net.minecraft.network.login.server.S03PacketEnableCompression;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraft.network.status.INetHandlerStatusServer;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.network.status.server.S00PacketServerInfo;
import net.minecraft.network.status.server.S01PacketPong;

public final class ConnectionUtil {
    private ConnectionUtil() {
    }

    public static boolean isForeign(Object manager) {
        NetHandlerPlayClient handler = Minecraft.getMinecraft().getNetHandler();
        if (handler != null) {
            return handler.getNetworkManager() != manager;
        }
        return manager instanceof NetworkManager
                && isServerSide(((NetworkManager) manager).getNetHandler());
    }

    public static boolean isPlayPacket(Object packet) {
        return !(packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart
                || packet instanceof C01PacketEncryptionResponse
                || packet instanceof C00PacketServerQuery
                || packet instanceof C01PacketPing
                || packet instanceof S00PacketDisconnect
                || packet instanceof S01PacketEncryptionRequest
                || packet instanceof S02PacketLoginSuccess
                || packet instanceof S03PacketEnableCompression
                || packet instanceof S00PacketServerInfo
                || packet instanceof S01PacketPong);
    }

    private static boolean isServerSide(INetHandler handler) {
        return handler instanceof INetHandlerPlayServer
                || handler instanceof INetHandlerLoginServer
                || handler instanceof INetHandlerHandshakeServer
                || handler instanceof INetHandlerStatusServer;
    }
}
