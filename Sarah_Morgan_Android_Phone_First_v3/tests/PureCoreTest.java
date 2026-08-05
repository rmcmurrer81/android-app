package com.kiraworld.sarahtravel;
import java.util.*;
public class PureCoreTest {
  public static void main(String[] args) {
    var memories = MemoryExtractor.extract("I want to visit Salem. My favorite food is pizza. I am worried about turbulence.");
    if (memories.size() < 3) throw new RuntimeException("Memory extraction failed");
    Map<String,String> profile = new LinkedHashMap<>();
    profile.put("name", "Robert");
    profile.put("hometown", "Newark");
    profile.put("age", "45");
    profile.put("interests", "action movies and history");
    String prompt = SarahPromptBuilder.build(profile, List.of(), List.of(), List.of(), false, true);
    if (!prompt.contains("DESTINATION MEDIA SUGGESTIONS") || !prompt.contains("Robert")) throw new RuntimeException("Prompt feature missing");
    String reply = DemoSarah.reply("This is my first flight", profile, false);
    if (!reply.toLowerCase().contains("flying")) throw new RuntimeException("First-flight response missing");
    String calm = CalmSupport.turbulenceSupport(profile);
    if (!calm.toLowerCase().contains("seat belt")) throw new RuntimeException("Turbulence support missing");
    var trivia = CalmSupport.questions(profile, List.of(Map.of("status", "planned", "destination", "Paris")), List.of());
    if (trivia.size() < 3) throw new RuntimeException("Personalized trivia missing");
    String media = MediaSuggestionEngine.paris(45, "action movies");
    if (!media.contains("Amélie") || !media.contains("John Wick")) throw new RuntimeException("Adult Paris media missing");
    String childMedia = MediaSuggestionEngine.paris(10, "animation");
    if (!childMedia.contains("Miraculous Ladybug") || childMedia.contains("John Wick")) throw new RuntimeException("Child media filtering failed");
    System.out.println("PURE_CORE_TESTS_PASS memories=" + memories.size());
  }
}
