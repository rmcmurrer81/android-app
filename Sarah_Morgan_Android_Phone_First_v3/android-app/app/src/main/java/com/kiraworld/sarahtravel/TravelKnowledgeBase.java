package com.kiraworld.sarahtravel;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stable offline travel knowledge. It deliberately excludes live prices,
 * schedules, closures, visa rules, and other facts that need current research.
 */
public final class TravelKnowledgeBase {
    public static final class Entry {
        public final String name;
        public final String history;
        public final String firstVisit;
        public final String transport;
        public final String practical;
        public final String familyMedia;
        public final String adultMedia;

        Entry(String name, String history, String firstVisit, String transport,
              String practical, String familyMedia, String adultMedia) {
            this.name = name;
            this.history = history;
            this.firstVisit = firstVisit;
            this.transport = transport;
            this.practical = practical;
            this.familyMedia = familyMedia;
            this.adultMedia = adultMedia;
        }
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        add(new Entry(
                "Paris",
                "Paris layers Roman roots, medieval religious and royal history, the French Revolution, nineteenth-century rebuilding, occupation and liberation during World War II, and modern political and cultural life. A history-focused visit can connect the Île de la Cité, the Louvre, the Latin Quarter, the Marais, revolutionary sites, and museums without treating the city as one single era.",
                "A first visit usually works better with one major anchor and one neighborhood each day rather than a landmark race. Leave space for walking, cafés, parks, and an indoor backup.",
                "The Métro and regional rail cover much of the city, but walking distances, stairs, station transfers, and crowds can add up. Current accessibility details and service disruptions need a live check.",
                "Paris can involve long walks, lines, cobblestones, stairs, and crowded attractions. Compare Charles de Gaulle and Orly when planning flights, but use live sources for actual fares and ground-transport schedules.",
                "Miraculous Ladybug, Ratatouille, and Hugo can provide age-appropriate atmosphere; they are stories, not travel guides.",
                "Amélie can provide fictional atmosphere. Pair fiction with a documentary, neighborhood history, museum guide, or architecture book for factual context."));

        add(new Entry(
                "London",
                "London's history includes Roman Londinium, medieval monarchy and trade, the Tudor and Stuart periods, the Great Fire, imperial expansion, industrialization, wartime bombing, postwar migration, and a highly diverse modern city. History can be explored through Westminster, the City, the Tower area, museums, markets, and neighborhoods shaped by different periods.",
                "For a first visit, group places by area: Westminster, the South Bank, the City and Tower, or a museum-and-neighborhood day. Crossing the city repeatedly wastes time.",
                "The Underground, buses, and rail are extensive. Step-free routes vary by station, and walking inside large stations can be substantial, so current accessibility maps matter.",
                "London has several airports with very different ground-transfer times. Compare the full trip cost and time, not only the headline airfare.",
                "Paddington, Mary Poppins, and selected Doctor Who stories can provide family-friendly atmosphere depending on age.",
                "Sherlock adaptations, historical dramas, and documentaries can provide different views of London; none should replace practical planning."));

        add(new Entry(
                "New York City",
                "New York City's history includes Indigenous Lenape homelands, Dutch New Amsterdam, British colonial rule, immigration, industrial growth, labor movements, Harlem's cultural influence, twentieth-century urban change, and continuing reinvention across five boroughs.",
                "Choose one or two neighboring areas per day. Manhattan alone is larger and slower to cross than many first-time visitors expect, and the other boroughs deserve their own time.",
                "Subways and buses cover much of the city, but elevators and service changes vary. Current transit alerts and station accessibility need live checking.",
                "Expect extensive walking, crowded platforms, noise, and changing weather. Newark, JFK, and LaGuardia have different routes and tradeoffs.",
                "Spider-Man, Sesame Street, and many museum- or neighborhood-based stories can create family-friendly interest depending on age.",
                "Documentaries, neighborhood histories, and films can show very different New Yorks; match them to the borough and era the traveler cares about."));

