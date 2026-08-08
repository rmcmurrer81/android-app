package com.kiraworld.sarahtravel;

import android.content.Context;

/** Idempotent, crash-resumable migration of every profile-keyed Android store. */
public final class OwnerProfileDataMigrator {
    private OwnerProfileDataMigrator() { }

    public static boolean move(
            Context context,
            String oldPersonId,
            String newPersonId,
            String confirmedName) {
        if (oldPersonId == null || newPersonId == null
                || oldPersonId.trim().isEmpty() || newPersonId.trim().isEmpty()
                || oldPersonId.trim().equals(newPersonId.trim())) return true;

        try {

        TripPlanStore plans = new TripPlanStore(context);
        try { plans.moveProfile(oldPersonId, newPersonId); }
        finally { plans.close(); }

        StayRequestStore stays = new StayRequestStore(context);
        try { stays.moveProfile(oldPersonId, newPersonId, confirmedName); }
        finally { stays.close(); }

        SarahLocationStore locations = new SarahLocationStore(context);
        if (!locations.moveProfile(oldPersonId, newPersonId)) return false;

        ProactiveDiscoveryStore discoveries = new ProactiveDiscoveryStore(context);
        try {
            if (!discoveries.moveProfile(oldPersonId, newPersonId, confirmedName)) return false;
        } finally {
            discoveries.close();
        }

        return TravelerNeedsStore.moveProfile(context, oldPersonId, newPersonId)
                && LoyaltyVaultStore.moveProfile(context, oldPersonId, newPersonId)
                && RoadTripProfileStore.moveProfile(context, oldPersonId, newPersonId)
                && HotelSearchState.moveProfile(context, oldPersonId, newPersonId)
                && ProactiveResearchReceiptStore.moveProfile(context, oldPersonId, newPersonId)
                && VoiceReceiptStore.moveProfile(context, oldPersonId, newPersonId);
        } catch (Exception ignored) {
            return false;
        }
    }
}
