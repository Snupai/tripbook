package com.snupai.tripbook;

import java.util.Locale;

final class GeoMath {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double METERS_PER_MILE = 1609.344;

    private GeoMath() {
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lng2 - lng1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    static double metersToMiles(double meters) {
        return meters / METERS_PER_MILE;
    }

    static double metersToKilometers(double meters) {
        return meters / 1000.0;
    }

    static String milesLabel(double meters) {
        return String.format(Locale.US, "%.2f mi", metersToMiles(meters));
    }

    static String distanceLabel(double meters, String unit) {
        return DistanceUnit.distanceLabel(meters, unit);
    }

    static String coordinateLabel(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return "Unknown";
        }
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }
}
