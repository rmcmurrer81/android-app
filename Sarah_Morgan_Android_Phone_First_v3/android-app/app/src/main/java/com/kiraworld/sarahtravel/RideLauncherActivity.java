package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/** One-tap ride and local-ground-transport handoff. */
public final class RideLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TravelContextSnapshot trip = TravelContextSnapshot.load(this);
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Local ride",
                trip.hasDestination() ? trip.destination : "Choose a destination",
                "Sarah passes the destination to another service when possible. The ride is not requested until the person confirms it in that service."));

        root.addView(rideCard(
                "🚘", "Open Uber",
                "Open Uber or its mobile web experience with the active destination. Review pickup, drop-off, fare, accessibility and safety details before confirming.",
                TravelUi.PEACH,
                ExternalTravelLinks.uber(trip)));
        root.addView(rideCard(
                "🚙", "Open Lyft",
                "Open Lyft for the active destination. Confirm pickup and drop-off inside Lyft because address-prefill behavior can vary by device and region.",
                TravelUi.LAVENDER,
                ExternalTravelLinks.lyft(trip)));
        root.addView(rideCard(
                "🚕", "Find a local taxi",
                "Show local taxi services near the destination. Verify licensing, current availability and the final quoted price.",
                TravelUi.SKY,
                ExternalTravelLinks.localTaxi(trip)));
        root.addView(rideCard(
                "🚇", "Use public transit instead",
                "Open current transit directions from the saved origin. Check service changes, walking distance, payment and accessibility.",
                TravelUi.MINT,
                ExternalTravelLinks.transitRoute(trip)));
        root.addView(rideCard(
                "🚗", "Rent a car",
                "Compare rental locations, taxes, airport fees, fuel or charging, insurance choices, deposits, tolls and parking before booking.",
                TravelUi.PEACH,
                ExternalTravelLinks.carRental(trip)));
        root.addView(rideCard(
                "🚶", "Walk from nearby",
                "Open a walking route only when it fits the active person's mobility, pace, weather and safety needs.",
                TravelUi.MINT,
                ExternalTravelLinks.walkRoute(trip)));

        LinearLayout safety = TravelUi.card(this, TravelUi.CREAM);
        safety.addView(TravelUi.cardTitle(this, "🛡️", "Ride handoff rule"));
        safety.addView(TravelUi.body(this,
                "Sarah can prepare and open the right app, but she must not claim that a ride was requested, paid for, accepted, or completed unless the external service supplies a verified result."));
        root.addView(safety);
    }

    private LinearLayout rideCard(String icon, String title, String description, int color, String url) {
        LinearLayout card = TravelUi.card(this, color);
        card.addView(TravelUi.cardTitle(this, icon, title));
        card.addView(TravelUi.body(this, description));
        card.addView(TravelUi.primaryButton(this, title, v -> TravelUi.open(this, url)));
        return card;
    }
}
