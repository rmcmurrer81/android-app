package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/**
 * Sarah's visual command center across the four hackathon tracks: AI planning,
 * hospitality, local experiences, and sustainable/accessibility-aware travel.
 */
public final class TravelHubActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        TravelContextSnapshot trip = TravelContextSnapshot.load(this);

        String subtitle = trip.hasDestination()
                ? trip.dateLabel() + " • " + trip.personName
                : "Talk with Sarah about a place or event and it will appear here.";
        root.addView(TravelUi.hero(
                this,
                "Sarah Travel OS",
                trip.title(),
                subtitle));

        LinearLayout now = TravelUi.card(this, TravelUi.SKY);
        now.addView(TravelUi.cardTitle(this, "✨", "One trip, every part connected"));
        now.addView(TravelUi.body(this,
                "Compare where to stay, how to get there, what to do, local rides, road-trip stops, loyalty value, accessibility needs, and hotel requests without losing the conversation."));
        now.addView(TravelUi.primaryButton(this, "Open Sarah's travel notebook",
                v -> TravelUi.start(this, TravelNotebookActivity.class)));
        root.addView(now);

        root.addView(TravelUi.section(this, "Plan and book"));
        root.addView(feature(
                "🏨", "Hotels and rooms",
                "Compare major booking sites, direct hotel websites, total-price details, cancellation rules, loyalty value, and live team-backend results when configured.",
                TravelUi.PEACH,
                HotelSearchActivity.class));
        root.addView(feature(
                "✈️", "Flights, Amtrak, buses and local transit",
                "Start with the whole door-to-door trip instead of assuming a flight. Open current official and comparison sources with the active route and dates.",
                TravelUi.SKY,
                TransportOptionsActivity.class));
        root.addView(feature(
                "🚕", "Airport and local rides",
                "Open Uber, Lyft, taxis, transit, rental cars, walking directions, or a pickup route using the active destination.",
                TravelUi.LAVENDER,
                RideLauncherActivity.class));

        root.addView(TravelUi.section(this, "On-the-ground trip"));
        root.addView(feature(
                "🍜", "Food, events and experiences",
                "Find inexpensive food, free activities, museums, history, quiet places, accessible attractions, filming locations, and current events.",
                TravelUi.MINT,
                LocalExperienceActivity.class));
        root.addView(feature(
                "🚗", "Road-trip companion",
                "Plan fuel or charging stops, rest breaks, roadside attractions, scenic places, meals, overnight hotels, and route alternatives.",
                TravelUi.PEACH,
                RoadTripActivity.class));
        root.addView(feature(
                "🎟️", "Event trip center",
                "Review monitored conventions and events, official dates, venue updates, nearby food, transportation, and shared-person participation.",
                TravelUi.SKY,
                EventTripCenterActivity.class));

        root.addView(TravelUi.section(this, "Personalization and care"));
        root.addView(feature(
                "🎁", "Loyalty wallet",
                "Keep airline, hotel, rail, car-rental, and rewards program identifiers together without storing passwords. Sarah can consider them when comparing value.",
                TravelUi.LAVENDER,
                LoyaltyWalletActivity.class));
        root.addView(feature(
                "♿", "Accessibility, pace and greener choices",
                "Save walking limits, step-free needs, sensory preferences, food needs, pace, and sustainability priorities for the active profile.",
                TravelUi.MINT,
                TravelerNeedsActivity.class));
        root.addView(feature(
                "🛎️", "Hotel stay assistant",
                "Prepare late-arrival, quiet-room, accessibility, allergy, housekeeping, maintenance, and checkout requests; contact the hotel without sharing account passwords.",
                TravelUi.PEACH,
                StayAssistantActivity.class));

        root.addView(TravelUi.section(this, "Hackathon hospitality track"));
        root.addView(feature(
                "🏢", "Hotel operations demo",
                "Show how Sarah can turn guest requests into a front-desk, housekeeping, maintenance, and guest-experience task board while keeping unverified requests separate from completed work.",
                TravelUi.SKY,
                HospitalityOpsActivity.class));
    }

    private LinearLayout feature(
            String icon,
            String title,
            String description,
            int color,
            Class<?> activity) {
        LinearLayout card = TravelUi.card(this, color);
        card.addView(TravelUi.cardTitle(this, icon, title));
        card.addView(TravelUi.body(this, description));
        card.addView(TravelUi.outlineButton(this, "Open " + title, v -> TravelUi.start(this, activity)));
        return card;
    }
}
