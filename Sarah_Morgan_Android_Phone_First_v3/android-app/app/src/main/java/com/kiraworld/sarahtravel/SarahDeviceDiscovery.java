package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.DhcpInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Sarah installations on the same private Wi-Fi. Discovery is not trust:
 * no memory or trip data can move until the already-running Sarah explicitly
 * approves the new named device and matching verification code.
 */
public final class SarahDeviceDiscovery {
    public static final int DISCOVERY_PORT = 8770;
    private static final byte[] QUERY = "SARAH_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);

    private SarahDeviceDiscovery() {}

    public static final class Peer {
        public final String host;
        public final String name;
        public final String deviceId;
        public final int port;

        Peer(String host, String name, String deviceId, int port) {
            this.host = host;
            this.name = name == null || name.trim().isEmpty() ? "Sarah on Windows" : name.trim();
            this.deviceId = deviceId == null ? "" : deviceId.trim();
            this.port = port <= 0 ? 8769 : port;
        }

        @Override public String toString() {
            return name + "  •  " + host;
        }
    }

    public static boolean isOnWifi(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static List<Peer> discover(Context context, int timeoutMillis) throws Exception {
        if (!isOnWifi(context)) return new ArrayList<>();
        Context app = context.getApplicationContext();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicast = null;
        if (wifi != null) {
            multicast = wifi.createMulticastLock("SarahDeviceDiscovery");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }

        Map<String, Peer> found = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + Math.max(800, timeoutMillis);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(280);
            send(socket, InetAddress.getByName("255.255.255.255"));
            InetAddress localBroadcast = broadcastAddress(wifi);
            if (localBroadcast != null) send(socket, localBroadcast);

            byte[] buffer = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    if (System.currentTimeMillis() + 350 < deadline) {
                        send(socket, InetAddress.getByName("255.255.255.255"));
                    }
                    continue;
                }
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                try {
                    JSONObject json = new JSONObject(text);
                    if (!"sarah-discovery-v1".equals(json.optString("protocol"))) continue;
                    String host = packet.getAddress().getHostAddress();
                    Peer peer = new Peer(
                            host,
                            json.optString("device_name", "Sarah on Windows"),
                            json.optString("device_id", ""),
                            json.optInt("port", 8769));
                    found.put(host + ":" + peer.port, peer);
                } catch (Exception ignored) {
                    // Ignore unrelated UDP traffic on the local network.
                }
            }
        } finally {
            if (multicast != null && multicast.isHeld()) multicast.release();
        }
        return new ArrayList<>(found.values());
    }

    private static void send(DatagramSocket socket, InetAddress address) throws Exception {
        DatagramPacket packet = new DatagramPacket(QUERY, QUERY.length, address, DISCOVERY_PORT);
        socket.send(packet);
    }

    private static InetAddress broadcastAddress(WifiManager wifi) {
        try {
            if (wifi == null) return null;
            DhcpInfo dhcp = wifi.getDhcpInfo();
            if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) return null;
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int k = 0; k < 4; k++) quads[k] = (byte) ((broadcast >> (k * 8)) & 0xFF);
            return InetAddress.getByAddress(quads);
        } catch (Exception ignored) {
            return null;
        }
    }
}
