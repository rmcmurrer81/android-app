package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Route tools, fuel estimate, break pacing, chargers and roadside discovery. */
public final class RoadTripActivity extends Activity {
    private TravelContextSnapshot trip;
    private RoadTripProfileStore.Vehicle vehicle;
    private String origin;
    private String destination;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        load();
        render();
    }

    private void load() {
        trip = TravelContextSnapshot.load(this);
        vehicle = RoadTripProfileStore.load(this, trip.personId);
        SharedPreferences p = getSharedPreferences("sarah_road_trip", MODE_PRIVATE);
        String prefix = "p" + trip.personId + "_";
        origin = p.getString(prefix + "origin", trip.origin);
        destination = p.getString(prefix + "destination", trip.destination);
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        String route = route();
        root.addView(TravelUi.hero(
                this,
                "Road-trip companion",
                route.isEmpty() ? "Choose a route" : route,
                vehicle.summary()));

        LinearLayout setup = TravelUi.card(this, TravelUi.SKY);
        setup.addView(TravelUi.cardTitle(this, "🚙", "Route and vehicle"));
        setup.addView(TravelUi.body(this,
                "Save a simple vehicle profile for estimates and break pacing. Sarah never needs your vehicle account, VIN, insurance login, or payment credentials."));
        setup.addView(TravelUi.primaryButton(this, "Edit route and vehicle", v -> editSetup()));
        root.addView(setup);

        LinearLayout routeCard = TravelUi.card(this, TravelUi.MINT);
        routeCard.addView(TravelUi.cardTitle(this, "🗺️", "Drive and navigation"));
        routeCard.addView(TravelUi.body(this,
                "Open current driving directions and review traffic, construction, weather, tolls, parking and the active person's needs before departure."));
        routeCard.addView(TravelUi.outlineButton(this, "Open driving route",
                v -> open(ExternalTravelLinks.directions(origin, destination, "driving"))));
        root.addView(routeCard);

        LinearLayout fuel = TravelUi.card(this, TravelUi.PEACH);
        fuel.addView(TravelUi.cardTitle(this, "⛽", "Fuel and charging"));
        fuel.addView(TravelUi.body(this,
                "Station-level prices change quickly. Use live sources before choosing a stop, and compare the detour, safety, open hours and remaining range—not only cents per gallon."));
        fuel.addView(TravelUi.outlineButton(this, "Find gas stations along the route",
                v -> open(ExternalTravelLinks.gasStations(route))));
        fuel.addView(TravelUi.outlineButton(this, "Search current gas prices",
                v -> open(ExternalTravelLinks.gasPriceSearch(route))));
        fuel.addView(TravelUi.outlineButton(this, "Find EV chargers",
                v -> open(ExternalTravelLinks.evCharging(route))));
        fuel.addView(TravelUi.outlineButton(this, "Open PlugShare",
                v -> open(ExternalTravelLinks.plugShare(destination))));
        fuel.addView(TravelUi.primaryButton(this, "Estimate fuel cost", v -> estimateFuel()));
        root.addView(fuel);

        LinearLayout breaks = TravelUi.card(this, TravelUi.LAVENDER);
        breaks.addView(TravelUi.cardTitle(this, "☕", "Breaks and safer pacing"));
        breaks.addView(TravelUi.body(this,
                "The saved preference is about every " + vehicle.stopEveryMiles
                        + " miles. Sarah should also react to fatigue, weather, traffic, medication, accessibility, children, pets and the driver's comfort rather than treating that as a rigid rule."));
        breaks.addView(TravelUi.outlineButton(this, "Find rest areas",
                v -> open(ExternalTravelLinks.restStops(route))));
        breaks.addView(TravelUi.outlineButton(this, "Find food along the route",
                v -> open(ExternalTravelLinks.roadFood(route))));
        root.addView(breaks);

        LinearLayout discover = TravelUi.card(this, TravelUi.MINT);
        discover.addView(TravelUi.cardTitle(this, "📍", "Make the drive part of the trip"));
        discover.addView(TravelUi.body(this,
                "Sarah can mix useful stops with the active person's interests, available time and budget instead of recommending every attraction along the highway."));
        discover.addView(TravelUi.outlineButton(this, "Interesting roadside stops",
                v -> open(ExternalTravelLinks.roadsideAttractions(route))));
        discover.addView(TravelUi.outlineButton(this, "Scenic stops and viewpoints",
                v -> open(ExternalTravelLinks.scenicStops(route))));
        discover.addView(TravelUi.outlineButton(this, "Overnight hotels along the route",
                v -> open(ExternalTravelLinks.roadsideHotels(route))));
        root.addView(discover);

        LinearLayout live = TravelUi.card(this, TravelUi.CREAM);
        live.addView(TravelUi.cardTitle(this, "🔌", "Automatic road-trip optimization"));
        live.addView(TravelUi.body(this,
                TravelCommerceConfig.isConfigured()
                        ? "Live route optimization can use the route, vehicle, stop pacing, and accessibility choices."
                        : "The route tools work now. Automatic cheapest-safe fuel stops require verified live fuel prices and routing; Sarah will not scrape private apps or invent station prices."));
        root.addView(live);
    }

    private void editSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);

        EditText originField = field("Origin", origin, InputType.TYPE_CLASS_TEXT);
        EditText destinationField = field("Destination", destination, InputType.TYPE_CLASS_TEXT);
        EditText type = field("Vehicle type: gas, hybrid, diesel, EV...", vehicle.type, InputType.TYPE_CLASS_TEXT);
        EditText mpg = field("MPG (0 for EV)", value(vehicle.mpg), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText tank = field("Tank gallons", value(vehicle.tankGallons), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText evRange = field("Usable EV range in miles", value(vehicle.evRangeMiles), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText stopMiles = field("Preferred break interval in miles", String.valueOf(vehicle.stopEveryMiles), InputType.TYPE_CLASS_NUMBER);
        EditText notes = field("Vehicle or stop notes", vehicle.notes, InputType.TYPE_CLASS_TEXT);
        box.addView(originField);
        box.addView(destinationField);
        box.addView(type);
        box.addView(mpg);
        box.addView(tank);
        box.addView(evRange);
        box.addView(stopMiles);
        box.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("Road-trip setup")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    origin = originField.getText().toString().trim();
                    destination = destinationField.getText().toString().trim();
                    vehicle = new RoadTripProfileStore.Vehicle(
                            type.getText().toString(),
                            number(mpg.getText().toString()),
                            number(tank.getText().toString()),
                            number(evRange.getText().toString()),
                            (int) Math.max(50, number(stopMiles.getText().toString())),
                            notes.getText().toString());
                    RoadTripProfileStore.save(this, trip.personId, vehicle);
                    String prefix = "p" + trip.personId + "_";
                    getSharedPreferences("sarah_road_trip", MODE_PRIVATE).edit()
                            .putString(prefix + "origin", origin)
                            .putString(prefix + "destination", destination)
                            .apply();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void estimateFuel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);
        EditText miles = field("Estimated route miles", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText price = field("Average fuel price per gallon", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(miles);
        box.addView(price);
        new AlertDialog.Builder(this)
                .setTitle("Fuel estimate")
                .setMessage("This is a planning estimate, not a live station quote.")
                .setView(box)
                .setPositiveButton("Calculate", (dialog, which) -> {
                    double distance = number(miles.getText().toString());
                    double fuelPrice = number(price.getText().toString());
                    if (distance <= 0 || fuelPrice <= 0 || vehicle.mpg <= 0) {
                        Toast.makeText(this, "Enter miles, price and a non-zero MPG.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    double gallons = distance / vehicle.mpg;
                    double cost = gallons * fuelPrice;
                    int breaks = (int) Math.max(0, Math.floor(distance / vehicle.stopEveryMiles));
                    new AlertDialog.Builder(this)
                            .setTitle("Planning estimate")
                            .setMessage(String.format(
                                    "About %.1f gallons and $%.2f at the values entered. A %,.0f-mile route suggests roughly %d planned break%s before adding traffic, detours or overnight stops.",
                                    gallons, cost, distance, breaks, breaks == 1 ? "" : "s"))
                            .setPositiveButton("OK", null)
                            .show();
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
        if (destination == null || destination.trim().isEmpty()) {
            Toast.makeText(this, "Set a road-trip destination first.", Toast.LENGTH_LONG).show();
            return;
        }
        TravelUi.open(this, url);
    }

    private String route() {
        String a = origin == null ? "" : origin.trim();
        String b = destination == null ? "" : destination.trim();
        if (a.isEmpty() && b.isEmpty()) return "";
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " to " + b;
    }

    private static double number(String value) {
        try { return Double.parseDouble(value == null ? "" : value.trim()); }
        catch (Exception ignored) { return 0; }
    }

    private static String value(double value) {
        return value <= 0 ? "" : String.valueOf(value);
    }
}