        add(new Entry(
                "Rome",
                "Rome combines ancient republican and imperial history, early Christianity, papal power, Renaissance and Baroque rebuilding, Italian unification, fascism, and modern civic life. Ancient ruins, churches, streets, and museums often sit directly beside later layers.",
                "A first visit is easier when ancient Rome, the historic center, and Vatican-related sites are separated into different days with rest built in.",
                "Metro coverage is more limited than in some European capitals, so walking and buses matter. Uneven surfaces and heat can shape the day.",
                "Cobblestones, stairs, crowds, summer heat, and dress requirements at some religious sites deserve advance planning.",
                "Age-appropriate Roman history books, museum resources, and family films can make the ruins easier to understand.",
                "Pair a historical documentary or readable Roman history with fiction for atmosphere rather than treating film as evidence."));

        add(new Entry(
                "Tokyo",
                "Tokyo developed from Edo, the seat of the Tokugawa shogunate, into the imperial capital, survived major earthquake and wartime destruction, and became a vast modern metropolis while retaining neighborhood, shrine, market, and craft traditions.",
                "Plan by district rather than by a single citywide checklist. A calm neighborhood, one major attraction, food, and a flexible evening often works better than constant cross-city travel.",
                "Rail and subway systems are extensive and reliable, but large stations, transfers, and rush-hour crowds can be overwhelming. Current route and accessibility tools are important.",
                "Language support is common in major transit areas, but sensory load, station scale, etiquette, and cashless-versus-cash differences should be considered.",
                "Age-appropriate animation, museum material, and illustrated culture books can build interest while still separating fiction from everyday Tokyo.",
                "Use documentaries, neighborhood guides, food writing, architecture, and selected films to see more than one stereotype of the city."));

        add(new Entry(
                "Washington, D.C.",
                "Washington, D.C. reflects the creation of the federal capital, slavery and emancipation, national political institutions, protest movements, neighborhood history, and the complicated relationship between federal power and local residents.",
                "The National Mall looks compact on maps but involves substantial walking. Group museums and monuments, and reserve time for neighborhoods beyond the federal core.",
                "Metro and buses are useful, but station elevators, weekend work, and walking distances require current checks.",
                "Many major museums are free, but timed entry, security screening, closures, and special exhibitions can change.",
                "Museum programs and age-appropriate American history resources work well for younger travelers.",
                "Pair political or historical documentaries with local history so the city is not reduced to monuments and government buildings."));

        add(new Entry(
                "Chicago",
                "Chicago's history includes Indigenous homelands, rapid nineteenth-century growth, the Great Fire, labor struggles, migration, architecture, industry, organized crime mythology, segregation, music, and neighborhood activism.",
                "A first visit can combine architecture, one museum, the lakefront, and one neighborhood rather than staying only downtown.",
                "The 'L', buses, and commuter rail cover many areas. Weather and station accessibility can strongly affect the experience.",
                "Wind, winter cold, summer heat, festivals, and long distances between neighborhoods make timing important.",
                "Museum and architecture resources can be matched to children or teens without relying on crime stereotypes.",
                "Architecture documentaries, blues and jazz history, labor history, and neighborhood writing give a fuller picture than gangster stories."));

        add(new Entry(
                "Boston",
                "Boston's history includes Indigenous homelands, Puritan settlement, Atlantic trade and slavery, the American Revolution, abolition, immigration, education, industry, and neighborhood change.",
                "The historic core is walkable, but a strong visit should include more than the Freedom Trail. Group waterfront, museum, university, and neighborhood interests.",
                "Subway, buses, commuter rail, and walking are useful, though older stations and streets vary in accessibility.",
                "Brick sidewalks, winter weather, stairs, and event crowds can affect pacing.",
                "Revolutionary history and museum resources are easy to match to school-age travelers.",
                "Use local history, abolition history, immigration stories, and documentaries to move beyond a single Revolution narrative."));

        add(new Entry(
                "Salem",
                "Salem is widely associated with the 1692 witch trials, but its history also includes Indigenous homelands, maritime trade, slavery and abolition, industry, immigration, architecture, and the later creation of a tourism identity around the trials.",
                "A thoughtful visit should distinguish original trial-related sites, later memorials, museums, maritime history, and modern seasonal entertainment.",
                "The historic center is walkable, but crowds and parking can become difficult during the fall season. Regional rail can be useful from Boston.",
                "October crowds, ticketed attractions, weather, and uneven walking surfaces require advance planning. Historical claims should be checked against reputable museum or archival sources.",
                "Age-appropriate museum programs and carefully chosen history books are better than frightening entertainment for some children.",
                "Pair trial history with maritime and local social history so the city is not reduced to one tragic event."));

