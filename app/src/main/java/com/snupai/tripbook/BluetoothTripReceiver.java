package com.snupai.tripbook;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BluetoothTripReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothTripReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        SettingsStore settings = new SettingsStore(context);
        TripRepository repository = new TripRepository(context);
        try {
            if (!AppPermissions.canBluetoothAutoRecord(context, settings, repository)) {
                return;
            }

            BluetoothDevice device = readDevice(intent);
            String name = deviceName(context, device);
            String address = deviceAddress(context, device);
            VehicleRecord vehicle = repository.matchingBluetoothVehicle(name, address);
            if (vehicle == null) {
                return;
            }
            String deviceKey = address == null || address.isEmpty() ? name : address;
            if (!settings.shouldHandleBluetoothEvent(intent.getAction(), deviceKey, System.currentTimeMillis())) {
                return;
            }

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                Intent service = new Intent(context, TripTrackingService.class)
                        .setAction(TripTrackingService.ACTION_START)
                        .putExtra(TripTrackingService.EXTRA_AUTO_STARTED, true)
                        .putExtra(TripTrackingService.EXTRA_START_SOURCE, AutoStartSource.BLUETOOTH)
                        .putExtra(TripTrackingService.EXTRA_VEHICLE_ID, vehicle.id);
                tryStartForegroundService(context, service, true);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                Intent service = new Intent(context, TripTrackingService.class)
                        .setAction(TripTrackingService.ACTION_STOP_IF_AUTO)
                        .putExtra(TripTrackingService.EXTRA_STOP_SOURCE, AutoStartSource.BLUETOOTH);
                tryStartForegroundService(context, service, false);
            }
        } finally {
            repository.close();
        }
    }

    private BluetoothDevice readDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private String deviceName(Context context, BluetoothDevice device) {
        if (device == null || !AppPermissions.hasBluetoothConnect(context)) {
            return "";
        }
        try {
            return device.getName();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private String deviceAddress(Context context, BluetoothDevice device) {
        if (device == null || !AppPermissions.hasBluetoothConnect(context)) {
            return "";
        }
        try {
            return device.getAddress();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private void tryStartForegroundService(Context context, Intent service, boolean showBlockedNotification) {
        try {
            context.startForegroundService(service);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to deliver Bluetooth trip event", exception);
            if (showBlockedNotification) {
                TripNotifications.showAutoStartBlocked(context);
            }
        }
    }
}
