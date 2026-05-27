package com.snupai.tripbook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaceRuleTest {
    @Test
    public void containsUsesConfiguredRadius() {
        PlaceRule place = new PlaceRule();
        place.latitude = 40.0;
        place.longitude = -74.0;
        place.radiusMeters = 200;

        assertTrue(place.contains(40.0005, -74.0005));
        assertFalse(place.contains(40.01, -74.01));
    }

    @Test
    public void containsRejectsMissingCoordinates() {
        PlaceRule place = new PlaceRule();
        place.radiusMeters = 200;

        assertFalse(place.contains(null, -74.0));
        assertFalse(place.contains(40.0, null));
    }
}
