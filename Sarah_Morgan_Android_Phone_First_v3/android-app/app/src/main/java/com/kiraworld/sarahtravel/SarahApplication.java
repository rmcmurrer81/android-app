package com.kiraworld.sarahtravel;

import android.app.Application;
import android.content.Context;

/** App-level initialization with fail-closed event-trip recovery. */
public final class SarahApplication extends Application {
    private static SarahApplication instance;
    private static volatile EventTripPreUpgradeBackupGate.Result eventTripUpgradeState;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        eventTripUpgradeState = EventTripPreUpgradeBackupGate.ensure(this);
        if (!eventTripUpgradeState.mayOpenV2) {
            try {
                android.app.job.JobScheduler jobs = (android.app.job.JobScheduler)
                        getSystemService(JOB_SCHEDULER_SERVICE);
                if (jobs != null) jobs.cancelAll();
            } catch (Exception ignored) { }
            return;
        }
        reconcileInterruptedBookingClearAtStartup();
        ProtectedBackendCapabilities.refreshAsync(this);
    }

    public static Context appContext() {
        return instance == null ? null : instance.getApplicationContext();
    }

    public static EventTripPreUpgradeBackupGate.Result eventTripUpgradeState() {
        return eventTripUpgradeState;
    }

    /**
     * Reconcile only the exact active profile's interrupted private booking
     * clear before normal app work. The store itself preserves and later
     * reports any ambiguous/committed residual; startup never deletes it.
     */
    private void reconcileInterruptedBookingClearAtStartup() {
        String personId = EventTripStore.activePersonId(this);
        if (personId.isEmpty()) return;
        EventTripStore store = null;
        try {
            store = new EventTripStore(this, personId);
            store.reconcileBookingClearOperations();
        } catch (Exception ignored) {
            // Booking entry/read boundaries surface the exact fail-closed status.
        } finally {
            if (store != null) store.close();
        }
    }

}
