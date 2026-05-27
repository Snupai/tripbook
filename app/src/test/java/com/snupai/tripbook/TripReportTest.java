package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class TripReportTest {
    @Test
    public void reportCalculatesCategoryRateTotals() {
        TripRecord trip = trip(TripCategory.BUSINESS, 1000);

        TripReport report = TripReport.build(
                0,
                Long.MAX_VALUE,
                Collections.singletonList(trip),
                Collections.singletonMap(TripCategory.BUSINESS, 0.50));

        assertEquals(1.0, report.totalDistance, 0.0001);
        assertEquals(0.50, report.totalAmount, 0.0001);
        assertEquals(1, report.categoryTotals.get(0).tripCount);
        assertEquals(0.50, report.categoryTotals.get(0).amount, 0.0001);
    }

    @Test
    public void csvIncludesReviewVehicleOdometerAndSource() {
        TripRecord trip = trip(TripCategory.BUSINESS, 1609.344);
        trip.reviewed = true;
        trip.vehicleName = "Truck";
        trip.startOdometer = 100.0;
        trip.endOdometer = 101.0;
        trip.autoStartSource = AutoStartSource.MOTION;
        TripReport report = TripReport.build(
                0,
                Long.MAX_VALUE,
                Collections.singletonList(trip),
                Collections.singletonMap(TripCategory.BUSINESS, 0.50));

        String csv = TripReportCsv.render(report);

        assertTrue(csv.contains("\"Truck\""));
        assertTrue(csv.contains("yes"));
        assertTrue(csv.contains("100.00,101.00"));
        assertTrue(csv.contains("\"Motion\""));
        assertTrue(csv.contains("0.50"));
    }

    @Test
    public void metricReportUsesKilometersForDistanceAndRates() {
        TripRecord trip = trip(TripCategory.BUSINESS, 1000);

        TripReport report = TripReport.build(
                0,
                Long.MAX_VALUE,
                Collections.singletonList(trip),
                Collections.singletonMap(TripCategory.BUSINESS, 2.00),
                DistanceUnit.METRIC);
        String csv = TripReportCsv.render(report);

        assertEquals(1.0, report.totalDistance, 0.0001);
        assertEquals(2.0, report.totalAmount, 0.0001);
        assertTrue(csv.contains("Distance Kilometers"));
        assertTrue(csv.contains("1.00,2.00,2.00"));
    }

    private TripRecord trip(String category, double meters) {
        TripRecord trip = new TripRecord();
        trip.id = 1;
        trip.startTime = 1_000L;
        trip.endTime = 2_000L;
        trip.category = category;
        trip.notes = "";
        trip.distanceMeters = meters;
        trip.autoStartSource = AutoStartSource.MANUAL;
        return trip;
    }
}
