package com.snupai.tripbook;

import java.util.Locale;

final class DistanceUnit {
    static final String AUTOMATIC = "automatic";
    static final String IMPERIAL = "imperial";
    static final String METRIC = "metric";

    private DistanceUnit() {
    }

    static String normalize(String unit) {
        if (IMPERIAL.equals(unit) || METRIC.equals(unit)) {
            return unit;
        }
        return AUTOMATIC;
    }

    static String resolve(String unit) {
        String normalized = normalize(unit);
        if (!AUTOMATIC.equals(normalized)) {
            return normalized;
        }
        return usesImperial(Locale.getDefault()) ? IMPERIAL : METRIC;
    }

    static String settingLabel(String unit) {
        String normalized = normalize(unit);
        if (IMPERIAL.equals(normalized)) {
            return "Imperial";
        }
        if (METRIC.equals(normalized)) {
            return "Metric";
        }
        return "Automatic (" + displayName(resolve(normalized)) + ")";
    }

    static String displayName(String unit) {
        return IMPERIAL.equals(resolve(unit)) ? "Imperial" : "Metric";
    }

    static String shortLabel(String unit) {
        return IMPERIAL.equals(resolve(unit)) ? "mi" : "km";
    }

    static String distanceName(String unit) {
        return IMPERIAL.equals(resolve(unit)) ? "Miles" : "Kilometers";
    }

    static double fromMeters(double meters, String unit) {
        if (IMPERIAL.equals(resolve(unit))) {
            return GeoMath.metersToMiles(meters);
        }
        return GeoMath.metersToKilometers(meters);
    }

    static String distanceLabel(double meters, String unit) {
        return String.format(Locale.US, "%.2f %s", fromMeters(meters, unit), shortLabel(unit));
    }

    private static boolean usesImperial(Locale locale) {
        String country = locale == null ? "" : locale.getCountry();
        return "US".equalsIgnoreCase(country)
                || "LR".equalsIgnoreCase(country)
                || "MM".equalsIgnoreCase(country);
    }
}
