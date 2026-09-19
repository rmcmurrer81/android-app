package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Conservative natural-language answers for one exact bound email proposal. */
public final class EmailConversationPromptPolicy {
    public static final int NOT_AN_ANSWER = 0;
    public static final int REMEMBER = 1;
    public static final int DEFER = 2;
    public static final int REJECT = -1;

    private EmailConversationPromptPolicy() { }

    public static int classify(String value) {
        String answer = value == null ? "" : value
                .trim()
                .toLowerCase(Locale.US)
                .replace('\u2019', '\'')
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ");
        switch (answer) {
            case "yes":
            case "yes please":
            case "yes remember it":
            case "yes remember this":
            case "remember it":
            case "remember this":
            case "add it":
            case "add this":
                return REMEMBER;
            case "no":
            case "no thanks":
            case "no thank you":
            case "do not remember it":
            case "do not remember this":
            case "don't remember it":
            case "don't remember this":
            case "ignore it":
            case "ignore this":
                return REJECT;
            case "not now":
            case "maybe later":
            case "ask me later":
            case "later":
                return DEFER;
            default:
                return NOT_AN_ANSWER;
        }
    }
}
