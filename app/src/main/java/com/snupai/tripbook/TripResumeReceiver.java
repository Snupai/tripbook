package com.snupai.tripbook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class TripResumeReceiver extends BroadcastReceiver {
    private static final String TAG = "TripResumeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SettingsStore settings = new SettingsStore(context);
        TripRepository repository = new TripRepository(context);
        boolean hasActiveTrip;
        try {
            hasActiveTrip = repository.getActiveTrip() != null;
        } finally {
            repository.close();
        }

        Intent service = null;
        if (hasActiveTrip) {
            if (!AppPermissions.hasLocation(context)) {
                TripNotifications.showResumeTripRequired(context);
                return;
            }
            service = new Intent(context, TripTrackingService.class)
                    .setAction(TripTrackingService.ACTION_START);
        } else if (AppPermissions.canAnyAutoRecord(context, settings)) {
            service = new Intent(context, TripTrackingService.class)
                    .setAction(TripTrackingService.ACTION_ENABLE_AUTO_DETECT);
        }

        if (service == null) {
            return;
        }

        try {
            context.startForegroundService(service);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to resume active trip tracking", exception);
            TripNotifications.showResumeTripRequired(context);
        }
    }
}
