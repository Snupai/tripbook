package com.snupai.tripbook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;

final class TripRepository extends SQLiteOpenHelper {
    private static final String DB_NAME = "tripbook.db";
    private static final int DB_VERSION = 3;
    private static final double MIN_SEGMENT_METERS = 5.0;
    private static final double MAX_SEGMENT_METERS = 2_000.0;
    private static final float MAX_ACCEPTED_ACCURACY_METERS = 120f;
    private static final long MAX_POINT_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final long MAX_POINT_FUTURE_MS = 5 * 60 * 1000L;

    private final SettingsStore settings;

    TripRepository(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        settings = new SettingsStore(context);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createVehicles(db);
        createTrips(db);
        createPoints(db);
        createPlaces(db);
        createOdometerReadings(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createVehicles(db);
            createOdometerReadings(db);
            db.execSQL("ALTER TABLE trips ADD COLUMN vehicle_id INTEGER");
            db.execSQL("ALTER TABLE trips ADD COLUMN start_odometer REAL");
            db.execSQL("ALTER TABLE trips ADD COLUMN end_odometer REAL");
            db.execSQL("ALTER TABLE trips ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE trips ADD COLUMN auto_start_source TEXT NOT NULL DEFAULT '" + AutoStartSource.MANUAL + "'");
            db.execSQL("UPDATE trips SET auto_start_source = '" + AutoStartSource.BLUETOOTH + "' WHERE auto_started = 1");
            oldVersion = 2;
        }
        if (oldVersion < 3) {
            upgradeVehiclesToV3(db);
            oldVersion = 3;
        }
        if (oldVersion != newVersion) {
            throw new IllegalStateException("No migrations defined for " + oldVersion + " -> " + newVersion);
        }
    }

    synchronized TripRecord startTrip(boolean autoStarted) {
        return startTrip(autoStarted, autoStarted ? AutoStartSource.BLUETOOTH : AutoStartSource.MANUAL);
    }

    synchronized TripRecord startTrip(boolean autoStarted, String source) {
        return startTrip(autoStarted, source, null, null);
    }

    synchronized TripRecord startTrip(boolean autoStarted, String source, Long requestedVehicleId, Double startOdometer) {
        TripRecord active = getActiveTrip();
        if (active != null) {
            return active;
        }

        String normalizedSource = AutoStartSource.normalize(source, autoStarted);
        long vehicleId = requestedVehicleId == null ? settings.defaultVehicleId() : requestedVehicleId;
        if (!vehicleExists(vehicleId)) {
            vehicleId = -1;
            if (requestedVehicleId == null) {
                settings.setDefaultVehicleId(-1);
            }
        }

        ContentValues values = new ContentValues();
        values.put("start_time", System.currentTimeMillis());
        values.put("distance_meters", 0);
        values.put("category", settings.defaultCategory());
        values.put("notes", "");
        values.put("auto_started", AutoStartSource.MANUAL.equals(normalizedSource) ? 0 : 1);
        values.put("auto_start_source", normalizedSource);
        values.put("active", 1);
        values.put("reviewed", 0);
        if (vehicleId > 0) {
            values.put("vehicle_id", vehicleId);
        }
        putNullableDouble(values, "start_odometer", startOdometer);
        long id = getWritableDatabase().insertOrThrow("trips", null, values);
        return getTrip(id);
    }

