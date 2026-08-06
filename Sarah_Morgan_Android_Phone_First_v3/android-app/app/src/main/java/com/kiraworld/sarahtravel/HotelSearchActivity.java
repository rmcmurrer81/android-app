package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Hotel discovery, comparison links, loyalty context, and stay-assistant entry. */
public final class HotelSearchActivity extends Activity {
    private TravelContextSnapshot baseTrip;
    private HotelSearchState state;

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

        LinearLayout live = TravelUi.card(this, TravelUi.CREAM);
        live.addView(TravelUi.cardTitle(this, "🔌", "Live price integration status"));
        live.addView(TravelUi.body(this,
                TravelCommerceConfig.isConfigured()
                        ? "A team travel-commerce backend is configured. Sarah can request normalized hotel offers while keeping provider credentials off the phone."
                        : "Provider links work now. In-app live hotel prices require a team backend connected to approved inventory such as Expedia Rapid, Booking.com Demand API, a sponsor tool, or another lawful provider. The installer does not enter those credentials."));
        root.addView(live);
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
}
