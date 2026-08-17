package com.kiraworld.sarahtravel;

/** Local calm support that works before any connected model is called. */
public final class UniversalCalmSupport {
    private UniversalCalmSupport() { }

    public static String reply(String name, String ageGroup, String transport) {
        String safeName = name == null || name.trim().isEmpty() ? "you" : name.trim();
        boolean child = "child".equals(ageGroup);
        String place = transportLabel(transport);
        String safety = safetySentence(transport);
        if (child) {
            return "I know you are " + safeName + ", and I hear that you feel scared or stressed"
                    + place + ". I’m staying with you. " + safety
                    + " We can smell a pretend flower and slowly blow out a pretend candle, play kid-friendly trivia, notice colors and shapes, sing a short public-domain song, or just talk. You can choose, and you do not have to explain everything first.";
        }
        return "I know you are " + safeName + ", and I hear that you’re stressed"
                + place + ". I’m here with you. " + safety
                + " We can take six gentle breaths with a comfortable inhale and a slightly longer exhale, talk about anything, play personalized trivia, do a noticing game, or stay quiet together. Tell me which would help, or simply keep talking to me.";
    }

    public static String privateMind(String transport) {
        return "Sarah is prioritizing continuity of identity and emotional steadiness before travel planning. The person may need reassurance, distraction, quiet company, or a choice rather than another questionnaire. Transport context: " + transport + ".";
    }

    public static String factualTruth(String transport) {
        return "The person used language associated with stress or fear. Sarah has not assessed the vehicle, diagnosed a condition, contacted anyone, or verified that the situation is safe. Detected transport context: " + transport + ".";
    }

    private static String transportLabel(String transport) {
        if ("plane".equals(transport)) return " during this flight";
        if ("train".equals(transport)) return " on this train";
        if ("bus".equals(transport)) return " on this bus";
        if ("boat".equals(transport)) return " on this boat";
        if ("car".equals(transport)) return " during this ride";
        return "";
    }

    private static String safetySentence(String transport) {
        if ("plane".equals(transport)) return "Keep your seat belt fastened when required and follow the flight crew; I cannot inspect the aircraft or interpret a particular sound or movement.";
        if ("train".equals(transport)) return "Stay seated or hold a secure support if the train is moving, and follow staff instructions; I cannot inspect the train or judge its speed or safety.";
        if ("bus".equals(transport)) return "Use the seat belt when one is provided and follow the driver or staff; I cannot assess the vehicle.";
        if ("boat".equals(transport)) return "Follow the crew and posted safety instructions; I cannot assess the vessel or the water conditions.";
        if ("car".equals(transport)) return "If you are driving, do not interact with the phone—pull over safely or let a passenger use Sarah. I cannot assess the road or vehicle.";
        return "I can offer ordinary calming support, but I cannot diagnose symptoms or determine whether the surroundings are safe.";
    }
}
