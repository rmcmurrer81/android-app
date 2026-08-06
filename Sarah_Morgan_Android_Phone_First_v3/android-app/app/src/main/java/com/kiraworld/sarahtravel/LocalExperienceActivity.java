package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

import java.util.Map;

/** Public-source discovery across free, paid, food, culture, events and quiet options. */
public final class LocalExperienceActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TravelContextSnapshot trip = TravelContextSnapshot.load(this);
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Discover locally",
                trip.hasDestination() ? trip.destination : "Choose a destination",
                trip.hasDates() ? trip.dateLabel() : "Use official current sources for dates, hours and closures"));

        PersonProfileStore people = new PersonProfileStore(this);
        Map<String, String> person;
        try {
            person = people.getActiveProfile();
        } finally {
            people.close();
        }
        String interests = peopleSafeSummary(person, trip.personId);

        LinearLayout personal = TravelUi.card(this, TravelUi.LAVENDER);
        personal.addView(TravelUi.cardTitle(this, "🧭", "Personalized without becoming a checklist"));
        personal.addView(TravelUi.body(this,
                interests.isEmpty()
                        ? "Sarah can start with a balanced mix of free, inexpensive and optional paid ideas, then learn what the active person actually enjoys."
                        : "Sarah should use these active-profile details: " + interests));
        root.addView(personal);

        root.addView(experience(
                "🆓", "Free and inexpensive",
                "Parks, public spaces, neighborhood walks, free museum periods, libraries, markets, viewpoints and official city programs.",
                TravelUi.MINT,
                ExternalTravelLinks.freeThings(trip)));
        root.addView(experience(
                "🍽️", "Restaurants and food",
                "Compare nearby food by price, distance, dietary needs, noise, opening hours and whether reservations are required.",
                TravelUi.PEACH,
                ExternalTravelLinks.restaurants(trip)));
        root.addView(experience(
                "💵", "Affordable food",
                "Search for casual and lower-cost options near the active destination or venue rather than assuming every meal is a destination restaurant.",
                TravelUi.SKY,
                ExternalTravelLinks.affordableFood(trip)));
        root.addView(experience(
                "📅", "Current events",
                "Search official calendars, venues and event pages for the active dates. Sarah should verify the event's real city, venue and dates before saving it.",
                TravelUi.LAVENDER,
                ExternalTravelLinks.localEvents(trip)));
        root.addView(experience(
                "🏛️", "History, museums and landmarks",
                "Prioritize official sources for hours, admission, timed entry and accessibility. Mix major sites with one or two smaller places.",
                TravelUi.SKY,
                ExternalTravelLinks.museumsHistory(trip)));
        root.addView(experience(
                "🎬", "Movies, television and filming locations",
                "Use public reference sources for filming background and maps for the locations. Fiction provides atmosphere, not practical travel guidance.",
                TravelUi.PEACH,
                ExternalTravelLinks.filmingLocations(trip)));
        root.addView(experience(
                "🌿", "Quiet places and reset breaks",
                "Parks, gardens, libraries and calmer spaces can make a crowded event or city day easier without abandoning the trip.",
                TravelUi.MINT,
                ExternalTravelLinks.quietPlaces(trip)));
        root.addView(experience(
                "♿", "Accessible attractions",
                "Search official accessibility pages, then verify entrances, elevators, seating, restrooms, sensory supports and current outages.",
                TravelUi.LAVENDER,
                ExternalTravelLinks.accessiblePlaces(trip)));

        LinearLayout media = TravelUi.card(this, TravelUi.SKY);
        media.addView(TravelUi.cardTitle(this, "🖼️", "Maps, photos and videos"));
        media.addView(TravelUi.body(this,
                "Return to Sarah's Explore panel for an inline public photo preview plus maps, more public photos, videos, routes and official-source searches."));
        root.addView(media);
    }

    private LinearLayout experience(
            String icon,
            String title,
            String description,
            int color,
            String url) {
        LinearLayout card = TravelUi.card(this, color);
        card.addView(TravelUi.cardTitle(this, icon, title));
        card.addView(TravelUi.body(this, description));
        card.addView(TravelUi.outlineButton(this, "Explore " + title.toLowerCase(),
                v -> TravelUi.open(this, url)));
        return card;
    }

    private String peopleSafeSummary(Map<String, String> person, String personId) {
        StringBuilder out = new StringBuilder();
        String profileInterests = person.getOrDefault("interests", "").trim();
        if (!profileInterests.isEmpty()) out.append("Interests: ").append(profileInterests);
        String needs = TravelerNeedsStore.summary(this, personId);
        if (!needs.isEmpty()) {
            if (out.length() > 0) out.append("; ");
            out.append(needs);
        }
        return out.toString();
    }
}
