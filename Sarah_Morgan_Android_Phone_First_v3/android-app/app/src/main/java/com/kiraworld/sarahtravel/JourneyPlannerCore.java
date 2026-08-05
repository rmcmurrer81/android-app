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
            return "I’ll treat this as a local-transit trip from " + intent.origin + " to "
                    + intent.eventName + " in " + intent.destination + ", not as a comparison with an old vacation idea. "
                    + "I’ll check the event venue, the realistic transit chain, walking distance, accessibility, service changes, and a backup route when current sources are connected. The Map and Route buttons can show the area now.";
        }

        if (intent.modes.contains(JourneyIntentParser.RAIL) && intent.crossCountry) {
            return "A cross-country train trip from " + intent.origin + " to " + intent.destination
                    + " is a multi-day rail journey, not a city comparison. I’ll compare current Amtrak route combinations, coach versus sleeper choices, transfer points, total travel time, station access, scenery, meals, and possible overnight stops. Current timetables and prices need official live data, but I can save the rail plan now and show maps, photos, and videos of the route.";
        }

        if (intent.modes.size() > 1) {
            return "I’ll compare more than flights for " + route + ": " + naturalModes(intent.modes)
                    + ". I’ll judge the complete trip—price, duration, transfers, baggage, station or airport access, accessibility, reliability, and local transportation at the destination—rather than picking the cheapest headline fare.";
        }

        String mode = intent.modes.get(0);
        if (JourneyIntentParser.RAIL.equals(mode)) {
            return "I’ll plan " + route + " by rail. I’ll check current Amtrak or regional-rail options, transfers, coach and sleeper choices where relevant, station access, total trip time, and the final local connection. I won’t substitute old Paris or New York suggestions for the route you actually named.";
        }
        if (JourneyIntentParser.TRANSIT.equals(mode)) {
            return "I’ll plan " + route + " by local transit. I’ll compare the practical train, subway, light-rail, bus, and walking pieces, including service changes, elevators, transfer time, and a backup route when current information is available.";
        }
        if (JourneyIntentParser.BUS.equals(mode)) {
            return "I’ll plan " + route + " by intercity bus and compare current operators, departure points, total time, luggage rules, transfer risk, and the local connection at each end.";
        }
        if (JourneyIntentParser.DRIVE.equals(mode)) {
            return "I’ll plan the drive from " + intent.origin + " to " + intent.destination
                    + " with route options, tolls, charging or fuel stops, weather, parking, rest stops, and an alternative if traffic or conditions change.";
        }
        if (JourneyIntentParser.FERRY.equals(mode)) {
            return "I’ll treat the ferry as part of the real journey from " + intent.origin + " to " + intent.destination
                    + " and check current terminals, schedules, boarding rules, weather sensitivity, and connections on both sides.";
        }
        if (JourneyIntentParser.AIR.equals(mode)) {
            return "I’ll plan the full air trip from " + intent.origin + " to " + intent.destination
                    + ", including nearby airports, bags, ground transportation, schedule quality, and total door-to-door time—not only the airfare.";
        }
        return "I’ll save the " + mode.replace('_', ' ') + " journey from " + intent.origin + " to "
                + intent.destination + " and use current route information when the connected travel service is available.";
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
