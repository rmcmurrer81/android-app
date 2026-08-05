package com.kiraworld.sarahtravel;

public final class MediaSuggestionEngine {
    private MediaSuggestionEngine() { }

    public static String paris(int age, String interests) {
        if (age < 13) {
            return "For Paris, I would keep it family-friendly: Miraculous Ladybug for playful Paris atmosphere, Ratatouille for food and city mood, and Hugo or The Invention of Hugo Cabret for a story connected to Paris and early cinema. They are stories, not travel guides.";
        }
        if (age < 18) {
            return "For a teen going to Paris, Miraculous Ladybug, Hugo, Ratatouille, or an age-appropriate illustrated history of Paris could work. I would match the final choices to what you actually enjoy instead of giving the same list to everyone.";
        }
        return "For Paris atmosphere, Amélie is a familiar starting point. For factual context, I would pair it with a documentary, neighborhood history, museum guide, food book, or architecture book based on your interests. Fiction can build mood, but it is not practical travel guidance.";
    }
}
