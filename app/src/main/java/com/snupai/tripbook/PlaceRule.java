package com.snupai.tripbook;

final class PlaceRule {
    long id;
    String name;
    double latitude;
    double longitude;
    double radiusMeters;
    String category;
    boolean matchStart;
    boolean matchEnd;

    boolean contains(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return false;
        }
        return GeoMath.distanceMeters(latitude, longitude, lat, lng) <= radiusMeters;
    }
}
