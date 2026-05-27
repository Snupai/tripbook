package com.snupai.tripbook;

import android.annotation.SuppressLint;
import android.app.Service;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;

import java.util.Arrays;
import java.util.List;

public final class TripTrackingService extends Service {
    private static final String TAG = "TripTrackingService";
    static final String ACTION_START = "com.snupai.tripbook.action.START";
    static final String ACTION_STOP = "com.snupai.tripbook.action.STOP";
    static final String ACTION_STOP_IF_AUTO = "com.snupai.tripbook.action.STOP_IF_AUTO";
    static final String ACTION_ENABLE_AUTO_DETECT = "com.snupai.tripbook.action.ENABLE_AUTO_DETECT";
    static final String ACTION_DISABLE_AUTO_DETECT = "com.snupai.tripbook.action.DISABLE_AUTO_DETECT";
    static final String ACTION_TRIP_UPDATED = "com.snupai.tripbook.action.TRIP_UPDATED";
    static final String EXTRA_AUTO_STARTED = "auto_started";
    static final String EXTRA_START_SOURCE = "start_source";
    static final String EXTRA_STOP_SOURCE = "stop_source";
    static final String EXTRA_VEHICLE_ID = "vehicle_id";
    static final String EXTRA_START_ODOMETER = "start_odometer";
    static final String EXTRA_END_ODOMETER = "end_odometer";

    private static final long MIN_TIME_MS = 15_000L;
    private static final float MIN_DISTANCE_METERS = 20f;
    private static final long MAX_LAST_KNOWN_AGE_MS = 30 * 60 * 1000L;
    private static final long MAX_FUTURE_LOCATION_MS = 60 * 1000L;

