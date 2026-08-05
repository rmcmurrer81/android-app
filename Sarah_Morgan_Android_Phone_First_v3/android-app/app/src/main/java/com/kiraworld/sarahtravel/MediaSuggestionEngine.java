package com.kiraworld.sarahtravel;

import java.util.Locale;

public final class MediaSuggestionEngine {
    private MediaSuggestionEngine() { }

    public static String paris(int age, String interests) {
        String likes = interests == null ? "" : interests.toLowerCase(Locale.US);
        if (age < 13) {
            return "For Paris, I would keep it family-friendly: a Miraculous Ladybug story for playful Paris atmosphere, Ratatouille for food and city mood, and The Invention of Hugo Cabret or its film Hugo for a child-friendly story connected to Paris and early cinema. They are stories, not travel guides.";
        }
        if (age < 18) {
            return "For a teen going to Paris, I might suggest Miraculous Ladybug, Hugo, Ratatouille, or an age-appropriate illustrated history or travel book about Paris. I would check the rating and your interests before suggesting anything more mature.";
        }
        if (likes.contains("action")) {
            return "For an adult trip to Paris, Amélie gives romantic city atmosphere, while John Wick: Chapter 4 uses several Paris locations for highly violent fictional action. I would label that one clearly as mature atmosphere, not a guide. I would also add a documentary or history book for factual context.";
        }
        return "For an adult trip to Paris, Amélie is an obvious atmosphere choice. I would pair it with a documentary or history book for factual context and perhaps a food, architecture, or neighborhood guide based on what you enjoy. Fiction can build mood, but it should not be mistaken for practical travel information.";
    }
}
