package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact active confirmed-owner lease for one bounded background operation. */
public final class ConfirmedOwnerLease {
    private final Context appContext;
    private final String personId;
    private final String profileKey;
    private final String displayName;
    private final Map<String, String> capturedProfile;

    private ConfirmedOwnerLease(
            Context context,
            Map<String, String> active) {
        appContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        personId = value(active.get("person_id"));
        profileKey = EventTripProfilePolicy.profileKey(personId);
        displayName = value(active.get("name"));
        capturedProfile = new LinkedHashMap<>(active);
    }

    /** Capture only one unambiguous, active, confirmed phone owner. */
    public static ConfirmedOwnerLease capture(Context context) {
        if (context == null || Thread.currentThread().isInterrupted()) return null;
        Context app = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();
        PersonProfileStore people = new PersonProfileStore(app);
        try {
            Map<String, String> active = people.getActiveProfile();
            Map<String, String> owner = people.uniqueConfirmedOwnerCandidate();
            String activeId = value(active.get("person_id"));
            String ownerId = value(owner.get("person_id"));
            if (activeId.isEmpty()
                    || !activeId.equals(ownerId)
                    || !"yes".equals(active.getOrDefault("active", "no"))
                    || !"yes".equals(active.getOrDefault("is_owner", "no"))
                    || !"yes".equals(owner.getOrDefault("is_owner", "no"))
                    || !ProfileMigrationPolicy.isConfirmedDisplayName(
                            active.getOrDefault("name", ""))
                    || !EventTripProfilePolicy.profileKey(activeId).equals(
                            EventTripProfilePolicy.profileKey(ownerId))) {
                return null;
            }
            return new ConfirmedOwnerLease(context, active);
        } finally {
            people.close();
        }
    }

    /** Fresh exact-owner check for a UI write or scheduler boundary. */
    public static boolean isExactActiveOwner(
            Context context,
            String expectedPersonId) {
        String expected = value(expectedPersonId);
        if (expected.isEmpty()) return false;
        ConfirmedOwnerLease lease = capture(context);
        if (lease == null || !expected.equals(lease.personId())) return false;
        try {
            lease.requireActive();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public Map<String, String> capturedProfile() {
        return new LinkedHashMap<>(capturedProfile);
    }

    public String personId() {
        return personId;
    }

    public boolean isActive() {
        if (Thread.currentThread().isInterrupted()) return false;
        PersonProfileStore people = new PersonProfileStore(appContext);
        try {
            Map<String, String> active = people.getActiveProfile();
            Map<String, String> owner = people.uniqueConfirmedOwnerCandidate();
            return personId.equals(value(active.get("person_id")))
                    && personId.equals(value(owner.get("person_id")))
                    && profileKey.equals(EventTripProfilePolicy.profileKey(
                            active.getOrDefault("person_id", "")))
                    && displayName.equals(value(active.get("name")))
                    && "yes".equals(active.getOrDefault("active", "no"))
                    && "yes".equals(active.getOrDefault("is_owner", "no"))
                    && "yes".equals(owner.getOrDefault("is_owner", "no"))
                    && ProfileMigrationPolicy.isConfirmedDisplayName(
                            active.getOrDefault("name", ""));
        } finally {
            people.close();
        }
    }

    public void requireActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("CONFIRMED_OWNER_LEASE_THREAD_INTERRUPTED");
        }
        if (!isActive()) {
            throw new IllegalStateException("CONFIRMED_OWNER_LEASE_REVOKED");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
