package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GeoMathTest {
    @Test
    public void metersToMilesUsesStandardConversion() {
        assertEquals(1.0, GeoMath.metersToMiles(1609.344), 0.000001);
    }

    @Test
    public void metersToKilometersUsesStandardConversion() {
        assertEquals(1.0, GeoMath.metersToKilometers(1000), 0.000001);
    }

    @Test
    public void distanceLabelUsesRequestedUnits() {
        assertEquals("1.00 km", GeoMath.distanceLabel(1000, DistanceUnit.METRIC));
        assertEquals("1.00 mi", GeoMath.distanceLabel(1609.344, DistanceUnit.IMPERIAL));
    }

    @Test
    public void distanceBetweenSamePointIsZero() {
        assertEquals(0.0, GeoMath.distanceMeters(40.0, -74.0, 40.0, -74.0), 0.001);
    }

    @Test
    public void coordinateLabelHandlesMissingValues() {
        assertEquals("Unknown", GeoMath.coordinateLabel(null, -74.0));
        assertEquals("40.00000, -74.00000", GeoMath.coordinateLabel(40.0, -74.0));
    }
}
