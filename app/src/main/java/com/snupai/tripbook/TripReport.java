package com.snupai.tripbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TripReport {
    final long startMillis;
    final long endMillis;
    final List<TripRecord> trips;
    final List<Total> categoryTotals;
    final List<Total> vehicleTotals;
    final String distanceUnit;
    final double totalDistance;
    final double totalAmount;

    private TripReport(long startMillis, long endMillis, List<TripRecord> trips,
                          List<Total> categoryTotals, List<Total> vehicleTotals,
                          String distanceUnit, double totalDistance, double totalAmount) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.trips = trips;
        this.categoryTotals = categoryTotals;
        this.vehicleTotals = vehicleTotals;
        this.distanceUnit = DistanceUnit.resolve(distanceUnit);
        this.totalDistance = totalDistance;
        this.totalAmount = totalAmount;
    }

    static TripReport build(long startMillis, long endMillis, List<TripRecord> trips,
                            Map<String, Double> rates) {
        return build(startMillis, endMillis, trips, rates, DistanceUnit.METRIC);
    }

    static TripReport build(long startMillis, long endMillis, List<TripRecord> trips,
                            Map<String, Double> rates, String distanceUnit) {
        LinkedHashMap<String, TotalBuilder> categoryBuilders = new LinkedHashMap<>();
        LinkedHashMap<String, TotalBuilder> vehicleBuilders = new LinkedHashMap<>();
        String resolvedUnit = DistanceUnit.resolve(distanceUnit);
        double totalDistance = 0;
        double totalAmount = 0;

        for (TripRecord trip : trips) {
            double distance = DistanceUnit.fromMeters(trip.distanceMeters, resolvedUnit);
            totalDistance += distance;
            double rate = rateFor(rates, trip.category);
            double amount = rate > 0 ? distance * rate : 0;
            totalAmount += amount;

            String category = TripCategory.normalize(trip.category);
            categoryBuilders.computeIfAbsent(category, TotalBuilder::new)
                    .add(distance, rate, amount);

            String vehicle = trip.vehicleName == null || trip.vehicleName.trim().isEmpty()
                    ? "No vehicle"
                    : trip.vehicleName.trim();
            vehicleBuilders.computeIfAbsent(vehicle, TotalBuilder::new)
                    .add(distance, 0, 0);
        }

        return new TripReport(
                startMillis,
                endMillis,
                new ArrayList<>(trips),
                totals(categoryBuilders),
                totals(vehicleBuilders),
                resolvedUnit,
                totalDistance,
                totalAmount);
    }

    static double rateFor(Map<String, Double> rates, String category) {
        if (rates == null) {
            return 0;
        }
        Double rate = rates.get(TripCategory.normalize(category));
        return rate == null ? 0 : Math.max(0, rate);
    }

    private static List<Total> totals(LinkedHashMap<String, TotalBuilder> builders) {
        List<Total> totals = new ArrayList<>();
        for (TotalBuilder builder : builders.values()) {
            totals.add(builder.build());
        }
        return totals;
    }

    static final class Total {
        final String label;
        final int tripCount;
        final double distance;
        final double rate;
        final double amount;

        private Total(String label, int tripCount, double distance, double rate, double amount) {
            this.label = label;
            this.tripCount = tripCount;
            this.distance = distance;
            this.rate = rate;
            this.amount = amount;
        }
    }

    private static final class TotalBuilder {
        private final String label;
        private int tripCount;
        private double distance;
        private double rate;
        private double amount;

        private TotalBuilder(String label) {
            this.label = label;
        }

        private void add(double nextDistance, double nextRate, double nextAmount) {
            tripCount++;
            distance += nextDistance;
            if (nextRate > 0) {
                rate = nextRate;
            }
            amount += nextAmount;
        }

        private Total build() {
            return new Total(label, tripCount, distance, rate, amount);
        }
    }
}
