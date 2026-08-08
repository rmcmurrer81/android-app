package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.Map;

/** Exact application-owned identity and per-turn route truth for explicit acceptance prompts. */
public final class OfflineTuringPolicy {
    private OfflineTuringPolicy() { }

    public static String answer(
            String message,
            Map<String, String> profile,
            String authoritativeTurnRoute) {
        String lower = message == null ? "" : message.trim().toLowerCase(Locale.US);
        String name = profile == null ? "" : clean(profile.get("name"));
        if (lower.matches(".*\\bwhat(?:'s| is) your name\\b.*")) {
            return "My name is Sarah Morgan. I’m your travel and conversational companion.";
        }
        if (lower.matches(".*\\bwho am i\\b.*")) {
            return "You are " + (name.isEmpty() ? "the person using the active profile" : name)
                    + ". I’m using only this active person’s profile for the conversation.";
        }
        if (lower.contains("online or offline") || lower.contains("offline or online")
                || lower.contains("are you online") || lower.contains("are you offline")) {
            if (TurnRoute.ONLINE_WORKERS_AI.equals(authoritativeTurnRoute)
                    || TurnRoute.ONLINE_OPENAI.equals(authoritativeTurnRoute)
                    || TurnRoute.ONLINE_CONNECTED_OTHER.equals(authoritativeTurnRoute)) {
                return "This reply came through my connected online mind. I can have a normal conversation, but I still need a verified source lookup before I claim current prices, events, schedules, or other changing facts.";
            }
            if (TurnRoute.ONLINE_FAILED_FELL_BACK_OFFLINE.equals(authoritativeTurnRoute)) {
                return "The online mind was attempted for this turn but did not answer, so this reply is from my offline mind. I can use saved knowledge and on-device tools, but I cannot claim current web research.";
            }
            if (TurnRoute.OFFLINE_LOCAL.equals(authoritativeTurnRoute)) {
                return "This reply is from my offline mind. I can converse, use saved knowledge, and run installed calming or trivia tools, but I cannot verify current prices, events, schedules, or web information.";
            }
            if (TurnRoute.PUBLIC_SOURCE_TOOL_RESULT.equals(authoritativeTurnRoute)) {
                return "This reply came from a connected public-source lookup. I can use only the exact sources and scope recorded for this turn.";
            }
            if (TurnRoute.LOCAL_TOOL_RESULT.equals(authoritativeTurnRoute)) {
                return "This reply came from an on-device tool. I can use saved information and local tools, but this turn did not verify current web information.";
            }
            return "I cannot establish an online or offline conversation route for this reply, so I will not guess.";
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
