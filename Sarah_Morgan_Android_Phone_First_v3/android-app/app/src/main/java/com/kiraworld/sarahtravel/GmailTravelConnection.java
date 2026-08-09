package com.kiraworld.sarahtravel;

/** Pure acceptance contract for the optional Android Gmail read-only connector. */
public final class GmailTravelConnection {
    private GmailTravelConnection() { }

    public static boolean implementationAvailable() { return true; }

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
        return "Gmail read-only connector available; runtime connection required";
    }

    public static String setupStatus() {
        return "The exact APK package and signing SHA-1 must be registered as an Android OAuth client, the Gmail API enabled, gmail.readonly granted, and a supervised read/revoke test passed.";
    }

    public static String privacySummary() {
        return "Sarah never asks for a Gmail password. After the owner connects Google, Sarah can perform bounded metadata-first travel searches only; she cannot send, delete, modify, mark read, create drafts, or change Gmail settings.";
    }

    /** Runtime UI uses GmailTokenVault; this context-free legacy method stays conservative. */
    public static boolean disconnectAvailable() { return false; }

    /** Runtime UI uses the encrypted receipt vault; this pure legacy method stays conservative. */
    public static boolean gmailDerivedDataExists() { return false; }
}
