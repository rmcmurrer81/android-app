package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds current Sarah pairing invitations on private Wi-Fi.  Announcements
 * contain an expiring process instance only: never a stable owner/device ID,
 * profile detail, token, provider credential, or proof of trust.
 */
public final class SarahDeviceDiscovery {
    public static final int DISCOVERY_PORT = 8771;
    public static final String DISCOVERY_SCHEMA = "sarah-device-discovery-v2";
    private static final byte[] QUERY =
            "SARAH_DISCOVER_V2".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_ANNOUNCEMENT_BYTES = 2048;

    private SarahDeviceDiscovery() { }

    public static final class Peer {
        public final String host;
        public final String name;
        public final String instanceId;
        public final String deviceType;
        public final int pairingPort;
        public final long expiresAt;

        Peer(
                String host,
                String name,
                String instanceId,
                String deviceType,
                int pairingPort,
                long expiresAt) {
            this.host = TrustedLanEndpointPolicy.requireLocalHost(host);
            this.name = cleanLabel(name, "Sarah device");
            this.instanceId = requireInstance(instanceId);
            this.deviceType = cleanLabel(deviceType, "device");
            if (pairingPort < 0 || pairingPort > 65535) {
                throw new IllegalArgumentException("Discovery pairing port is invalid");
            }
            this.pairingPort = pairingPort;
            this.expiresAt = expiresAt;
        }

        @Override public String toString() {
            return name + "  -  " + host
                    + (pairingPort > 0 ? "  -  ready to verify" : "  -  setup pending");
        }
    }

    public static boolean isOnWifi(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static List<Peer> discover(Context context, int timeoutMillis) throws Exception {
        if (!isOnWifi(context)) return new ArrayList<>();
        Context app = context.getApplicationContext();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicast = null;
        if (wifi != null) {
            multicast = wifi.createMulticastLock("SarahDeviceDiscoveryV2");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }

        Map<String, Peer> found = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + Math.max(800, Math.min(timeoutMillis, 5000));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(250);
            send(socket, InetAddress.getByName("255.255.255.255"));
            InetAddress localBroadcast = broadcastAddress(wifi);
            if (localBroadcast != null) send(socket, localBroadcast);

            byte[] buffer = new byte[MAX_ANNOUNCEMENT_BYTES + 1];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (packet.getLength() < 1
                        || packet.getLength() > MAX_ANNOUNCEMENT_BYTES) continue;
                try {
                    Peer peer = parseAnnouncement(
                            new String(
                                    packet.getData(),
                                    packet.getOffset(),
                                    packet.getLength(),
                                    StandardCharsets.UTF_8),
                            packet.getAddress().getHostAddress(),
                            System.currentTimeMillis() / 1000L);
                    found.put(peer.host + ":" + peer.instanceId, peer);
                } catch (Exception ignored) {
                    // Unrelated, public, expired, or malformed LAN traffic is not a peer.
                }
            }
        } finally {
            if (multicast != null && multicast.isHeld()) multicast.release();
        }
        return new ArrayList<>(found.values());
    }

    static Peer parseAnnouncement(String text, String host, long nowSeconds) throws Exception {
        if (text == null || text.isEmpty()
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_ANNOUNCEMENT_BYTES) {
            throw new IllegalArgumentException("Discovery response is empty or oversized");
        }
        JSONObject value = new JSONObject(text);
        if (!DISCOVERY_SCHEMA.equals(value.optString("schema"))) {
            throw new IllegalArgumentException("Discovery response has the wrong protocol");
        }
        if (!SarahPairingProtocol.SCHEMA.equals(value.optString("pairing_protocol"))) {
            throw new IllegalArgumentException("Discovery response has no accepted pairing protocol");
        }
        if (!value.optBoolean("approval_required_on_both_devices", false)) {
            throw new SecurityException("Discovery response does not require two-device approval");
        }
        long expires = value.optLong("expires_at", 0L);
        if (expires < nowSeconds || expires > nowSeconds + 60L) {
            throw new SecurityException("Discovery response expired or has an invalid lifetime");
        }
        return new Peer(
                host,
                value.optString("device_name", "Sarah device"),
                value.optString("instance_id", ""),
                value.optString("device_type", "device"),
                value.optInt("pairing_port", -1),
                expires);
    }

    private static void send(DatagramSocket socket, InetAddress address) throws Exception {
        DatagramPacket packet = new DatagramPacket(QUERY, QUERY.length, address, DISCOVERY_PORT);
        socket.send(packet);
    }

    private static String requireInstance(String value) {
        String text = value == null ? "" : value.trim();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(text);
            if (decoded.length != 18) throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new IllegalArgumentException("Discovery instance ID is malformed", error);
        }
        return text;
    }

    private static String cleanLabel(String value, String fallback) {
        String text = value == null ? fallback : value.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) text = fallback;
        if (text.length() > 80) throw new IllegalArgumentException("Discovery label is invalid");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < 32 || Character.isSurrogate(character)) {
                throw new IllegalArgumentException("Discovery label is invalid");
            }
        }
        return text;
    }

    private static InetAddress broadcastAddress(WifiManager wifi) {
        try {
            if (wifi == null) return null;
            DhcpInfo dhcp = wifi.getDhcpInfo();
            if (dhcp == null || dhcp.ipAddress == 0 || dhcp.netmask == 0) return null;
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int index = 0; index < 4; index++) {
                quads[index] = (byte) ((broadcast >> (index * 8)) & 0xff);
            }
            return InetAddress.getByAddress(quads);
        } catch (Exception ignored) {
            return null;
        }
    }
}
