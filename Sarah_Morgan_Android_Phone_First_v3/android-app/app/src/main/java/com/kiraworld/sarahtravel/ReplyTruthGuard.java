package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Replaces unsupported asynchronous-work promises with exact action truth. */
public final class ReplyTruthGuard {
    private static final Pattern FALSE_BACKGROUND_PROMISE = Pattern.compile(
            "(?i)\\b(?:i(?:'|’)?ll|i will)\\s+check\\b[^.!?]{0,80}\\bget back to you\\b|"
                    + "\\b(?:i(?:'|’)?ll|i will)\\s+(?:get to work|get back to you|be back|send (?:you )?(?:a )?summary|"
                    + "let you know|(?:start )?(?:looking|researching|comparing|checking)(?: into| for)?|"
                    + "check(?: into| for)?|verify|research|compare|monitor|build|"
                    + "keep (?:looking|researching)|retry (?:later|soon|in the background))\\b|"
                    + "\\bi(?:'|’)?m\\s+(?:on it|working on (?:it|that))\\b|\\bsummary soon\\b");

    private ReplyTruthGuard() { }

    public static SarahChannelResponse enforce(SarahChannelResponse response) {
        return enforce(response, false, "");
    }

    public static SarahChannelResponse enforce(
            SarahChannelResponse response,
            boolean durableJobCreated,
            String actionReceipt) {
        return enforce(response, durableJobCreated, actionReceipt, "");
    }

    public static SarahChannelResponse enforce(
            SarahChannelResponse response,
            boolean durableJobCreated,
            String actionReceipt,
            String pendingReceipt) {
        String[] sentences = response.spoken.split("(?<=[.!?])\\s+");
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : sentences) {
            String clean = sentence == null ? "" : sentence.trim();
            if (clean.isEmpty()) continue;
            if (FALSE_BACKGROUND_PROMISE.matcher(clean).find()) removed = true;
            else kept.add(clean);
        }
        String spoken = String.join(" ", kept).trim();
        String receipt = actionReceipt == null ? "" : actionReceipt.trim();
        if (durableJobCreated) {
            String saved = receipt.isEmpty()
                    ? "A background request was saved and is visible in Sarah's records."
                    : "Saved background work: " + receipt + ".";
            boolean receiptAlreadyVisible = !receipt.isEmpty()
                    && spoken.toLowerCase().contains(receipt.toLowerCase());
            if (!receiptAlreadyVisible) spoken = spoken.isEmpty() ? saved : spoken + " " + saved;
            return response.withGroundingCorrection(
                    spoken,
                    (removed
                            ? "Unsupported future-work wording was replaced with an exact durable action receipt: "
                            : "An exact durable action receipt was added to the reply: ")
                            + (receipt.isEmpty() ? "persisted runnable job" : receipt) + ".");
        }

        String pending = pendingReceipt == null ? "" : pendingReceipt.trim();
        if (!pending.isEmpty()) {
            String saved = "Saved request (not scheduled): " + pending + ".";
            if (!spoken.toLowerCase().contains(pending.toLowerCase())) {
                spoken = spoken.isEmpty() ? saved : spoken + " " + saved;
            }
            return response.withGroundingCorrection(
                    spoken,
                    "The request is persisted as PENDING_NOT_SCHEDULED. It is not a runnable job and no future result was promised.");
        }

        if (!removed) return response;

        if (spoken.isEmpty()) {
            spoken = "I can help with that here, but I have not started a background search or job.";
        }
        return response.withGroundingCorrection(
                spoken,
                "An unsupported promise of future background work was removed; no durable job was created.");
    }
}