        add(new Entry(
                "Charleston",
                "Charleston's history includes Indigenous homelands, colonial settlement, slavery and the Atlantic economy, the Civil War, emancipation, Reconstruction, preservation, tourism, and continuing debates over memory and inequality.",
                "A responsible first visit should include African American history, architecture, waterfront areas, and neighborhoods rather than treating the city only as picturesque scenery.",
                "The historic peninsula is walkable, but heat, humidity, uneven sidewalks, and distances can matter. Current transit and accessibility details need checking.",
                "Summer heat, storms, and the interpretation quality of historic sites should influence planning.",
                "Choose age-appropriate museum and history resources that explain slavery honestly without overwhelming younger travelers.",
                "Use reputable local history, African American history, architecture, and foodways sources rather than romanticized plantation narratives."));

        add(new Entry(
                "San Francisco",
                "San Francisco's history includes Indigenous Ohlone homelands, Spanish and Mexican rule, the Gold Rush, immigration, labor, the 1906 earthquake and fire, wartime industry, civil-rights movements, and technology-driven change.",
                "Plan around hills and neighborhoods. One waterfront or downtown area plus one neighborhood often makes a better day than constant backtracking.",
                "Buses, light rail, BART, ferries, and cable cars serve different purposes. Hills and station accessibility should be considered.",
                "Microclimates, steep streets, fog, and event crowds can change the experience within a single day.",
                "Family museum resources, nature, and transit history can provide age-appropriate context.",
                "Pair films with earthquake history, immigration history, civil-rights history, architecture, and neighborhood writing."));

        add(new Entry(
                "Los Angeles",
                "Los Angeles history includes Indigenous Tongva homelands, Spanish and Mexican rule, annexation, rail and real-estate growth, film and aerospace industries, migration, racial conflict, freeways, and neighborhood activism.",
                "Treat Los Angeles as a region, not a compact downtown. Choose one part of the city per day and allow generous travel time.",
                "Metro rail and buses are useful in some corridors, but many trips still require careful route planning. Distances are easy to underestimate.",
                "Traffic, heat, air quality, event schedules, and parking costs can matter more than the map suggests.",
                "Studio, museum, science, and beach interests can be matched to age without promising celebrity encounters.",
                "Film history is relevant, but pair it with neighborhood, migration, architecture, and civil-rights history."));
    }

    private TravelKnowledgeBase() { }

    private static void add(Entry entry) {
        ENTRIES.put(entry.name.toLowerCase(Locale.US), entry);
    }

    public static Entry find(String destination) {
        if (destination == null) return null;
        return ENTRIES.get(destination.trim().toLowerCase(Locale.US));
    }

    public static String answer(String destination, String topic, boolean childSafe) {
        Entry entry = find(destination);
        if (entry == null) {
            return destination + " is not in my offline knowledge pack yet. I can still help structure the trip, and Smart mode can research current details when connected.";
        }
        String normalized = topic == null ? "overview" : topic.toLowerCase(Locale.US);
        if (normalized.contains("history")) return entry.history;
        if (normalized.contains("transport") || normalized.contains("getting around")) return entry.transport;
        if (normalized.contains("practical") || normalized.contains("first visit") || normalized.contains("plan")) {
            return entry.firstVisit + " " + entry.practical;
        }
        if (normalized.contains("movie") || normalized.contains("book") || normalized.contains("media")) {
            return childSafe ? entry.familyMedia : entry.adultMedia;
        }
        return entry.firstVisit + " " + entry.practical;
    }

    public static String compare(String first, String second, String topic, boolean childSafe) {
        Entry a = find(first);
        Entry b = find(second);
        if (a == null || b == null) {
            return "I can compare " + first + " and " + second
                    + " by travel pace, history, transport, or atmosphere, but at least one is not in my offline knowledge pack yet.";
        }
        String normalized = topic == null ? "overview" : topic.toLowerCase(Locale.US);
        if (normalized.contains("history")) {
            return first + ": " + a.history + "\n\n" + second + ": " + b.history;
        }
        if (normalized.contains("movie") || normalized.contains("book") || normalized.contains("media")) {
            return first + ": " + (childSafe ? a.familyMedia : a.adultMedia)
                    + "\n\n" + second + ": " + (childSafe ? b.familyMedia : b.adultMedia);
        }
        return first + ": " + a.firstVisit + "\n\n" + second + ": " + b.firstVisit;
    }
}
