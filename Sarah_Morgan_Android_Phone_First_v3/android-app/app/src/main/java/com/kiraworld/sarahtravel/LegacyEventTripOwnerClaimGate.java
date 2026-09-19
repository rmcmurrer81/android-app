package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Idempotently binds preserved R1 event/update/booking rows to an exact,
 * confirmed, currently active owner before normal event features can open.
 */
public final class LegacyEventTripOwnerClaimGate {
    private static final String PREFS = "sarah_legacy_event_trip_owner_claim";

    public static final class Result {
        public final boolean mayProceed;
        public final boolean claimComplete;
        public final String status;

        Result(boolean mayProceed, boolean claimComplete, String status) {
            this.mayProceed = mayProceed;
            this.claimComplete = claimComplete;
            this.status = status;
        }
    }

    private LegacyEventTripOwnerClaimGate() { }

    public static synchronized Result ensure(
            Context context,
            Map<String, String> confirmedOwner,
            Map<String, String> activeProfile) {
        if (context == null || confirmedOwner == null || activeProfile == null) {
            return new Result(false, false, "OWNER_CLAIM_CONTEXT_UNAVAILABLE");
        }
        String ownerId = confirmedOwner.getOrDefault("person_id", "").trim();
        String ownerName = confirmedOwner.getOrDefault("name", "").trim();
        String activeId = activeProfile.getOrDefault("person_id", "").trim();
        boolean confirmed = ownerId.matches("[0-9]+")
                && ProfileMigrationPolicy.isConfirmedDisplayName(ownerName)
                && "yes".equals(confirmedOwner.getOrDefault("is_owner", "no"));
        boolean exactActiveOwner = confirmed
                && ownerId.equals(activeId)
                && "yes".equals(activeProfile.getOrDefault("is_owner", "no"))
                && "yes".equals(activeProfile.getOrDefault("active", "no"));
        if (!exactActiveOwner) {
            // A non-owner may keep using their own profile. The claim is
            // retried automatically when the exact confirmed owner is active.
            return new Result(true, false, "DEFERRED_UNTIL_CONFIRMED_OWNER_ACTIVE");
        }

        if (!OwnerProfileDataMigrator.claimLegacyOwnerData(context, ownerId)) {
            record(context, ownerId, "CLAIM_FAILED_RETRY_REQUIRED", false);
            return new Result(false, false, "CLAIM_FAILED_RETRY_REQUIRED");
        }
        if (!record(context, ownerId, "CLAIM_COMPLETE_VERIFIED", true)) {
            return new Result(false, false, "CLAIM_COMPLETE_MARKER_COMMIT_FAILED_RETRY_REQUIRED");
        }
        return new Result(true, true, "CLAIM_COMPLETE_VERIFIED");
    }

    private static boolean record(
            Context context,
            String ownerId,
            String status,
            boolean complete) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return preferences.edit()
                .putString("owner_person_id", ownerId)
                .putString("status", status)
                .putBoolean("complete", complete)
                .putLong("updated_at", System.currentTimeMillis())
                .commit();
    }
}
