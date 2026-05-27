package com.snupai.tripbook;

final class TripRecord {
    long id;
    long startTime;
    Long endTime;
    Double startLat;
    Double startLng;
    Double endLat;
    Double endLng;
    double distanceMeters;
    String category;
    String notes;
    boolean autoStarted;
    String autoStartSource;
    boolean active;
    boolean reviewed;
    Long vehicleId;
    String vehicleName;
    Double startOdometer;
    Double endOdometer;

    String title() {
        String end = endTime == null ? "Active" : TimeFormat.shortDateTime(endTime);
        return TimeFormat.shortDateTime(startTime) + " -> " + end;
    }

    String summary() {
        return summary(DistanceUnit.IMPERIAL);
    }

    String summary(String distanceUnit) {
        return GeoMath.distanceLabel(distanceMeters, distanceUnit) + " / " + category;
    }

    String reviewLabel() {
        return reviewed ? "Reviewed" : "Needs review";
    }
}
