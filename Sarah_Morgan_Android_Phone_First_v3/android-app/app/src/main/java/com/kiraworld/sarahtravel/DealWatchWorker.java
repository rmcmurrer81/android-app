package com.kiraworld.sarahtravel;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.Map;

public final class DealWatchWorker extends Worker {
    public DealWatchWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        SarahDatabase db = new SarahDatabase(getApplicationContext());
        boolean temporaryFailure = false;
        try {
            List<Map<String, String>> watches = db.listActiveDealWatches(100);
            for (Map<String, String> watch : watches) {
                long id = longValue(watch, "id", 0);
                try {
                    TravelDealResult result = TravelDealGateway.check(getApplicationContext(), watch);
                    long now = System.currentTimeMillis();
                    if (!result.configured) {
                        db.updateDealWatchCheck(id, "backend_not_configured", now, 0, watch.getOrDefault("currency", "USD"));
                        continue;
                    }
                    if (!result.found) {
                        db.updateDealWatchCheck(id, "checked_no_result", now, 0, result.currency);
                        continue;
                    }
                    if (result.isDeal && shouldNotify(watch, result)) {
                        DealNotificationManager.post(getApplicationContext(), watch, result);
                        db.updateDealWatchCheck(id, "deal_notified", now, result.totalPrice, result.currency);
                    } else {
                        db.updateDealWatchCheck(id, "checked_no_new_deal", now, 0, result.currency);
                    }
                } catch (Exception ignored) {
                    temporaryFailure = true;
                    db.updateDealWatchCheck(id, "temporary_error", System.currentTimeMillis(), 0, watch.getOrDefault("currency", "USD"));
                }
            }
        } finally {
            db.close();
        }
        return temporaryFailure ? Result.retry() : Result.success();
    }

    private static boolean shouldNotify(Map<String, String> watch, TravelDealResult result) {
        if (!result.isDeal || result.totalPrice <= 0) return result.isDeal;
        double previous = doubleValue(watch, "last_notified_price", 0);
        return previous <= 0 || result.totalPrice <= previous * 0.98;
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(Map<String, String> row, String key, double fallback) {
        try { return Double.parseDouble(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
