package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/** Door-to-door air, rail, bus, transit, driving, cycling, and walking options. */
public final class TransportOptionsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        TravelContextSnapshot trip = TravelContextSnapshot.load(this);
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Get there",
                trip.hasDestination() ? trip.origin + " → " + trip.destination : "Choose a route",
                trip.dateLabel()));

        LinearLayout compare = TravelUi.card(this, TravelUi.SKY);
        compare.addView(TravelUi.cardTitle(this, "⚖️", "Compare the complete journey"));
        compare.addView(TravelUi.body(this,
                "Sarah should compare total price, travel time, transfers, station or airport access, baggage, parking, reliability, accessibility, weather exposure, and the final local connection—not only the headline fare."));
        root.addView(compare);

        root.addView(modeCard("✈️", "Flights",
                "Search current flight options and then verify baggage, seat selection, airport transfer, cancellation rules, and the airline's own checkout total.",
                TravelUi.PEACH,
                ExternalTravelLinks.flights(trip)));
        root.addView(modeCard("🚆", "Amtrak and rail",
                "Open Amtrak and current rail searches. For long trips, compare coach, roomette or sleeper, transfers, meals, scenery, accessibility and station hours.",
                TravelUi.MINT,
                ExternalTravelLinks.amtrak(trip)));
        root.addView(modeCard("🚌", "Intercity bus and mixed routes",
                "Search current bus and rail combinations. Confirm the operator, stop location, baggage, transfer buffer, accessibility and late-arrival plan.",
                TravelUi.LAVENDER,
                ExternalTravelLinks.busRailSearch(trip)));
        root.addView(modeCard("🚇", "Local transit",
                "Open current public-transit directions. Recheck elevator outages, service changes, fare payment, walking distance and a backup route before leaving.",
                TravelUi.SKY,
                ExternalTravelLinks.transitRoute(trip)));
        root.addView(modeCard("🚗", "Drive",
                "Open the road route, then use Sarah's road-trip tools for fuel or charging, breaks, weather, parking, hotels and interesting stops.",
                TravelUi.PEACH,
                ExternalTravelLinks.driveRoute(trip)));
        root.addView(modeCard("🚲", "Bike or walk",
                "Use only where suitable for the active person's mobility, pace, weather and safety needs. Verify crossings, construction, steep grades and lighting.",
                TravelUi.MINT,
                ExternalTravelLinks.bikeRoute(trip)));

        LinearLayout needs = TravelUi.card(this, TravelUi.LAVENDER);
        needs.addView(TravelUi.cardTitle(this, "♿", "Use the active travel profile"));
        String summary = TravelerNeedsStore.summary(this, trip.personId);
        needs.addView(TravelUi.body(this,
                summary.isEmpty()
                        ? "No accessibility, pace or sustainability preferences are saved for " + trip.personName + "."
                        : summary));
        needs.addView(TravelUi.primaryButton(this, "Review accessibility and green preferences",
                v -> TravelUi.start(this, TravelerNeedsActivity.class)));
        root.addView(needs);

        LinearLayout backend = TravelUi.card(this, TravelUi.CREAM);
        backend.addView(TravelUi.cardTitle(this, "🔌", "Live transportation status"));
        backend.addView(TravelUi.body(this,
                TravelCommerceConfig.isConfigured()
                        ? "Current transportation offers can be compared in one consistent view."
                        : "External route links work now. In-app current schedules and normalized prices require a verified live connection."));
        root.addView(backend);
    }

    private LinearLayout modeCard(
            String icon,
            String title,
            String description,
            int color,
            String url) {
        LinearLayout card = TravelUi.card(this, color);
        card.addView(TravelUi.cardTitle(this, icon, title));
        card.addView(TravelUi.body(this, description));
        card.addView(TravelUi.outlineButton(this, "Open current " + title.toLowerCase() + " source",
                v -> TravelUi.open(this, url)));
        return card;
    }
}
