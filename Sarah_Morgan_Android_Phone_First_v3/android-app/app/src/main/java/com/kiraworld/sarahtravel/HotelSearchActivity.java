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
    private List<Stay22DirectClient.StayOffer> stay22Offers = new ArrayList<>();
    private String stay22Status = "";
    private long stay22NextAllowedAtMillis = 0;
    private boolean stay22SearchInFlight = false;

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
                        + " • " + state.adults + " adult traveler" + (state.adults == 1 ? "" : "s")
                        + " • " + state.rooms + " room" + (state.rooms == 1 ? "" : "s")));

        LinearLayout criteria = TravelUi.card(this, TravelUi.SKY);
        criteria.addView(TravelUi.cardTitle(this, "🔎", "Search details"));
        criteria.addView(TravelUi.body(this,
                "Sarah can carry the active trip into hotel searches. You can change dates, adult travelers, or rooms here without changing a confirmed booking."));
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

        LinearLayout stay22Demo = TravelUi.card(this, TravelUi.SKY);
        stay22Demo.addView(TravelUi.cardTitle(this, "S22", "Stay22 keyless demo"));
        String stay22Explanation = "Sends this destination, adult-traveler and room counts, and, when complete, both dates to "
                + "Stay22 only after you tap Search. Demo mode needs no API key and is limited to "
                + "5 requests per minute per network. Results are temporary and are not saved. "
                + "A result or provider link is not a booking.";
        if (!stay22Status.isEmpty()) stay22Explanation += "\n\n" + stay22Status;
        stay22Demo.addView(TravelUi.body(this, stay22Explanation));
        stay22Demo.addView(TravelUi.primaryButton(
                this,
                "Search Stay22 demo",
                v -> loadStay22DemoOffers()));
        root.addView(stay22Demo);
        for (Stay22DirectClient.StayOffer offer : stay22Offers) {
            root.addView(stay22OfferCard(offer));
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
                        ? "A team travel-commerce backend is configured. Provider credentials stay off the phone. The separately labeled Stay22 keyless demo remains an optional traveler-initiated comparison."
                        : "Provider links and the traveler-initiated Stay22 keyless demo work without embedding a provider secret. Dated Stay22 results may include a temporary supplier total; undated discovery never claims price or availability. Always verify the final provider checkout before paying."));
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

    private LinearLayout stay22OfferCard(Stay22DirectClient.StayOffer offer) {
        LinearLayout card = TravelUi.card(this, TravelUi.CREAM);
        card.addView(TravelUi.cardTitle(this, "S22", offer.title));
        String priceTruth;
        if (offer.hasQuotedTotal) {
            priceTruth = offer.currency + " " + String.format("%.2f", offer.quotedTotal)
                    + " full-stay supplier quote returned for the requested dates. "
                    + "Recheck price, availability, room, fees, taxes and terms at the provider.";
        } else if (offer.datedSearch) {
            priceTruth = "No supplier total was attached to this result. Price and availability remain unknown.";
        } else {
            priceTruth = "Static discovery result only. No price or availability was requested or confirmed.";
        }
        String detail = "Stay22 keyless demo"
                + (offer.type.isEmpty() ? "" : "\nType: " + offer.type)
                + "\nSelected source: " + offer.provider
                + "\n" + priceTruth
                + (offer.checkedAtUtc.isEmpty() ? "" : "\nChecked: " + offer.checkedAtUtc);
        card.addView(TravelUi.body(this, detail));
        if (!offer.reviewUrl.isEmpty()) {
            card.addView(TravelUi.primaryButton(
                    this,
                    "Review at provider (not booked)",
                    v -> TravelUi.open(this, offer.reviewUrl)));
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

    private void loadStay22DemoOffers() {
        try {
            Stay22SearchPolicy.prepare(
                    state.destination, state.checkIn, state.checkOut, state.adults, state.rooms);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
            return;
        }

        if (stay22SearchInFlight) {
            Toast.makeText(
                    this,
                    "The current Stay22 demo search is still running.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        long now = System.currentTimeMillis();
        if (now < stay22NextAllowedAtMillis) {
            long seconds = Math.max(1, (stay22NextAllowedAtMillis - now + 999) / 1000);
            Toast.makeText(
                    this,
                    "Stay22 demo is cooling down. Try again in about " + seconds + " seconds.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        stay22SearchInFlight = true;
        stay22NextAllowedAtMillis = now
                + Stay22SearchPolicy.MIN_DEMO_REQUEST_INTERVAL_SECONDS * 1000L;
        stay22Status = "Searching one small first page in Stay22 keyless demo mode...";
        stay22Offers.clear();
        render();
        final String destination = state.destination;
        final String checkIn = state.checkIn;
        final String checkOut = state.checkOut;
        final int adults = state.adults;
        final int rooms = state.rooms;
        executor.submit(() -> {
            try {
                Stay22DirectClient.SearchResult result = Stay22DirectClient.search(
                        destination, checkIn, checkOut, adults, rooms);
                runOnUiThread(() -> {
                    stay22SearchInFlight = false;
                    if (!sameStay22Search(destination, checkIn, checkOut, adults, rooms)) {
                        stay22Offers.clear();
                        stay22Status = "An earlier Stay22 result was not shown because the search details changed.";
                        render();
                        return;
                    }
                    stay22Offers = new ArrayList<>(result.offers);
                    stay22Status = result.offers.isEmpty()
                            ? "Stay22 returned no demo listings. This does not prove that no accommodation is available."
                            : "Stay22 returned " + result.offers.size() + " temporary demo listing"
                                    + (result.offers.size() == 1 ? "" : "s")
                                    + (result.datedSearch
                                            ? " for the requested date window. Verify every quote before paying."
                                            : ". Dates were not supplied, so no price or availability is claimed.")
                                    + (result.rateLimitRemaining >= 0
                                            ? " Demo requests remaining in this window: " + result.rateLimitRemaining + "."
                                            : "");
                    render();
                });
            } catch (Stay22DirectClient.RateLimitException error) {
                runOnUiThread(() -> {
                    stay22SearchInFlight = false;
                    stay22Offers.clear();
                    stay22NextAllowedAtMillis = System.currentTimeMillis()
                            + error.retryAfterSeconds * 1000L;
                    stay22Status = "Stay22's keyless demo rate limit was reached. No automatic retry "
                            + "was sent. Try again in about " + error.retryAfterSeconds + " seconds.";
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    stay22SearchInFlight = false;
                    stay22Offers.clear();
                    stay22Status = "The Stay22 demo could not finish: " + safeMessage(error)
                            + ". No price, availability or booking claim was created.";
                    render();
                });
            }
        });
    }

    private boolean sameStay22Search(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms) {
        return state.destination.equals(destination)
                && state.checkIn.equals(checkIn)
                && state.checkOut.equals(checkOut)
                && state.adults == adults
                && state.rooms == rooms;
    }

    private void editDetails() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);

        EditText destination = field("Destination", state.destination, InputType.TYPE_CLASS_TEXT);
        EditText checkIn = field("Check-in (YYYY-MM-DD)", state.checkIn, InputType.TYPE_CLASS_DATETIME);
        EditText checkOut = field("Check-out (YYYY-MM-DD)", state.checkOut, InputType.TYPE_CLASS_DATETIME);
        EditText adults = field("Adult travelers", String.valueOf(state.adults), InputType.TYPE_CLASS_NUMBER);
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
                    stay22Offers.clear();
                    stay22Status = "";
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
