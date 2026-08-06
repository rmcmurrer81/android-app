package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hotel discovery, normalized live offers, loyalty context, and stay-assistant entry. */
public final class HotelSearchActivity extends Activity {
    private TravelContextSnapshot baseTrip;
    private HotelSearchState state;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<TravelCommerceClient.Offer> liveOffers = new ArrayList<>();
    private String liveStatus = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseTrip = TravelContextSnapshot.load(this);
        state = HotelSearchState.load(this, baseTrip);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        baseTrip = TravelContextSnapshot.load(this);
        state = HotelSearchState.load(this, baseTrip);
        render();
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        TravelContextSnapshot trip = state.applyTo(baseTrip);
        root.addView(TravelUi.hero(
                this,
                "Stay finder",
                trip.destination.isEmpty() ? "Choose a destination" : trip.destination,
                state.checkIn.isEmpty() ? "Dates not set" : state.checkIn + " to " + state.checkOut
                        + " • " + state.adults + " traveler" + (state.adults == 1 ? "" : "s")
                        + " • " + state.rooms + " room" + (state.rooms == 1 ? "" : "s")));

        LinearLayout criteria = TravelUi.card(this, TravelUi.SKY);
        criteria.addView(TravelUi.cardTitle(this, "🔎", "Search details"));
        criteria.addView(TravelUi.body(this,
                "Sarah can carry the active trip into hotel searches. You can change dates, travelers, or rooms here without changing a confirmed booking."));
        criteria.addView(TravelUi.primaryButton(this, "Edit hotel search details", v -> editDetails()));
        root.addView(criteria);

        if (TravelCommerceConfig.isConfigured()) {
            LinearLayout live = TravelUi.card(this, TravelUi.MINT);
            live.addView(TravelUi.cardTitle(this, "⚡", "Live in-app hotel comparison"));
            live.addView(TravelUi.body(this,
                    liveStatus.isEmpty()
                            ? "Ask the team travel backend for normalized current offers using the active dates, rooms, loyalty programs and accessibility preferences."
                            : liveStatus));
            live.addView(TravelUi.primaryButton(this, "Find live hotel prices", v -> loadLiveOffers()));
            root.addView(live);
            for (TravelCommerceClient.Offer offer : liveOffers) root.addView(offerCard(offer));
        }

        LinearLayout compare = TravelUi.card(this, TravelUi.PEACH);
        compare.addView(TravelUi.cardTitle(this, "💵", "Compare the complete price"));
        compare.addView(TravelUi.body(this,
                "Compare the total after mandatory fees, taxes, resort or destination fees, cancellation rules, payment timing, breakfast, parking, accessibility, room type, and loyalty value. A headline nightly rate is not enough."));
        compare.addView(TravelUi.outlineButton(this, "Google hotel results",
                v -> open(ExternalTravelLinks.googleHotels(trip))));
        compare.addView(TravelUi.outlineButton(this, "Expedia",
                v -> open(ExternalTravelLinks.expediaHotels(trip, state.rooms))));
        compare.addView(TravelUi.outlineButton(this, "Booking.com",
                v -> open(ExternalTravelLinks.bookingHotels(trip, state.rooms))));
        compare.addView(TravelUi.outlineButton(this, "Priceline",
                v -> open(ExternalTravelLinks.pricelineHotels(trip))));
        compare.addView(TravelUi.outlineButton(this, "Hotels.com",
                v -> open(ExternalTravelLinks.hotelsDotCom(trip))));
        compare.addView(TravelUi.outlineButton(this, "Rove rewards hotel search",
                v -> open(ExternalTravelLinks.roveHotels(trip))));
        root.addView(compare);

        LinearLayout direct = TravelUi.card(this, TravelUi.MINT);
        direct.addView(TravelUi.cardTitle(this, "🏷️", "Check the hotel directly"));
        direct.addView(TravelUi.body(this,
                "After finding a property, compare the hotel's own website. Direct rates may include different cancellation terms, member discounts, breakfast, parking, upgrades, or support. Verify the same room and dates before comparing."));
        direct.addView(TravelUi.outlineButton(this, "Search official hotel websites",
                v -> open(ExternalTravelLinks.directHotelSiteSearch(trip))));
        direct.addView(TravelUi.outlineButton(this, "Show hotels on a map",
                v -> open(ExternalTravelLinks.hotelMap(trip))));
        root.addView(direct);

        LinearLayout loyalty = TravelUi.card(this, TravelUi.LAVENDER);
        loyalty.addView(TravelUi.cardTitle(this, "🎁", "Loyalty and rewards"));
        String programs = LoyaltyVaultStore.summary(this, trip.personId);
        loyalty.addView(TravelUi.body(this,
                programs.isEmpty()
                        ? "No loyalty programs are saved for " + trip.personName + ". Add airline, hotel, rail, car-rental, credit-card, or general travel rewards programs without storing passwords."
                        : "Saved for " + trip.personName + ": " + programs
                                + ". Sarah should compare points, status benefits, direct-booking eligibility, and total cash cost—not points alone."));
        loyalty.addView(TravelUi.primaryButton(this, "Open loyalty wallet",
                v -> TravelUi.start(this, LoyaltyWalletActivity.class)));
        root.addView(loyalty);

