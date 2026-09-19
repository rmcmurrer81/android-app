package com.kiraworld.sarahtravel;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded transport for the pairing handshake only.
 *
 * <p>Wire contract: one four-byte unsigned/big-endian length followed by that
 * many UTF-8 JSON bytes.  The Android initiator sends an offer, receives a
 * response, shows the derived SAS, sends its confirmation only after explicit
 * owner approval, and receives the Windows confirmation.  No profile, owner,
 * Gmail, model, provider, or sync payload is accepted by this transport.</p>
 */
public final class SarahPairingTransport {
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int RESPONSE_TIMEOUT_MS = 10000;
    public static final int MAX_FRAME_BYTES = SarahPairingProtocol.MAX_MESSAGE_BYTES;

    private SarahPairingTransport() { }

    public static final class Pending implements AutoCloseable {
        public final SarahDeviceDiscovery.Peer peer;
        public final String sasCode;
        public final long expiresAt;

        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final SarahPairingProtocol.Session session;
        private boolean completed;

        private Pending(
                SarahDeviceDiscovery.Peer peer,
                Socket socket,
                DataInputStream input,
                DataOutputStream output,
                SarahPairingProtocol.Session session) {
            this.peer = peer;
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.session = session;
            this.sasCode = session.sasCode;
            this.expiresAt = session.expiresAt;
        }

        /** Complete only after this device's owner explicitly approves the SAS. */
        public synchronized SarahPairingProtocol.Credential complete(
                boolean ownerConfirmedMatchingCode) throws Exception {
            if (completed) {
                throw new SarahPairingProtocol.PairingException(
                        "This pairing transport cannot be completed twice");
            }
            completed = true;
            long now = nowSeconds();
            try {
                Map<String, Object> local = session.localConfirmation(
                        ownerConfirmedMatchingCode, now);
                int remaining = (int) Math.max(
                        1000L,
                        Math.min(125_000L, (expiresAt - now + 1L) * 1000L));
                socket.setSoTimeout(remaining);
                writeFrame(output, local);
                Map<String, Object> peerConfirmation = readFrame(input);
                session.acceptPeerConfirmation(peerConfirmation, nowSeconds());
                return session.finalizeCredential(nowSeconds());
            } finally {
                close();
            }
        }

        @Override public synchronized void close() {
            session.destroy();
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    public static Pending begin(
            SarahDeviceDiscovery.Peer peer,
            String localInstanceId,
            String localDeviceName,
            String localDeviceType) throws Exception {
        if (peer == null) {
            throw new SarahPairingProtocol.PairingException(
                    "Choose a discovered Sarah device first");
        }
        if (peer.pairingPort < 1 || peer.pairingPort > 65535) {
            throw new SarahPairingProtocol.PairingException(
                    "The discovered Sarah device is not accepting secure pairing yet");
        }
        if (nowSeconds() > peer.expiresAt) {
            throw new SarahPairingProtocol.PairingException(
                    "The discovery notice expired; scan again");
        }
        String exactHost = TrustedLanEndpointPolicy.requireLocalHost(peer.host);
        Socket socket = new Socket();
        SarahPairingProtocol.Session session = null;
        try {
            socket.connect(new InetSocketAddress(exactHost, peer.pairingPort), CONNECT_TIMEOUT_MS);
            requireExactPeer(socket.getInetAddress(), exactHost);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            SarahPairingProtocol.Initiator initiator = SarahPairingProtocol.newInitiator(
                    localInstanceId,
                    localDeviceName,
                    localDeviceType,
                    nowSeconds());
            writeFrame(output, initiator.offerMessage());
            Map<String, Object> response = readFrame(input);
            requireResponseIdentity(response, peer);
            session = initiator.acceptResponse(response, nowSeconds());
            return new Pending(peer, socket, input, output, session);
        } catch (Exception error) {
            if (session != null) session.destroy();
            try { socket.close(); } catch (Exception ignored) { }
            throw error;
        }
    }

    private static void requireExactPeer(InetAddress actual, String expected) {
        String actualHost = actual == null ? "" : actual.getHostAddress();
        String normalizedActual = TrustedLanEndpointPolicy.requireLocalHost(actualHost);
        String normalizedExpected = TrustedLanEndpointPolicy.requireLocalHost(expected);
        if (!normalizedActual.equalsIgnoreCase(normalizedExpected)) {
            throw new SarahPairingProtocol.PairingException(
                    "Pairing connected to a different LAN address than the discovery notice");
        }
    }

    private static void requireResponseIdentity(
            Map<String, Object> response,
            SarahDeviceDiscovery.Peer peer) {
        if (!peer.instanceId.equals(String.valueOf(response.get("instance_id")))
                || !peer.name.equals(String.valueOf(response.get("device_name")))
                || !peer.deviceType.equals(String.valueOf(response.get("device_type")))) {
            throw new SarahPairingProtocol.PairingException(
                    "The pairing response does not match the discovered Sarah device");
        }
    }

    private static void writeFrame(DataOutputStream output, Map<String, ?> message)
            throws Exception {
        byte[] encoded = new JSONObject(message).toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > MAX_FRAME_BYTES) {
            throw new SarahPairingProtocol.PairingException(
                    "Pairing message exceeds its size limit");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
        output.flush();
    }

    private static Map<String, Object> readFrame(DataInputStream input) throws Exception {
        int length;
        try { length = input.readInt(); }
        catch (EOFException error) {
            throw new SarahPairingProtocol.PairingException(
                    "The other Sarah device closed pairing before replying", error);
        }
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new SarahPairingProtocol.PairingException(
                    "Pairing frame is empty or oversized");
        }
        byte[] data = new byte[length];
        input.readFully(data);
        JSONObject json;
        try { json = new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (Exception error) {
            throw new SarahPairingProtocol.PairingException(
                    "Pairing frame is not valid JSON", error);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (!(value instanceof String)
                    && !(value instanceof Boolean)
                    && !(value instanceof Byte)
                    && !(value instanceof Short)
                    && !(value instanceof Integer)
                    && !(value instanceof Long)) {
                throw new SarahPairingProtocol.PairingException(
                        "Pairing frame contains an unsupported value");
            }
            result.put(key, value);
        }
        return result;
    }

    private static long nowSeconds() { return System.currentTimeMillis() / 1000L; }
}
