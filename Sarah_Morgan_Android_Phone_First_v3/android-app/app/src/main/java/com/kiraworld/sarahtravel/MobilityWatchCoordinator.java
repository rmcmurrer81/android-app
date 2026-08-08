package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.List;
import java.util.Map;

/** Checks saved multimodal watches through the configured team travel backend. */
public final class MobilityWatchCoordinator {
    private MobilityWatchCoordinator() { }

    /** Returns true when at least one check failed temporarily and should retry. */
    public static boolean run(Context context) {
        MobilityWatchStore store = new MobilityWatchStore(context.getApplicationContext());
        boolean temporaryFailure = false;
        try {
            List<Map<String, String>> watches = store.listActiveWatches(100);
            for (Map<String, String> watch : watches) {
                long id = longValue(watch, "id", 0);
                try {
                    MobilityResult result = MobilityGateway.check(context, watch);
                    long now = System.currentTimeMillis();
                    if (!result.configured) {
                        store.updateWatch(id, "setup_required", now, "", result.sourceNote);
                    } else if (!result.found) {
                        store.updateWatch(id, "checked_no_result", now, "", result.sourceNote);
                    } else {
                        String status = result.significant ? "update_notified" : "checked_no_significant_change";
                        store.updateWatch(id, status, now, result.summary, result.sourceNote);
                        if (result.significant) MobilityNotificationManager.post(context, watch, result);
                    }
                } catch (Exception ignored) {
                    temporaryFailure = true;
                    store.updateWatch(id, "temporary_error", System.currentTimeMillis(), "", "Temporary backend error");
                }
            }
        } finally {
            store.close();
        }
        return temporaryFailure;
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
