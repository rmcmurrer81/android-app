package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts an event and destination from natural travel statements. */
public final class EventTripIntentParser {
    public static final class EventIntent {
        public final String eventName;
        public final String destination;
        public final boolean monitoringRequested;
        public final boolean monitoringCancellationRequested;

        EventIntent(
                String eventName,
                String destination,
                boolean monitoringRequested,
                boolean monitoringCancellationRequested) {
            this.eventName = eventName == null ? "" : eventName.trim();
            this.destination = destination == null ? "" : destination.trim();
            this.monitoringRequested = monitoringRequested;
            this.monitoringCancellationRequested = monitoringCancellationRequested;
        }

        public boolean recognized() {
            return !eventName.isEmpty();
        }

        public boolean found() {
            return recognized() && !destination.isEmpty();
        }
    }

    private static final Pattern CITY_FOR_EVENT = Pattern.compile(
            "(?i)\\b(?:going|traveling|travelling|flying|heading|taking (?:the )?(?:train|metro|subway|bus)|planning to go|planning on going)\\s+to\\s+([A-Za-z .'-]{2,50})\\s+for\\s+([^.!?]{2,90})");
    private static final Pattern EVENT_IN_CITY = Pattern.compile(
            "(?i)\\b(?:attending|going to|visiting|taking (?:the )?(?:train|metro|subway|bus) to)\\s+([^.!?]{2,80}?)\\s+(?:in|at)\\s+([A-Za-z .'-]{2,50})(?:[.!?]|$)");
    private static final Pattern MONITORING_NEGATION = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don['’]?t|dont|never|stop|cancel|disable|turn\\s+off|no\\s+longer)\\b[^.!?]{0,50}\\b(?:monitor|track|follow|notify|watch|keep\\s+me\\s+updated)\\b");
    private static final Pattern AFFIRMATIVE_MONITORING_REQUEST = Pattern.compile(
            "(?i)(?:^|\\b(?:please|and|can\\s+you|could\\s+you|would\\s+you|will\\s+you|i\\s+want\\s+(?:you|sarah)\\s+to|i['’]?d\\s+like\\s+(?:you|sarah)\\s+to)\\s+)"
                    + "(?:monitor|track|follow)\\b"
                    + "|\\b(?:watch\\s+for\\s+updates|keep\\s+me\\s+updated|notify\\s+me|let\\s+me\\s+know\\s+(?:when|if)\\s+(?:there\\s+(?:is|are)\\s+)?(?:an?\\s+)?(?:update|updates|new\\s+details?))\\b");
    /*
     * Cancellation is a direct verb/object grammar. A broad "negative word
     * somewhere before monitor" expression is destructive: phrases such as
     * "never stop monitoring" and "do not forget to monitor" mean the
     * opposite. Requiring the cancellation verb immediately before the
     * monitoring verb/noun also excludes "stop talking about monitoring".
     */
    private static final String CANCELLATION_CLAUSE_PREFIX =
            "(?:^|[.!?;]\\s*|\\b(?:and|but|also)\\s+)"
                    + "(?:(?:hey\\s+)?sarah[, ]+\\s*)?"
                    + "(?:(?:can|could|would|will)\\s+you\\s+"
                    + "|i\\s+(?:want|need)\\s+you\\s+to\\s+)?"
                    + "(?:please\\s+)?";
    private static final String STOP_MONITOR_OBJECT =
            "(?:stop|cancel|disable|turn\\s+off)\\s+(?:the\\s+)?"
                    + "(?:monitor(?:ing)?|track(?:ing)?|follow(?:ing)?|watch(?:ing)?|notifications?)";
    private static final String NEGATED_MONITOR_VERB =
            "(?:do\\s+not|don(?:'|\\u2019)?t|dont|never|no\\s+longer)\\s+"
                    + "(?:monitor|track|follow|watch|notify(?:\\s+me)?|keep\\s+me\\s+updated|let\\s+me\\s+know)";
    private static final Pattern MONITORING_CANCELLATION_REQUEST = Pattern.compile(
            "(?i)" + CANCELLATION_CLAUSE_PREFIX
                    + "(?:" + STOP_MONITOR_OBJECT + "|" + NEGATED_MONITOR_VERB + ")\\b");
    private static final Pattern MONITORING_CANCELLATION_TARGET = Pattern.compile(
            "(?i)" + CANCELLATION_CLAUSE_PREFIX
                    + "(?:" + STOP_MONITOR_OBJECT + "|" + NEGATED_MONITOR_VERB + ")"
                    + "(?:\\s+(?:for|about|of))?\\s+([^.!?;]{2,90})(?:[.!?;]|$)");
    private static final Pattern NAMED_MONITOR_CANCELLATION = Pattern.compile(
            "(?i)" + CANCELLATION_CLAUSE_PREFIX
                    + "(?:cancel|disable|turn\\s+off)\\s+(?:the\\s+)?"
                    + "([^.!?;]{2,90}?)\\s+(?:monitor|tracking|notifications?)\\b");

    private EventTripIntentParser() { }

    public static EventIntent parse(String message) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        boolean monitor = monitoringAffirmativelyRequested(lower);
        boolean cancelMonitor = monitoringCancellationRequested(lower);

        KnownEventCatalog.Entry known = KnownEventCatalog.find(safe);
        if (known != null) {
            return new EventIntent(known.eventName, known.destination, monitor, cancelMonitor);
        }

        if (cancelMonitor) {
            String target = cancellationTarget(safe);
            if (!target.isEmpty()) {
                return new EventIntent(target, "", false, true);
            }
        }

        if (containsAny(lower, "new york comic con", "nycc")) {
            return new EventIntent("New York Comic Con", "New York City", monitor, cancelMonitor);
        }
        if (containsAny(lower, "ces", "consumer electronics show")) {
            return new EventIntent("CES", "Las Vegas", monitor, cancelMonitor);
        }
        if (containsAny(lower,
                "san diego comic-con", "san diego comic con", "comic-con international",
                "comic con international", "sdcc")) {
            return new EventIntent("San Diego Comic-Con", "San Diego", monitor, cancelMonitor);
        }
        if (lower.contains("comic con") || lower.contains("comic-con")) {
            String city = lower.contains("san diego") ? "San Diego"
                    : lower.contains("new york") ? "New York City"
                    : cityBeforeFor(safe);
            if (!city.isEmpty()) return new EventIntent("Comic-Con", city, monitor, cancelMonitor);
        }

        Matcher cityForEvent = CITY_FOR_EVENT.matcher(safe);
        if (cityForEvent.find()) {
            String city = cleanCity(cityForEvent.group(1));
            String event = cleanEvent(cityForEvent.group(2));
            if (!city.isEmpty() && !event.isEmpty()) {
                return new EventIntent(event, city, monitor, cancelMonitor);
            }
        }

        Matcher eventInCity = EVENT_IN_CITY.matcher(safe);
        if (eventInCity.find()) {
            String event = cleanEvent(eventInCity.group(1));
            String city = cleanCity(eventInCity.group(2));
            if (!city.isEmpty() && !event.isEmpty()) {
                return new EventIntent(event, city, monitor, cancelMonitor);
            }
        }

        String unfamiliarEvent = GenericEventReference.extract(safe);
        if (!unfamiliarEvent.isEmpty()) {
            return new EventIntent(unfamiliarEvent, "", monitor, cancelMonitor);
        }
        return new EventIntent("", "", false, false);
    }

