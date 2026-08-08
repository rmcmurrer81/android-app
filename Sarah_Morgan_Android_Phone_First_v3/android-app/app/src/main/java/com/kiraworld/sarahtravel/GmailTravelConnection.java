package com.kiraworld.sarahtravel;

/** Pure fail-closed contract until Google OAuth and a supervised read test are installed. */
public final class GmailTravelConnection {
    private GmailTravelConnection() { }

    public static boolean implementationAvailable() { return false; }

    public static boolean canClaimConnected(
            boolean oauthAccountSelected,
            boolean readOnlyScopeGranted,
            boolean supervisedReadTestPassed) {
        return implementationAvailable()
                && oauthAccountSelected
                && readOnlyScopeGranted
                && supervisedReadTestPassed;
    }

    public static boolean canMonitor(boolean connected, boolean ownerEnabled) {
        return connected && ownerEnabled;
    }

    public static String status() {
        return "Gmail not connected - monitoring off - last sync never";
    }
}