    synchronized TripRecord getActiveTrip() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                tripSelect() + " WHERE trips.active = 1 ORDER BY trips.start_time DESC LIMIT 1",
                new String[0])) {
            return cursor.moveToFirst() ? readTrip(cursor) : null;
        }
    }

    synchronized TripRecord getTrip(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(tripSelect() + " WHERE trips.id = ?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? readTrip(cursor) : null;
        }
    }

    synchronized void addPoint(long tripId, Location location) {
        if (location == null) {
            return;
        }
        long locationTime = location.getTime();
        if (locationTime > 0) {
            long age = System.currentTimeMillis() - locationTime;
            if (age > MAX_POINT_AGE_MS || age < -MAX_POINT_FUTURE_MS) {
                return;
            }
        }
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPTED_ACCURACY_METERS) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            TripRecord trip = getTrip(tripId);
            if (trip == null || !trip.active) {
                return;
            }

            double[] previous = lastPoint(db, tripId);
            double segment = 0;
            if (previous != null) {
                segment = GeoMath.distanceMeters(previous[0], previous[1],
                        location.getLatitude(), location.getLongitude());
                if (segment < MIN_SEGMENT_METERS || segment > MAX_SEGMENT_METERS) {
                    segment = 0;
                }
            }

            ContentValues point = new ContentValues();
            point.put("trip_id", tripId);
            point.put("recorded_at", location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
            point.put("lat", location.getLatitude());
            point.put("lng", location.getLongitude());
            if (location.hasAccuracy()) {
                point.put("accuracy", location.getAccuracy());
            }
            if (location.hasSpeed()) {
                point.put("speed", location.getSpeed());
            }
            db.insertOrThrow("points", null, point);

            ContentValues values = new ContentValues();
            if (trip.startLat == null || trip.startLng == null) {
                values.put("start_lat", location.getLatitude());
                values.put("start_lng", location.getLongitude());
            }
            values.put("end_lat", location.getLatitude());
            values.put("end_lng", location.getLongitude());
            values.put("distance_meters", trip.distanceMeters + segment);
            db.update("trips", values, "id = ?", new String[]{String.valueOf(tripId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized TripRecord finishActiveTrip(boolean onlyIfAutoStarted) {
        return finishActiveTrip(onlyIfAutoStarted, null);
    }

    synchronized TripRecord finishActiveTrip(boolean onlyIfAutoStarted, String requiredSource) {
        return finishActiveTrip(onlyIfAutoStarted, requiredSource, null);
    }

    synchronized TripRecord finishActiveTrip(boolean onlyIfAutoStarted, String requiredSource, Double endOdometer) {
        TripRecord trip = getActiveTrip();
        if (trip == null) {
            return null;
        }
        if (onlyIfAutoStarted && !trip.autoStarted) {
            return trip;
        }
        if (requiredSource != null && !requiredSource.equals(trip.autoStartSource)) {
            return trip;
        }

        String placeCategory = matchingCategory(trip);
        String category = placeCategory;
        if (category == null) {
            category = settings.workHoursCategory(trip.startTime);
        }
        if (category == null) {
            category = trip.category == null ? TripCategory.UNCATEGORIZED : trip.category;
        }

        ContentValues values = new ContentValues();
        values.put("end_time", System.currentTimeMillis());
        values.put("category", category);
        if (placeCategory != null) {
            values.put("reviewed", 1);
        }
        if (endOdometer != null && endOdometer >= 0) {
            values.put("end_odometer", endOdometer);
        }
        values.put("active", 0);
        getWritableDatabase().update("trips", values, "id = ?", new String[]{String.valueOf(trip.id)});
        return getTrip(trip.id);
    }

    synchronized List<TripRecord> recentTrips(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<TripRecord> trips = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                tripSelect() + " ORDER BY trips.start_time DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                trips.add(readTrip(cursor));
            }
        }
        return trips;
    }

    synchronized List<TripRecord> tripsNeedingReview(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<TripRecord> trips = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                tripSelect() + " WHERE trips.active = 0 AND trips.reviewed = 0 ORDER BY trips.start_time DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                trips.add(readTrip(cursor));
            }
        }
        return trips;
    }

    synchronized List<TripRecord> reportTrips(long startMillis, long endMillis) {
        SQLiteDatabase db = getReadableDatabase();
        List<TripRecord> trips = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                tripSelect() + " WHERE trips.active = 0 AND trips.start_time >= ? AND trips.start_time <= ?"
                        + " ORDER BY trips.start_time ASC",
                new String[]{String.valueOf(startMillis), String.valueOf(endMillis)})) {
            while (cursor.moveToNext()) {
                trips.add(readTrip(cursor));
            }
        }
        return trips;
    }

    synchronized List<TripPoint> tripPoints(long tripId) {
        SQLiteDatabase db = getReadableDatabase();
        List<TripPoint> points = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT recorded_at, lat, lng FROM points WHERE trip_id = ? ORDER BY recorded_at ASC",
                new String[]{String.valueOf(tripId)})) {
            while (cursor.moveToNext()) {
                TripPoint point = new TripPoint();
                point.recordedAt = cursor.getLong(0);
                point.latitude = cursor.getDouble(1);
                point.longitude = cursor.getDouble(2);
                points.add(point);
            }
        }
        return points;
    }

    synchronized TripReport report(long startMillis, long endMillis) {
        return TripReport.build(
                startMillis,
                endMillis,
                reportTrips(startMillis, endMillis),
                settings.categoryRates(),
                settings.resolvedDistanceUnit());
    }

    synchronized String exportCsv() {
        return TripReportCsv.render(report(0, Long.MAX_VALUE));
    }

    synchronized void updateTrip(long id, String category, String notes) {
        TripRecord trip = getTrip(id);
        updateTrip(id, category, notes,
                trip == null ? null : trip.vehicleId,
                trip == null ? null : trip.startOdometer,
                trip == null ? null : trip.endOdometer);
    }

    synchronized void updateTrip(long id, String category, String notes,
                                 Long vehicleId, Double startOdometer, Double endOdometer) {
        ContentValues values = new ContentValues();
        values.put("category", TripCategory.normalize(category));
        values.put("notes", notes == null ? "" : notes.trim());
        values.put("reviewed", 1);
        putNullableLong(values, "vehicle_id", vehicleId);
        putNullableDouble(values, "start_odometer", startOdometer);
        putNullableDouble(values, "end_odometer", endOdometer);
        getWritableDatabase().update("trips", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void reviewTrip(long id, String category) {
        ContentValues values = new ContentValues();
        values.put("category", TripCategory.normalize(category));
        values.put("reviewed", 1);
        getWritableDatabase().update("trips", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void deleteTrip(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("points", "trip_id = ?", new String[]{String.valueOf(id)});
            db.delete("trips", "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized long addVehicle(String name) {
        ContentValues values = new ContentValues();
        values.put("name", cleanName(name, "Vehicle"));
        return getWritableDatabase().insertOrThrow("vehicles", null, values);
    }

    synchronized long addVehicle(String brand, String model, boolean odometerPromptEnabled,
                                 String bluetoothName, String bluetoothAddress) {
        ContentValues values = vehicleValues(brand, model, odometerPromptEnabled, bluetoothName, bluetoothAddress);
        return getWritableDatabase().insertOrThrow("vehicles", null, values);
    }

    synchronized void updateVehicle(long id, String name) {
        ContentValues values = new ContentValues();
        values.put("name", cleanName(name, "Vehicle"));
        getWritableDatabase().update("vehicles", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void updateVehicle(long id, String brand, String model, boolean odometerPromptEnabled,
                                    String bluetoothName, String bluetoothAddress) {
        ContentValues values = vehicleValues(brand, model, odometerPromptEnabled, bluetoothName, bluetoothAddress);
        getWritableDatabase().update("vehicles", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void updateVehicleBluetooth(long id, String bluetoothName, String bluetoothAddress) {
        ContentValues values = new ContentValues();
        values.put("bluetooth_name", cleanOptional(bluetoothName));
        values.put("bluetooth_address", cleanOptional(bluetoothAddress));
        getWritableDatabase().update("vehicles", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void deleteVehicle(long id) {
        getWritableDatabase().delete("vehicles", "id = ?", new String[]{String.valueOf(id)});
        if (settings.defaultVehicleId() == id) {
            settings.setDefaultVehicleId(-1);
        }
    }

    synchronized List<VehicleRecord> vehicles() {
        SQLiteDatabase db = getReadableDatabase();
        List<VehicleRecord> vehicles = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT * FROM vehicles ORDER BY name COLLATE NOCASE",
                new String[0])) {
            while (cursor.moveToNext()) {
                vehicles.add(readVehicle(cursor));
            }
        }
        return vehicles;
    }

    synchronized VehicleRecord vehicle(long id) {
        if (id <= 0) {
            return null;
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT * FROM vehicles WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst() ? readVehicle(cursor) : null;
        }
    }

    synchronized List<VehicleRecord> bluetoothVehicles() {
        SQLiteDatabase db = getReadableDatabase();
        List<VehicleRecord> vehicles = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT * FROM vehicles WHERE bluetooth_name <> '' OR bluetooth_address <> ''"
                        + " ORDER BY name COLLATE NOCASE",
                new String[0])) {
            while (cursor.moveToNext()) {
                vehicles.add(readVehicle(cursor));
            }
        }
        return vehicles;
    }

    synchronized boolean hasBluetoothVehicle() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT id FROM vehicles WHERE bluetooth_name <> '' OR bluetooth_address <> '' LIMIT 1",
                new String[0])) {
            return cursor.moveToFirst();
        }
    }

    synchronized VehicleRecord matchingBluetoothVehicle(String deviceName, String deviceAddress) {
        SQLiteDatabase db = getReadableDatabase();
        String address = cleanOptional(deviceAddress);
        if (!address.isEmpty()) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT * FROM vehicles WHERE lower(bluetooth_address) = lower(?) LIMIT 1",
                    new String[]{address})) {
                if (cursor.moveToFirst()) {
                    return readVehicle(cursor);
                }
            }
        }

        String name = cleanOptional(deviceName);
        if (!name.isEmpty()) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT * FROM vehicles WHERE bluetooth_name = ? LIMIT 1",
                    new String[]{name})) {
                if (cursor.moveToFirst()) {
                    return readVehicle(cursor);
                }
            }
        }
        return null;
    }

    synchronized boolean vehicleExists(long id) {
        if (id <= 0) {
            return false;
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT id FROM vehicles WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst();
        }
    }

    synchronized void addOdometerReading(long vehicleId, double odometerValue, String notes) {
        if (!vehicleExists(vehicleId) || odometerValue < 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("vehicle_id", vehicleId);
        values.put("recorded_at", System.currentTimeMillis());
        values.put("odometer_value", odometerValue);
        values.put("notes", notes == null ? "" : notes.trim());
        getWritableDatabase().insertOrThrow("odometer_readings", null, values);
    }

    synchronized List<OdometerReading> recentOdometerReadings(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<OdometerReading> readings = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT odometer_readings.*, vehicles.name AS vehicle_name"
                        + " FROM odometer_readings"
                        + " LEFT JOIN vehicles ON odometer_readings.vehicle_id = vehicles.id"
                        + " ORDER BY recorded_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                readings.add(readOdometerReading(cursor));
            }
        }
        return readings;
    }

    synchronized long latestOdometerReadingAt(long vehicleId) {
        if (vehicleId <= 0) {
            return 0;
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT recorded_at FROM odometer_readings WHERE vehicle_id = ? ORDER BY recorded_at DESC LIMIT 1",
                new String[]{String.valueOf(vehicleId)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    synchronized void addPlace(String name, double lat, double lng, double radiusMeters,
                               String category, boolean matchStart, boolean matchEnd) {
        ContentValues values = new ContentValues();
        values.put("name", cleanName(name, "Saved place"));
        values.put("lat", lat);
        values.put("lng", lng);
        values.put("radius_meters", Math.max(25, radiusMeters));
        values.put("category", TripCategory.normalize(category));
        values.put("match_start", matchStart ? 1 : 0);
        values.put("match_end", matchEnd ? 1 : 0);
        getWritableDatabase().insertOrThrow("places", null, values);
    }

    synchronized void updatePlace(long id, String name, double lat, double lng, double radiusMeters,
                                  String category, boolean matchStart, boolean matchEnd) {
        ContentValues values = new ContentValues();
        values.put("name", cleanName(name, "Saved place"));
        values.put("lat", lat);
        values.put("lng", lng);
        values.put("radius_meters", Math.max(25, radiusMeters));
        values.put("category", TripCategory.normalize(category));
        values.put("match_start", matchStart ? 1 : 0);
        values.put("match_end", matchEnd ? 1 : 0);
        getWritableDatabase().update("places", values, "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized void deletePlace(long id) {
        getWritableDatabase().delete("places", "id = ?", new String[]{String.valueOf(id)});
    }

    synchronized List<PlaceRule> places() {
        SQLiteDatabase db = getReadableDatabase();
        List<PlaceRule> places = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT * FROM places ORDER BY name COLLATE NOCASE",
                new String[0])) {
            while (cursor.moveToNext()) {
                places.add(readPlace(cursor));
            }
        }
        return places;
    }

    private void createTrips(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trips ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_time INTEGER NOT NULL,"
                + "end_time INTEGER,"
                + "start_lat REAL,"
                + "start_lng REAL,"
                + "end_lat REAL,"
                + "end_lng REAL,"
                + "distance_meters REAL NOT NULL DEFAULT 0,"
                + "category TEXT NOT NULL,"
                + "notes TEXT NOT NULL DEFAULT '',"
                + "auto_started INTEGER NOT NULL DEFAULT 0,"
                + "auto_start_source TEXT NOT NULL DEFAULT '" + AutoStartSource.MANUAL + "',"
                + "vehicle_id INTEGER,"
                + "start_odometer REAL,"
                + "end_odometer REAL,"
                + "reviewed INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 1"
                + ")");
    }

    private void createPoints(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS points ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "trip_id INTEGER NOT NULL,"
                + "recorded_at INTEGER NOT NULL,"
                + "lat REAL NOT NULL,"
                + "lng REAL NOT NULL,"
                + "accuracy REAL,"
                + "speed REAL,"
                + "FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_trip_time ON points(trip_id, recorded_at)");
    }

    private void createPlaces(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS places ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "lat REAL NOT NULL,"
                + "lng REAL NOT NULL,"
                + "radius_meters REAL NOT NULL,"
                + "category TEXT NOT NULL,"
                + "match_start INTEGER NOT NULL DEFAULT 1,"
                + "match_end INTEGER NOT NULL DEFAULT 1"
                + ")");
    }

    private void createVehicles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vehicles ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "brand TEXT NOT NULL DEFAULT '',"
                + "model TEXT NOT NULL DEFAULT '',"
                + "odometer_prompt_enabled INTEGER NOT NULL DEFAULT 0,"
                + "bluetooth_name TEXT NOT NULL DEFAULT '',"
                + "bluetooth_address TEXT NOT NULL DEFAULT ''"
                + ")");
    }

    private void upgradeVehiclesToV3(SQLiteDatabase db) {
        addColumnIfMissing(db, "vehicles", "brand", "brand TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "vehicles", "model", "model TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "vehicles", "odometer_prompt_enabled",
                "odometer_prompt_enabled INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "vehicles", "bluetooth_name", "bluetooth_name TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "vehicles", "bluetooth_address", "bluetooth_address TEXT NOT NULL DEFAULT ''");
        migrateLegacyBluetoothCar(db);
    }

    private void createOdometerReadings(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS odometer_readings ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "vehicle_id INTEGER NOT NULL,"
                + "recorded_at INTEGER NOT NULL,"
                + "odometer_value REAL NOT NULL,"
                + "notes TEXT NOT NULL DEFAULT ''"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_odometer_vehicle_time ON odometer_readings(vehicle_id, recorded_at)");
    }

    private String matchingCategory(TripRecord trip) {
        for (PlaceRule place : places()) {
            if (place.matchEnd && place.contains(trip.endLat, trip.endLng)) {
                return place.category;
            }
        }
        for (PlaceRule place : places()) {
            if (place.matchStart && place.contains(trip.startLat, trip.startLng)) {
                return place.category;
            }
        }
        return null;
    }

    private double[] lastPoint(SQLiteDatabase db, long tripId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT lat, lng FROM points WHERE trip_id = ? ORDER BY recorded_at DESC LIMIT 1",
                new String[]{String.valueOf(tripId)})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new double[]{cursor.getDouble(0), cursor.getDouble(1)};
        }
    }

    private String tripSelect() {
        return "SELECT trips.*, vehicles.name AS vehicle_name"
                + " FROM trips"
                + " LEFT JOIN vehicles ON trips.vehicle_id = vehicles.id";
    }

    private TripRecord readTrip(Cursor cursor) {
        TripRecord trip = new TripRecord();
        trip.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        trip.startTime = cursor.getLong(cursor.getColumnIndexOrThrow("start_time"));
        int endTime = cursor.getColumnIndexOrThrow("end_time");
        trip.endTime = cursor.isNull(endTime) ? null : cursor.getLong(endTime);
        trip.startLat = nullableDouble(cursor, "start_lat");
        trip.startLng = nullableDouble(cursor, "start_lng");
        trip.endLat = nullableDouble(cursor, "end_lat");
        trip.endLng = nullableDouble(cursor, "end_lng");
        trip.distanceMeters = cursor.getDouble(cursor.getColumnIndexOrThrow("distance_meters"));
        trip.category = cursor.getString(cursor.getColumnIndexOrThrow("category"));
        trip.notes = cursor.getString(cursor.getColumnIndexOrThrow("notes"));
        trip.autoStarted = cursor.getInt(cursor.getColumnIndexOrThrow("auto_started")) == 1;
        trip.autoStartSource = AutoStartSource.normalize(nullableString(cursor, "auto_start_source"), trip.autoStarted);
        trip.vehicleId = nullableLong(cursor, "vehicle_id");
        trip.vehicleName = nullableString(cursor, "vehicle_name");
        trip.startOdometer = nullableDouble(cursor, "start_odometer");
        trip.endOdometer = nullableDouble(cursor, "end_odometer");
        trip.reviewed = intValue(cursor, "reviewed", 0) == 1;
        trip.active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1;
        return trip;
    }

    private PlaceRule readPlace(Cursor cursor) {
        PlaceRule place = new PlaceRule();
        place.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        place.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
        place.latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"));
        place.longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("lng"));
        place.radiusMeters = cursor.getDouble(cursor.getColumnIndexOrThrow("radius_meters"));
        place.category = cursor.getString(cursor.getColumnIndexOrThrow("category"));
        place.matchStart = cursor.getInt(cursor.getColumnIndexOrThrow("match_start")) == 1;
        place.matchEnd = cursor.getInt(cursor.getColumnIndexOrThrow("match_end")) == 1;
        return place;
    }

    private VehicleRecord readVehicle(Cursor cursor) {
        VehicleRecord vehicle = new VehicleRecord();
        vehicle.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        vehicle.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
        vehicle.brand = nullableString(cursor, "brand");
        vehicle.model = nullableString(cursor, "model");
        vehicle.odometerPromptEnabled = intValue(cursor, "odometer_prompt_enabled", 0) == 1;
        vehicle.bluetoothName = nullableString(cursor, "bluetooth_name");
        vehicle.bluetoothAddress = nullableString(cursor, "bluetooth_address");
        return vehicle;
    }

    private OdometerReading readOdometerReading(Cursor cursor) {
        OdometerReading reading = new OdometerReading();
        reading.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        reading.vehicleId = cursor.getLong(cursor.getColumnIndexOrThrow("vehicle_id"));
        reading.vehicleName = nullableString(cursor, "vehicle_name");
        reading.recordedAt = cursor.getLong(cursor.getColumnIndexOrThrow("recorded_at"));
        reading.odometerValue = cursor.getDouble(cursor.getColumnIndexOrThrow("odometer_value"));
        reading.notes = cursor.getString(cursor.getColumnIndexOrThrow("notes"));
        return reading;
    }

    private static void putNullableLong(ContentValues values, String key, Long value) {
        if (value == null || value <= 0) {
            values.putNull(key);
        } else {
            values.put(key, value);
        }
    }

    private static void putNullableDouble(ContentValues values, String key, Double value) {
        if (value == null || value < 0) {
            values.putNull(key);
        } else {
            values.put(key, value);
        }
    }

    private static String cleanName(String name, String fallback) {
        return name == null || name.trim().isEmpty() ? fallback : name.trim();
    }

    private static ContentValues vehicleValues(String brand, String model, boolean odometerPromptEnabled,
                                               String bluetoothName, String bluetoothAddress) {
        String cleanBrand = cleanOptional(brand);
        String cleanModel = cleanOptional(model);
        ContentValues values = new ContentValues();
        values.put("name", vehicleLabel(cleanBrand, cleanModel));
        values.put("brand", cleanBrand);
        values.put("model", cleanModel);
        values.put("odometer_prompt_enabled", odometerPromptEnabled ? 1 : 0);
        values.put("bluetooth_name", cleanOptional(bluetoothName));
        values.put("bluetooth_address", cleanOptional(bluetoothAddress));
        return values;
    }

    private static String vehicleLabel(String brand, String model) {
        String label = (cleanOptional(brand) + " " + cleanOptional(model)).trim();
        return label.isEmpty() ? "Vehicle" : label;
    }

    private static String cleanOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private void migrateLegacyBluetoothCar(SQLiteDatabase db) {
        String name = cleanOptional(settings.carName());
        String address = cleanOptional(settings.carAddress());
        if (name.isEmpty() && address.isEmpty()) {
            return;
        }
        if (hasBluetoothVehicle(db)) {
            return;
        }

        long vehicleId = settings.defaultVehicleId();
        if (vehicleId > 0 && vehicleExists(db, vehicleId)) {
            ContentValues values = new ContentValues();
            values.put("bluetooth_name", name);
            values.put("bluetooth_address", address);
            db.update("vehicles", values, "id = ?", new String[]{String.valueOf(vehicleId)});
            return;
        }

        ContentValues values = new ContentValues();
        values.put("name", cleanName(name, "Vehicle"));
        values.put("brand", "");
        values.put("model", "");
        values.put("odometer_prompt_enabled", 0);
        values.put("bluetooth_name", name);
        values.put("bluetooth_address", address);
        long id = db.insertOrThrow("vehicles", null, values);
        settings.setDefaultVehicleId(id);
    }

    private boolean hasBluetoothVehicle(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT id FROM vehicles WHERE bluetooth_name <> '' OR bluetooth_address <> '' LIMIT 1",
                new String[0])) {
            return cursor.moveToFirst();
        }
    }

    private boolean vehicleExists(SQLiteDatabase db, long id) {
        if (id <= 0) {
            return false;
        }
        try (Cursor cursor = db.rawQuery("SELECT id FROM vehicles WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst();
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (!columnExists(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + definition);
        }
    }

    private boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", new String[0])) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Double nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getDouble(index);
    }

    private Long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? null : cursor.getLong(index);
    }

    private String nullableString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private int intValue(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }
}
