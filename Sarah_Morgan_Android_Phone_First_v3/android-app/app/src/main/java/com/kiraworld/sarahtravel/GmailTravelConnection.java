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
        return "Gmail not connected · monitoring off · last sync never";
    }

    public static String setupStatus() {
        return "Setup required. No Google OAuth client, read-only scope grant, or supervised mailbox-read acceptance is installed.";
    }

    public static String privacySummary() {
        return "Sarah cannot read Gmail. Only an exact booking email, link, text, image, or PDF you deliberately share is imported.";
    }

    public static boolean disconnectAvailable() { return false; }

    public static boolean gmailDerivedDataExists() { return false; }
}
