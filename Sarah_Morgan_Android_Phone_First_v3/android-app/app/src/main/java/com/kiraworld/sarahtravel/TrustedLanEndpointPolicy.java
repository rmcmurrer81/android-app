package com.kiraworld.sarahtravel;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;

/** Pure allowlist for Sarah's payload-encrypted, token-authenticated LAN sync transport. */
public final class TrustedLanEndpointPolicy {
    public static final int PORT = 8769;

    private TrustedLanEndpointPolicy() { }

    /** Returns a canonical host without a port, or throws before any network request. */
    public static String requireLocalHost(String input) {
        String value = input == null ? "" : input.trim();
        value = value.replaceFirst("(?i)^https?://", "");
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty() || value.contains("/") || value.contains("?")
                || value.contains("#") || value.contains("@")) {
            throw new IllegalArgumentException("A literal trusted LAN address is required.");
        }

        String host = value;
        int explicitPort = -1;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end <= 1) throw new IllegalArgumentException("The IPv6 LAN address is invalid.");
            host = value.substring(1, end);
            String suffix = value.substring(end + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) throw new IllegalArgumentException("The LAN address is invalid.");
                explicitPort = parsePort(suffix.substring(1));
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = value.substring(0, firstColon);
                explicitPort = parsePort(value.substring(firstColon + 1));
            } else if (firstColon >= 0) {
                // Unbracketed IPv6 literals are accepted only without a port.
                host = value;
            }
        }
        if (explicitPort != -1 && explicitPort != PORT) {
            throw new IllegalArgumentException("Trusted Sarah sync uses only LAN port " + PORT + ".");
        }

        String lower = host.toLowerCase(Locale.US);
        if ("localhost".equals(lower)) return "localhost";
        if (isAllowedIpv4(lower)) return lower;
        if (isAllowedIpv6(lower)) return lower;
        throw new SecurityException(
                "Trusted Sarah sync accepts only loopback, private, or link-local literal LAN addresses.");
    }

    public static String authority(String input) {
        String host = requireLocalHost(input);
        return host.contains(":") ? "[" + host + "]:" + PORT : host + ":" + PORT;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("The trusted LAN port is invalid.", error);
        }
    }

    private static boolean isAllowedIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] bytes = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                bytes[i] = Integer.parseInt(parts[i]);
                if (bytes[i] < 0 || bytes[i] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return bytes[0] == 10
                || bytes[0] == 127
                || bytes[0] == 169 && bytes[1] == 254
                || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31
                || bytes[0] == 192 && bytes[1] == 168;
    }

    private static boolean isAllowedIpv6(String value) {
        String lower = value.toLowerCase(Locale.US);
        if (!(lower.contains(":") && lower.matches("[0-9a-f:]+"))) return false;
        try {
            InetAddress parsed = InetAddress.getByName(lower);
            if (!(parsed instanceof Inet6Address)) return false;
            byte[] bytes = parsed.getAddress();
            boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
            boolean linkLocal = (bytes[0] & 0xff) == 0xfe
                    && (bytes[1] & 0xc0) == 0x80;
            return parsed.isLoopbackAddress() || uniqueLocal || linkLocal;
        } catch (Exception invalid) {
            return false;
        }
    }
}
