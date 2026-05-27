package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.Location;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class TripRepositoryInstrumentedTest {
    private static final String DB_NAME = "tripbook.db";
    private static final String PREFS = "tripbook_settings";

    private Context context;
    private TripRepository repository;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB_NAME);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        repository = new TripRepository(context);
    }

    @After
    public void tearDown() {
        repository.close();
        context.deleteDatabase(DB_NAME);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void startTripReusesActiveTrip() {
        TripRecord first = repository.startTrip(false);
        TripRecord second = repository.startTrip(true);

        assertEquals(first.id, second.id);
        assertFalse(second.autoStarted);
    }

    @Test
    public void autoStopDoesNotFinishManualTrip() {
        repository.startTrip(false);

        TripRecord result = repository.finishActiveTrip(true);
        TripRecord active = repository.getActiveTrip();

        assertNotNull(result);
        assertNotNull(active);
        assertTrue(active.active);
    }

    @Test
    public void sourceStopDoesNotFinishDifferentAutoSource() {
        repository.startTrip(true, AutoStartSource.MOTION);

        TripRecord result = repository.finishActiveTrip(true, AutoStartSource.BLUETOOTH);
        TripRecord active = repository.getActiveTrip();

        assertNotNull(result);
        assertNotNull(active);
        assertTrue(active.active);
        assertEquals(AutoStartSource.MOTION, active.autoStartSource);
    }

    @Test
    public void finishTripAppliesMatchingStartPlaceCategory() {
        TripRecord trip = repository.startTrip(false);
        repository.addPoint(trip.id, location(40.0, -74.0, 1_000));
        repository.addPlace("Office", 40.0, -74.0, 150, TripCategory.BUSINESS, true, false);

        TripRecord finished = repository.finishActiveTrip(false);

        assertNotNull(finished);
        assertFalse(finished.active);
        assertTrue(finished.reviewed);
        assertEquals(TripCategory.BUSINESS, finished.category);
    }

    @Test
    public void updatePlaceChangesStoredRule() {
        repository.addPlace("Office", 40.0, -74.0, 150, TripCategory.BUSINESS, true, false);
        PlaceRule place = repository.places().get(0);

        repository.updatePlace(place.id, "Campus", 41.0, -75.0, 200, "Uni", false, true);

        PlaceRule updated = repository.places().get(0);
        assertEquals("Campus", updated.name);
        assertEquals(41.0, updated.latitude, 0.001);
        assertEquals(-75.0, updated.longitude, 0.001);
        assertEquals(200.0, updated.radiusMeters, 0.001);
        assertEquals("Uni", updated.category);
        assertFalse(updated.matchStart);
        assertTrue(updated.matchEnd);
    }

    @Test
    public void farSegmentsDoNotInflateDistance() {
        TripRecord trip = repository.startTrip(false);
        repository.addPoint(trip.id, location(40.0, -74.0, 1_000));
        repository.addPoint(trip.id, location(41.0, -75.0, 2_000));

        TripRecord updated = repository.getTrip(trip.id);

        assertNotNull(updated);
        assertEquals(0.0, updated.distanceMeters, 0.001);
    }

    @Test
    public void reviewTripMarksTripReviewed() {
        TripRecord trip = repository.startTrip(false);
        repository.finishActiveTrip(false);

        repository.reviewTrip(trip.id, TripCategory.PERSONAL);

        TripRecord reviewed = repository.getTrip(trip.id);
        assertNotNull(reviewed);
        assertTrue(reviewed.reviewed);
        assertEquals(TripCategory.PERSONAL, reviewed.category);
    }

    @Test
    public void newTripUsesDefaultVehicle() {
        long vehicleId = repository.addVehicle("Truck");
        new SettingsStore(context).setDefaultVehicleId(vehicleId);

        TripRecord trip = repository.startTrip(false);

        assertNotNull(trip.vehicleId);
        assertEquals(vehicleId, trip.vehicleId.longValue());
        assertEquals("Truck", trip.vehicleName);
    }

    @Test
    public void vehicleStoresDetailsAndMatchesBluetooth() {
        long vehicleId = repository.addVehicle(
                "Toyota",
                "Corolla",
                true,
                "Car BT",
                "AA:BB:CC:DD:EE:FF");

        VehicleRecord stored = repository.vehicle(vehicleId);
        VehicleRecord match = repository.matchingBluetoothVehicle("Other", "aa:bb:cc:dd:ee:ff");

        assertNotNull(stored);
        assertEquals("Toyota Corolla", stored.label());
        assertTrue(stored.odometerPromptEnabled);
        assertTrue(stored.hasBluetooth());
        assertNotNull(match);
        assertEquals(vehicleId, match.id);
    }

    @Test
    public void bluetoothStartedTripUsesMatchedVehicleAndStartOdometer() {
        long vehicleId = repository.addVehicle("Honda", "Civic", true, "Civic BT", "11:22");

        TripRecord trip = repository.startTrip(true, AutoStartSource.BLUETOOTH, vehicleId, 1234.5);

        assertNotNull(trip.vehicleId);
        assertEquals(vehicleId, trip.vehicleId.longValue());
        assertEquals("Honda Civic", trip.vehicleName);
        assertNotNull(trip.startOdometer);
        assertEquals(1234.5, trip.startOdometer, 0.001);
    }

    private Location location(double lat, double lng, long offsetMs) {
        Location location = new Location("test");
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setAccuracy(10f);
        location.setTime(System.currentTimeMillis() + offsetMs);
        return location;
    }
}