    private static String cityBeforeFor(String value) {
        Matcher matcher = CITY_FOR_EVENT.matcher(value == null ? "" : value);
        return matcher.find() ? cleanCity(matcher.group(1)) : "";
    }

    private static String cleanCity(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("(?i)\\b(?:the|a|an)\\b", "").trim();
        cleaned = cleaned.replaceAll("[,.!?]+$", "").trim();
        return DestinationParser.canonicalizePlace(cleaned);
    }

    private static String cleanEvent(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll(
                "(?i)\\b(?:and|but|because|while|then|next|this year|next year|with my|with a)\\b.*$",
                "").trim();
        cleaned = cleaned.replaceAll("[,.!?]+$", "").trim();
        if (cleaned.equalsIgnoreCase("ces")) return "CES";
        if (cleaned.equalsIgnoreCase("nycc")) return "New York Comic Con";
        if (cleaned.equalsIgnoreCase("comic con") || cleaned.equalsIgnoreCase("comic-con")) return "Comic-Con";
        return titleCase(cleaned);
    }

    private static boolean looksLikeNamedEvent(String event) {
        String lower = event.toLowerCase(Locale.US);
        return GenericEventReference.looksLikeEvent(event) || containsAny(lower,
                "conference", "convention", "expo", "festival", "summit", "congress",
                "comic", "show", "meetup", "hackathon", "concert", "championship");
    }

    private static String titleCase(String value) {
        String[] words = value.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (word.length() <= 4 && word.equals(word.toUpperCase(Locale.US))) out.append(word);
            else out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.length() > 1 ? word.substring(1).toLowerCase(Locale.US) : "");
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static boolean monitoringAffirmativelyRequested(String lower) {
        String safe = lower == null ? "" : lower.trim();
        if (safe.isEmpty() || monitoringCancellationRequested(safe)
                || MONITORING_NEGATION.matcher(safe).find()) return false;
        return AFFIRMATIVE_MONITORING_REQUEST.matcher(safe).find();
    }

    private static boolean monitoringCancellationRequested(String lower) {
        String safe = lower == null ? "" : lower.trim();
        return !safe.isEmpty()
                && (MONITORING_CANCELLATION_REQUEST.matcher(safe).find()
                    || NAMED_MONITOR_CANCELLATION.matcher(safe).find());
    }

    private static String cancellationTarget(String message) {
        Matcher matcher = MONITORING_CANCELLATION_TARGET.matcher(
                message == null ? "" : message.trim());
        String rawTarget;
        if (matcher.find()) {
            rawTarget = matcher.group(1);
        } else {
            Matcher named = NAMED_MONITOR_CANCELLATION.matcher(
                    message == null ? "" : message.trim());
            if (!named.find()) return "";
            rawTarget = named.group(1);
        }
        String target = rawTarget.trim()
                .replaceAll("(?i)^(?:updates?|notifications?)\\s+(?:for|about|of)\\s+", "")
                .replaceAll("(?i)^(?:the|an?)\\s+", "")
                .replaceAll("[,.!?;]+$", "")
                .trim();
        return target.isEmpty() ? "" : titleCase(target);
    }
}
