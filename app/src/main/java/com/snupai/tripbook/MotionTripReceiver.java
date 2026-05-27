package com.snupai.tripbook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

public final class MotionTripReceiver extends BroadcastReceiver {
    private static final String TAG = "MotionTripReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ActivityTransitionResult.hasResult(intent)) {
            return;
        }
        SettingsStore settings = new SettingsStore(context);
        if (!AppPermissions.canMotionAutoRecord(context, settings)) {
            return;
        }
        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null) {
            return;
        }
        for (ActivityTransitionEvent event : result.getTransitionEvents()) {
            if (event.getActivityType() != DetectedActivity.IN_VEHICLE) {
                continue;
            }
            if (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                Intent service = new Intent(context, TripTrackingService.class)
                        .setAction(TripTrackingService.ACTION_START)
                        .putExtra(TripTrackingService.EXTRA_AUTO_STARTED, true)
                        .putExtra(TripTrackingService.EXTRA_START_SOURCE, AutoStartSource.MOTION);
                tryStartForegroundService(context, service, true);
            } else if (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                Intent service = new Intent(context, TripTrackingService.class)
                        .setAction(TripTrackingService.ACTION_STOP_IF_AUTO)
                        .putExtra(TripTrackingService.EXTRA_STOP_SOURCE, AutoStartSource.MOTION);
                tryStartForegroundService(context, service, false);
            }
        }
    }

    private void tryStartForegroundService(Context context, Intent service, boolean showBlockedNotification) {
        try {
            context.startForegroundService(service);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to deliver motion trip event", exception);
            if (showBlockedNotification) {
                TripNotifications.showAutoStartBlocked(context);
            }
        }
    }
}