    private TripRepository repository;
    private SettingsStore settings;
    private LocationManager locationManager;
    private LocationListener listener;
    private BroadcastReceiver bluetoothReceiver;
    private boolean requestingUpdates;
    private boolean autoDetecting;
    private boolean motionDetectionRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = new TripRepository(this);
        settings = new SettingsStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        TripNotifications.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return resumeActiveTrip();
        }
        String action = intent.getAction() == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTrip(false, null, optionalDoubleExtra(intent, EXTRA_END_ODOMETER));
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_IF_AUTO.equals(action)) {
            stopTrip(true, intent.getStringExtra(EXTRA_STOP_SOURCE), null);
            return START_NOT_STICKY;
        }
        if (ACTION_ENABLE_AUTO_DETECT.equals(action)) {
            enableAutoDetect();
            return START_STICKY;
        }
        if (ACTION_DISABLE_AUTO_DETECT.equals(action)) {
            disableAutoDetect();
            return START_NOT_STICKY;
        }

        boolean autoStarted = intent != null && intent.getBooleanExtra(EXTRA_AUTO_STARTED, false);
        startTrip(autoStarted,
                intent.getStringExtra(EXTRA_START_SOURCE),
                optionalLongExtra(intent, EXTRA_VEHICLE_ID),
                optionalDoubleExtra(intent, EXTRA_START_ODOMETER));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        unregisterAutoDetectReceiver();
        unregisterMotionDetection();
        super.onDestroy();
    }

    private void startTrip(boolean autoStarted, String source, Long vehicleId, Double startOdometer) {
        if (!hasLocationPermission()) {
            stopSelf();
            return;
        }
        if (autoStarted && !AppPermissions.hasBackgroundLocation(this)) {
            TripNotifications.showAutoStartBlocked(this);
            stopSelf();
            return;
        }
        if (autoStarted && !AppPermissions.hasEnabledLocationProvider(this)) {
            TripNotifications.showAutoStartBlocked(this);
            stopSelf();
            return;
        }

        boolean hadActive = repository.getActiveTrip() != null;
        TripRecord trip = repository.startTrip(autoStarted, source, vehicleId, startOdometer);
        if (settings.autoRecordEnabled() || settings.motionAutoRecordEnabled()) {
            registerAutoDetectReceiver();
            registerMotionDetection();
        }
        try {
            startAsForeground(trip);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start foreground location tracking", exception);
            if (!hadActive && trip != null) {
                repository.deleteTrip(trip.id);
            }
            if (autoStarted) {
                TripNotifications.showAutoStartBlocked(this);
            }
            stopSelf();
            return;
        }
        requestLocationUpdates();
        publishUpdate();
    }

    private int resumeActiveTrip() {
        TripRecord active = repository.getActiveTrip();
        if (active == null) {
            if (settings.autoRecordEnabled() || settings.motionAutoRecordEnabled()) {
                enableAutoDetect();
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasLocationPermission()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (settings.autoRecordEnabled() || settings.motionAutoRecordEnabled()) {
            registerAutoDetectReceiver();
            registerMotionDetection();
        }
        try {
            startAsForeground(active);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to resume foreground location tracking", exception);
            stopSelf();
            return START_NOT_STICKY;
        }
        requestLocationUpdates();
        publishUpdate();
        return START_STICKY;
    }

    private void stopTrip(boolean onlyIfAutoStarted, String requiredSource, Double endOdometer) {
        TripRecord activeBeforeStop = repository.getActiveTrip();
        TripRecord finishedTrip = repository.finishActiveTrip(onlyIfAutoStarted, requiredSource, endOdometer);
        if (onlyIfAutoStarted && activeBeforeStop != null
                && (!activeBeforeStop.autoStarted
                || (requiredSource != null && !requiredSource.equals(activeBeforeStop.autoStartSource)))) {
            return;
        }
        stopLocationUpdates();
        publishUpdate();
        if (finishedTrip != null && !finishedTrip.active && !finishedTrip.reviewed) {
            TripNotifications.showTripNeedsReview(this);
        }
        if ((settings.autoRecordEnabled() || settings.motionAutoRecordEnabled()) && enableAutoDetect()) {
            return;
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startAsForeground(TripRecord trip) {
        String text = trip == null ? "Tracking trip" : GeoMath.distanceLabel(trip.distanceMeters, settings.resolvedDistanceUnit());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (autoDetecting && AppPermissions.canBluetoothAutoRecord(this, settings, repository)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            startForeground(
                    TripNotifications.TRACKING_ID,
                    TripNotifications.tracking(this, text),
                    type);
        } else {
            startForeground(TripNotifications.TRACKING_ID, TripNotifications.tracking(this, text));
        }
    }

    private boolean enableAutoDetect() {
        if (!AppPermissions.canAnyAutoRecord(this, settings, repository)) {
            unregisterAutoDetectReceiver();
            unregisterMotionDetection();
            if (repository.getActiveTrip() == null) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return false;
        }
        autoDetecting = true;
        try {
            startAsAutoDetectForeground();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to keep auto-record detection running", exception);
            unregisterAutoDetectReceiver();
            unregisterMotionDetection();
            autoDetecting = false;
            TripNotifications.showResumeTripRequired(this);
            stopSelf();
            return false;
        }
        registerAutoDetectReceiver();
        registerMotionDetection();
        publishUpdate();
        return true;
    }

    private void disableAutoDetect() {
        settings.setAutoRecordEnabled(false);
        settings.setMotionAutoRecordEnabled(false);
        unregisterAutoDetectReceiver();
        unregisterMotionDetection();
        autoDetecting = false;
        if (repository.getActiveTrip() == null) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            updateForegroundNotification();
        }
        publishUpdate();
    }

    private void startAsAutoDetectForeground() {
        String label;
        if (AppPermissions.canBluetoothAutoRecord(this, settings, repository) && AppPermissions.canMotionAutoRecord(this, settings)) {
            label = "Waiting for car Bluetooth or driving motion.";
        } else if (AppPermissions.canMotionAutoRecord(this, settings)) {
            label = "Waiting for driving motion.";
        } else {
            label = bluetoothWaitLabel();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = 0;
            if (AppPermissions.canBluetoothAutoRecord(this, settings, repository)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            if (AppPermissions.canMotionAutoRecord(this, settings)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (type == 0) {
                startForeground(TripNotifications.TRACKING_ID, TripNotifications.autoDetecting(this, label));
            } else {
                startForeground(
                        TripNotifications.TRACKING_ID,
                        TripNotifications.autoDetecting(this, label),
                        type);
            }
        } else {
            startForeground(TripNotifications.TRACKING_ID, TripNotifications.autoDetecting(this, label));
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerAutoDetectReceiver() {
        if (!AppPermissions.canBluetoothAutoRecord(this, settings, repository)) {
            unregisterAutoDetectReceiver();
            return;
        }
        if (bluetoothReceiver != null) {
            return;
        }
        bluetoothReceiver = new BluetoothTripReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, filter);
        }
    }

    private void unregisterAutoDetectReceiver() {
        if (bluetoothReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        bluetoothReceiver = null;
    }

    @SuppressLint("MissingPermission")
    private void registerMotionDetection() {
        if (motionDetectionRegistered || !AppPermissions.canMotionAutoRecord(this, settings)) {
            if (!AppPermissions.canMotionAutoRecord(this, settings)) {
                unregisterMotionDetection();
            }
            return;
        }
        try {
            ActivityRecognitionClient client = ActivityRecognition.getClient(this);
            motionDetectionRegistered = true;
            client.requestActivityTransitionUpdates(motionRequest(), motionPendingIntent())
                    .addOnSuccessListener(unused -> motionDetectionRegistered = true)
                    .addOnFailureListener(exception -> {
                        motionDetectionRegistered = false;
                        Log.w(TAG, "Unable to register motion auto-record", exception);
                    });
        } catch (RuntimeException exception) {
            motionDetectionRegistered = false;
            Log.w(TAG, "Unable to register motion auto-record", exception);
        }
    }

    @SuppressLint("MissingPermission")
    private void unregisterMotionDetection() {
        if (!motionDetectionRegistered) {
            return;
        }
        try {
            ActivityRecognition.getClient(this)
                    .removeActivityTransitionUpdates(motionPendingIntent())
                    .addOnCompleteListener(task -> motionDetectionRegistered = false);
        } catch (RuntimeException exception) {
            motionDetectionRegistered = false;
        }
    }

    private ActivityTransitionRequest motionRequest() {
        List<ActivityTransition> transitions = Arrays.asList(
                new ActivityTransition.Builder()
                        .setActivityType(DetectedActivity.IN_VEHICLE)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build(),
                new ActivityTransition.Builder()
                        .setActivityType(DetectedActivity.IN_VEHICLE)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .build());
        return new ActivityTransitionRequest(transitions);
    }

    private PendingIntent motionPendingIntent() {
        Intent intent = new Intent(this, MotionTripReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 12, intent, flags);
    }

    private void updateForegroundNotification() {
        TripRecord active = repository.getActiveTrip();
        if (active == null) {
            return;
        }
        startAsForeground(active);
    }

    private void requestLocationUpdates() {
        if (requestingUpdates || locationManager == null || !hasLocationPermission()) {
            return;
        }
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                TripRecord active = repository.getActiveTrip();
                if (active == null) {
                    return;
                }
                repository.addPoint(active.id, location);
                updateForegroundNotification();
                publishUpdate();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        boolean requested = false;
        requested |= requestProvider(LocationManager.GPS_PROVIDER);
        requested |= requestProvider(LocationManager.NETWORK_PROVIDER);
        addLastKnownPoint(LocationManager.GPS_PROVIDER);
        addLastKnownPoint(LocationManager.NETWORK_PROVIDER);
        requestingUpdates = requested;
    }

    private boolean requestProvider(String provider) {
        try {
            if (locationManager != null && locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                        provider,
                        MIN_TIME_MS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper());
                return true;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        return false;
    }

    private void addLastKnownPoint(String provider) {
        try {
            if (locationManager == null || !locationManager.isProviderEnabled(provider)) {
                return;
            }
            Location lastKnown = locationManager.getLastKnownLocation(provider);
            TripRecord active = repository.getActiveTrip();
            if (active != null && isRecent(lastKnown)) {
                repository.addPoint(active.id, lastKnown);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private boolean isRecent(Location location) {
        if (location == null || location.getTime() <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long age = now - location.getTime();
        return age >= -MAX_FUTURE_LOCATION_MS && age <= MAX_LAST_KNOWN_AGE_MS;
    }

    private void stopLocationUpdates() {
        if (locationManager != null && listener != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (SecurityException ignored) {
            }
        }
        requestingUpdates = false;
        listener = null;
    }

    private String bluetoothWaitLabel() {
        List<VehicleRecord> vehicles = repository.bluetoothVehicles();
        if (vehicles.isEmpty()) {
            return "Waiting for vehicle Bluetooth.";
        }
        if (vehicles.size() == 1) {
            return "Waiting for " + vehicles.get(0).label() + ".";
        }
        return "Waiting for " + vehicles.size() + " vehicle Bluetooth devices.";
    }

    private Long optionalLongExtra(Intent intent, String key) {
        if (intent == null || !intent.hasExtra(key)) {
            return null;
        }
        long value = intent.getLongExtra(key, -1L);
        return value > 0 ? value : null;
    }

    private Double optionalDoubleExtra(Intent intent, String key) {
        if (intent == null || !intent.hasExtra(key)) {
            return null;
        }
        double value = intent.getDoubleExtra(key, -1);
        return value >= 0 ? value : null;
    }

    private boolean hasLocationPermission() {
        return AppPermissions.hasLocation(this);
    }

    private void publishUpdate() {
        Intent update = new Intent(ACTION_TRIP_UPDATED);
        update.setPackage(getPackageName());
        sendBroadcast(update);
    }
}
