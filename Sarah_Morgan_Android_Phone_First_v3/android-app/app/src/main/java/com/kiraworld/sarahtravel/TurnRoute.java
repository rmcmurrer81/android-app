package com.kiraworld.sarahtravel;

/** Authoritative, application-owned route for one Sarah reply. */
public final class TurnRoute {
    public static final String ONLINE_WORKERS_AI = "ONLINE_WORKERS_AI";
    public static final String ONLINE_OPENAI = "ONLINE_OPENAI";
    public static final String ONLINE_CONNECTED_OTHER = "ONLINE_CONNECTED_OTHER";
    public static final String OFFLINE_LOCAL = "OFFLINE_LOCAL";
    public static final String ONLINE_FAILED_FELL_BACK_OFFLINE = "ONLINE_FAILED_FELL_BACK_OFFLINE";
    public static final String RECONNECTING = "RECONNECTING";
    public static final String LOCAL_TOOL_RESULT = "LOCAL_TOOL_RESULT";
    public static final String PUBLIC_SOURCE_TOOL_RESULT = "PUBLIC_SOURCE_TOOL_RESULT";
    /** Legacy imported route; new turns use one of the exact tool routes above. */
    public static final String TOOL_RESULT = "TOOL_RESULT";
    public static final String TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE";
    public static final String UNKNOWN_LEGACY = "UNKNOWN_LEGACY";

    private TurnRoute() { }

    public static String connectedRoute(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        if (normalized.equals("workers-ai")
                || normalized.equals("cloudflare")
                || normalized.equals("cloudflare-workers-ai")) return ONLINE_WORKERS_AI;
        if (normalized.equals("openai")) return ONLINE_OPENAI;
        return ONLINE_CONNECTED_OTHER;
    }

    public static String sourceLabel(String route) {
        if (ONLINE_WORKERS_AI.equals(route)) return "Online mind";
        if (ONLINE_OPENAI.equals(route)) return "Online mind";
        if (ONLINE_CONNECTED_OTHER.equals(route)) return "Online mind";
        if (OFFLINE_LOCAL.equals(route)) return "Offline mind · saved knowledge";
        if (ONLINE_FAILED_FELL_BACK_OFFLINE.equals(route)) return "Online unavailable · answered offline";
        if (RECONNECTING.equals(route)) return "Reconnecting";
        if (LOCAL_TOOL_RESULT.equals(route)) return "On-device Sarah tool";
        if (PUBLIC_SOURCE_TOOL_RESULT.equals(route)) return "Current information verified by a connected public-source tool";
        if (TOOL_RESULT.equals(route)) return "Earlier tool result · exact source route not recorded";
        if (TOOL_UNAVAILABLE.equals(route)) return "Current-information tool unavailable";
        return "Earlier reply · route not recorded";
    }

    public static String runtimeFact(String route) {
        if (ONLINE_WORKERS_AI.equals(route)) {
            return "This turn is being answered by ONLINE_WORKERS_AI. Sarah may truthfully say the online mind is answering, but a model response alone is not web research.";
        }
        if (ONLINE_OPENAI.equals(route)) {
            return "This turn is being answered by ONLINE_OPENAI. A model response alone is not web research; current-source claims require an applied web-search receipt.";
        }
        if (ONLINE_CONNECTED_OTHER.equals(route)) {
            return "This turn is being answered by another recorded connected provider. Its exact provider and model belong in factual audit telemetry; a model reply alone is not web research.";
        }
        if (OFFLINE_LOCAL.equals(route)) {
            return "This turn is being answered by OFFLINE_LOCAL. Sarah must say she is offline if asked and must not claim current web research.";
        }
        if (ONLINE_FAILED_FELL_BACK_OFFLINE.equals(route)) {
            return "The online call failed for this turn and OFFLINE_LOCAL is answering. Sarah must disclose the fallback if mode is relevant and must not claim current web research.";
        }
        if (LOCAL_TOOL_RESULT.equals(route)) {
            return "This turn was answered by an on-device Sarah tool. It may use this profile's saved facts, but it did not verify current web information.";
        }
        if (PUBLIC_SOURCE_TOOL_RESULT.equals(route)) {
            return "This turn contains an actual connected tool result. Sarah must preserve its source and scope and must not expand it into an unsupported booking or search claim.";
        }
        if (TOOL_RESULT.equals(route)) {
            return "This legacy turn says only that a tool was involved. The application did not record whether it was local or connected, so Sarah must not claim current verification.";
        }
        if (TOOL_UNAVAILABLE.equals(route)) {
            return "A requested current-information tool is unavailable. Sarah must not promise background work unless a persisted runnable job was actually created.";
        }
        return "The application has not established a route for this legacy turn. Sarah must not claim to be online or offline from conversational wording.";
    }
}