        LinearLayout stay = TravelUi.card(this, TravelUi.SKY);
        stay.addView(TravelUi.cardTitle(this, "🛎️", "After choosing a hotel"));
        stay.addView(TravelUi.body(this,
                "Share the booking link or screenshot with Sarah, review extracted details, then prepare arrival, room, accessibility, housekeeping, maintenance, and checkout requests."));
        stay.addView(TravelUi.primaryButton(this, "Open hotel stay assistant",
                v -> TravelUi.start(this, StayAssistantActivity.class)));
        root.addView(stay);

        LinearLayout integration = TravelUi.card(this, TravelUi.CREAM);
        integration.addView(TravelUi.cardTitle(this, "🔌", "Live price integration status"));
        integration.addView(TravelUi.body(this,
                TravelCommerceConfig.isConfigured()
                        ? "A team travel-commerce backend is configured. Provider credentials stay off the phone, and results must include source time, total price and booking URL."
                        : "Provider links work now. In-app live hotel prices require a team backend connected to approved inventory such as Expedia Rapid, Booking.com Demand API, a sponsor tool, or another lawful provider. The installer does not enter those credentials."));
        root.addView(integration);
    }

    private LinearLayout offerCard(TravelCommerceClient.Offer offer) {
        LinearLayout card = TravelUi.card(this, TravelUi.SKY);
        card.addView(TravelUi.cardTitle(this, "🏨", offer.title));
        String price = offer.totalPrice > 0
                ? offer.currency + " " + String.format("%.2f", offer.totalPrice) + " total"
                : "Price requires confirmation";
        String detail = "Provider: " + offer.provider
                + "\n" + price
                + (offer.cancellation.isEmpty() ? "" : "\nCancellation: " + offer.cancellation)
                + (offer.details.isEmpty() ? "" : "\n" + offer.details)
                + (offer.sourceTime.isEmpty() ? "" : "\nChecked: " + offer.sourceTime);
        card.addView(TravelUi.body(this, detail));
        if (!offer.bookingUrl.isEmpty()) {
            card.addView(TravelUi.primaryButton(this, "Review this offer at the provider",
                    v -> TravelUi.open(this, offer.bookingUrl)));
        }
        return card;
    }

    private void loadLiveOffers() {
        if (state.destination.isEmpty() || state.checkIn.isEmpty() || state.checkOut.isEmpty()) {
            Toast.makeText(this, "Set the destination, check-in and check-out dates first.", Toast.LENGTH_LONG).show();
            return;
        }
        liveStatus = "Searching approved hotel sources…";
        liveOffers.clear();
        render();
        TravelContextSnapshot trip = state.applyTo(baseTrip);
        String loyalty = LoyaltyVaultStore.summary(this, trip.personId);
        String needs = TravelerNeedsStore.summary(this, trip.personId);
        executor.submit(() -> {
            try {
                List<TravelCommerceClient.Offer> offers = TravelCommerceClient.searchHotels(
                        trip, state, loyalty, needs);
                runOnUiThread(() -> {
                    liveOffers = offers;
                    liveStatus = offers.isEmpty()
                            ? "The backend returned no matching offers. Try different dates or use the provider links below."
                            : "Found " + offers.size() + " current offer" + (offers.size() == 1 ? "" : "s")
                                    + ". Verify the final provider checkout before paying.";
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    liveOffers.clear();
                    liveStatus = "The live comparison could not finish: " + safeMessage(error)
                            + ". Provider links remain available below.";
                    render();
                });
            }
        });
    }

    private void editDetails() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);

        EditText destination = field("Destination", state.destination, InputType.TYPE_CLASS_TEXT);
        EditText checkIn = field("Check-in (YYYY-MM-DD)", state.checkIn, InputType.TYPE_CLASS_DATETIME);
        EditText checkOut = field("Check-out (YYYY-MM-DD)", state.checkOut, InputType.TYPE_CLASS_DATETIME);
        EditText adults = field("Travelers", String.valueOf(state.adults), InputType.TYPE_CLASS_NUMBER);
        EditText rooms = field("Rooms", String.valueOf(state.rooms), InputType.TYPE_CLASS_NUMBER);
        box.addView(destination);
        box.addView(checkIn);
        box.addView(checkOut);
        box.addView(adults);
        box.addView(rooms);

        new AlertDialog.Builder(this)
                .setTitle("Hotel search details")
                .setMessage("These are search criteria, not a booking.")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String place = destination.getText().toString().trim();
                    if (place.isEmpty()) {
                        Toast.makeText(this, "Enter a destination.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    state = HotelSearchState.of(
                            place,
                            checkIn.getText().toString().trim(),
                            checkOut.getText().toString().trim(),
                            number(adults.getText().toString(), 1),
                            number(rooms.getText().toString(), 1));
                    state.save(this, baseTrip.personId);
                    liveOffers.clear();
                    liveStatus = "";
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setInputType(inputType);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private void open(String url) {
        if (state.destination.isEmpty()) {
            Toast.makeText(this, "Set a hotel destination first.", Toast.LENGTH_LONG).show();
            return;
        }
        TravelUi.open(this, url);
    }

    private static int number(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value.trim())); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safeMessage(Exception error) {
        String value = error == null ? "unknown error" : error.getMessage();
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
