package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;

/** Produces useful local journey guidance without treating route endpoints as competing vacations. */
public final class JourneyPlannerCore {
    private JourneyPlannerCore() { }

    public static String answer(JourneyIntentParser.JourneyIntent intent) {
        if (intent == null || !intent.found()) return null;
        String route = intent.origin + " to " + intent.destination;

        if (intent.modes.contains(JourneyIntentParser.TRANSIT) && !intent.eventName.isEmpty()) {
            return "This is a local-transit trip from " + intent.origin + " to "
                    + intent.eventName + " in " + intent.destination + ". "
                    + "The event venue, realistic transit chain, walking distance, accessibility, service changes, and a backup route require verified current sources. The Map and Route buttons can show the area now.";
        }

        if (intent.modes.contains(JourneyIntentParser.RAIL) && intent.crossCountry) {
            return "A cross-country train trip from " + intent.origin + " to " + intent.destination
                    + " is a multi-day rail journey. Current Amtrak route combinations, coach versus sleeper choices, transfer points, total travel time, station access, scenery, meals, and possible overnight stops need official live data. A rail plan can be saved only through the explicit save action; maps, photos, and videos can still show the route.";
        }

        if (intent.modes.size() > 1) {
            return "Useful modes beyond flights for " + route + " are " + naturalModes(intent.modes)
                    + ". A complete comparison needs price, duration, transfers, baggage, station or airport access, accessibility, reliability, and local transportation at the destination—not only the cheapest headline fare.";
        }

        String mode = intent.modes.get(0);
        if (JourneyIntentParser.RAIL.equals(mode)) {
            return "For " + route + " by rail, current Amtrak or regional-rail options, transfers, coach and sleeper choices, station access, total trip time, and the final local connection require verified sources.";
        }
        if (JourneyIntentParser.TRANSIT.equals(mode)) {
            return "For " + route + " by local transit, compare the practical train, subway, light-rail, bus, and walking pieces. Service changes, elevators, transfer time, and a backup route require current information.";
        }
        if (JourneyIntentParser.BUS.equals(mode)) {
            return "For " + route + " by intercity bus, current operators, departure points, total time, luggage rules, transfer risk, and the local connection at each end require verified sources.";
        }
        if (JourneyIntentParser.DRIVE.equals(mode)) {
            return "A drive from " + intent.origin + " to " + intent.destination
                    + " with route options, tolls, charging or fuel stops, weather, parking, rest stops, and an alternative if traffic or conditions change.";
        }
        if (JourneyIntentParser.FERRY.equals(mode)) {
            return "The ferry is part of the journey from " + intent.origin + " to " + intent.destination
                    + ". Current terminals, schedules, boarding rules, weather sensitivity, and connections on both sides require verified sources.";
        }
        if (JourneyIntentParser.AIR.equals(mode)) {
            return "A full air trip from " + intent.origin + " to " + intent.destination
                    + ", including nearby airports, bags, ground transportation, schedule quality, and total door-to-door time—not only the airfare.";
        }
        return "The " + mode.replace('_', ' ') + " journey from " + intent.origin + " to "
                + intent.destination + " can be saved only through an explicit save action; current route information requires a connected source.";
    }

    private static String naturalModes(List<String> modes) {
        String[] labels = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) labels[i] = label(modes.get(i));
        if (labels.length == 1) return labels[0];
        if (labels.length == 2) return labels[0] + " and " + labels[1];
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) out.append(i == labels.length - 1 ? ", and " : ", ");
            out.append(labels[i]);
        }
        return out.toString();
    }

    private static String label(String mode) {
        if (JourneyIntentParser.AIR.equals(mode)) return "air travel";
        if (JourneyIntentParser.RAIL.equals(mode)) return "Amtrak or rail";
        if (JourneyIntentParser.TRANSIT.equals(mode)) return "local transit";
        if (JourneyIntentParser.BUS.equals(mode)) return "intercity bus";
        return mode.replace('_', ' ').toLowerCase(Locale.US);
    }
}
