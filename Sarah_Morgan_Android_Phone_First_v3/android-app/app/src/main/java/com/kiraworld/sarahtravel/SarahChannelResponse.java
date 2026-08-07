package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates Sarah's public speech from private mind and grounded runtime truth. */
public final class SarahChannelResponse {
    private static final Pattern SPOKEN = tag("SPOKEN");
    private static final Pattern PRIVATE = tag("PRIVATE_MIND");
    private static final Pattern FACTUAL = tag("FACTUAL_TRUTH");
    private static final Pattern CLASSIFICATION = tag("CLASSIFICATION");

    public final String spoken;
    public final String privateMind;
    public final String factualTruth;
    public final String classification;
    public final boolean structured;

    private SarahChannelResponse(String spoken, String privateMind, String factualTruth,
                                 String classification, boolean structured) {
        this.spoken = clean(spoken);
        this.privateMind = clean(privateMind);
        this.factualTruth = clean(factualTruth);
        this.classification = normalizeClassification(classification);
        this.structured = structured;
    }

    public static SarahChannelResponse parse(String raw) {
        String source = raw == null ? "" : raw.trim();
        boolean hasPrivate = source.toUpperCase(Locale.US).contains("<PRIVATE_MIND>")
                || source.toUpperCase(Locale.US).contains("<FACTUAL_TRUTH>");
        String spoken = group(SPOKEN, source);
        String privateMind = group(PRIVATE, source);
        String factualTruth = group(FACTUAL, source);
        String classification = group(CLASSIFICATION, source);
        boolean structured = !spoken.isEmpty() || !privateMind.isEmpty() || !factualTruth.isEmpty();
        if (structured) {
            if (spoken.isEmpty()) spoken = "I’m sorry—I could not safely separate my public reply from my private record. Please ask me again.";
            return new SarahChannelResponse(spoken, privateMind, factualTruth, classification, true);
        }
        if (hasPrivate) {
            return new SarahChannelResponse(
                    "I’m sorry—I could not safely separate my public reply from my private record. Please ask me again.",
                    "Malformed three-channel response was withheld.",
                    "A model response contained private-channel markers but no valid public channel.",
                    "RUNTIME_STATE_ERROR",
                    true);
        }
        return spokenOnly(source, "Connected or local source returned an ordinary public reply.");
    }

    public static SarahChannelResponse spokenOnly(String spoken, String factualTruth) {
        return new SarahChannelResponse(spoken, "", factualTruth, "TRUTHFUL_STATEMENT", false);
    }

    public static String promptContract() {
        return "Return exactly four XML-style fields and nothing outside them: "
                + "<SPOKEN>the public reply</SPOKEN> "
                + "<PRIVATE_MIND>a brief private subjective record, not hidden chain-of-thought</PRIVATE_MIND> "
                + "<FACTUAL_TRUTH>grounded facts and unknowns for this turn</FACTUAL_TRUTH> "
                + "<CLASSIFICATION>one of TRUTHFUL_STATEMENT, DELIBERATE_LIE, JOKE_OR_SARCASM, EVASION, PRIVACY_PROTECTION, SOFTENED_TRUTH, PARTIAL_TRUTH, EXAGGERATION, UNCERTAIN_BELIEF, SINCERE_MISTAKE, HALLUCINATION_OR_GROUNDING_ERROR, IDENTITY_ATTRIBUTION_ERROR, RUNTIME_STATE_ERROR</CLASSIFICATION>. Only SPOKEN is shown or sent to speech.";
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*(.*?)\\s*</" + name + ">");
    }

    private static String group(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? clean(matcher.group(1)) : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeClassification(String value) {
        String clean = clean(value).toUpperCase(Locale.US).replace(' ', '_');
        return clean.matches("TRUTHFUL_STATEMENT|DELIBERATE_LIE|JOKE_OR_SARCASM|EVASION|PRIVACY_PROTECTION|SOFTENED_TRUTH|PARTIAL_TRUTH|EXAGGERATION|UNCERTAIN_BELIEF|SINCERE_MISTAKE|HALLUCINATION_OR_GROUNDING_ERROR|IDENTITY_ATTRIBUTION_ERROR|RUNTIME_STATE_ERROR")
                ? clean : "UNCERTAIN_BELIEF";
    }
}
