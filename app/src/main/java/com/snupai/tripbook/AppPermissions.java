package com.snupai.tripbook;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

final class AppPermissions {
    private AppPermissions() {
    }

    static boolean hasLocation(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackgroundLocation(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBluetoothConnect(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasActivityRecognition(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasEnabledLocationProvider(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        return providerEnabled(manager, LocationManager.GPS_PROVIDER)
                || providerEnabled(manager, LocationManager.NETWORK_PROVIDER);
    }

    static boolean canAutoRecord(Context context, SettingsStore settings) {
        return canBluetoothAutoRecord(context, settings);
    }

    static boolean canBluetoothAutoRecord(Context context, SettingsStore settings) {
        TripRepository repository = new TripRepository(context);
        try {
            return canBluetoothAutoRecord(context, settings, repository);
        } finally {
            repository.close();
        }
    }

    static boolean canBluetoothAutoRecord(Context context, SettingsStore settings, TripRepository repository) {
        return settings.autoRecordEnabled()
                && settings.locationDisclosureAccepted()
                && repository.hasBluetoothVehicle()
                && hasLocation(context)
                && hasBackgroundLocation(context)
                && hasBluetoothConnect(context)
                && hasNotificationPermission(context);
    }

    static boolean canMotionAutoRecord(Context context, SettingsStore settings) {
        return settings.motionAutoRecordEnabled()
                && settings.locationDisclosureAccepted()
                && hasLocation(context)
                && hasBackgroundLocation(context)
                && hasActivityRecognition(context)
                && hasNotificationPermission(context);
    }

    static boolean canAnyAutoRecord(Context context, SettingsStore settings) {
        TripRepository repository = new TripRepository(context);
        try {
            return canAnyAutoRecord(context, settings, repository);
        } finally {
            repository.close();
        }
    }

    static boolean canAnyAutoRecord(Context context, SettingsStore settings, TripRepository repository) {
        return canBluetoothAutoRecord(context, settings, repository) || canMotionAutoRecord(context, settings);
    }

    private static boolean providerEnabled(LocationManager manager, String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
