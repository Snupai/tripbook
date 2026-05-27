package com.snupai.tripbook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

final class TripNotifications {
    static final int TRACKING_ID = 1101;
    private static final int AUTOSTART_BLOCKED_ID = 1102;
    private static final int RESUME_REQUIRED_ID = 1103;
    private static final int TRIP_REVIEW_ID = 1104;
    private static final String TRACKING_CHANNEL = "trip_tracking";
    private static final String ALERT_CHANNEL = "trip_alerts";
    private static final String REVIEW_CHANNEL = "trip_review_alerts";

    private TripNotifications() {
    }

    static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel tracking = new NotificationChannel(
                TRACKING_CHANNEL,
                "Trip tracking",
                NotificationManager.IMPORTANCE_LOW);
        tracking.setDescription("Shown while Tripbook is recording a trip.");
        manager.createNotificationChannel(tracking);

        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL,
                "Trip alerts",
                NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription("Used when Android blocks automatic trip start.");
        manager.createNotificationChannel(alerts);

        NotificationChannel review = new NotificationChannel(
                REVIEW_CHANNEL,
                "Trip review alerts",
                NotificationManager.IMPORTANCE_HIGH);
        review.setDescription("Shown when a completed trip needs review.");
        review.enableVibration(true);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (sound != null) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            review.setSound(sound, audioAttributes);
        }
        manager.createNotificationChannel(review);
    }

    static Notification tracking(Context context, String text) {
        ensureChannels(context);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                1,
                new Intent(context, MainActivity.class),
                flags());
        Intent stopIntent = new Intent(context, TripTrackingService.class)
                .setAction(TripTrackingService.ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(context, 2, stopIntent, flags());

        Notification.Builder builder = new Notification.Builder(context, TRACKING_CHANNEL);
        builder.setContentTitle("Tripbook is recording")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    static Notification autoDetecting(Context context, String label) {
        ensureChannels(context);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                1,
                new Intent(context, MainActivity.class),
                flags());
        Intent disableIntent = new Intent(context, TripTrackingService.class)
                .setAction(TripTrackingService.ACTION_DISABLE_AUTO_DETECT);
        PendingIntent disablePendingIntent = PendingIntent.getService(context, 5, disableIntent, flags());

        Notification.Builder builder = new Notification.Builder(context, TRACKING_CHANNEL);
        builder.setContentTitle("Tripbook is watching for drives")
                .setContentText(label == null || label.isEmpty() ? "Waiting for vehicle Bluetooth." : label)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop auto-record", disablePendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    static void showAutoStartBlocked(Context context) {
        if (!canPostNotifications(context)) {
            return;
        }
        ensureChannels(context);
        Intent startIntent = new Intent(context, TripTrackingService.class)
                .setAction(TripTrackingService.ACTION_START);
        PendingIntent startPendingIntent = pendingForegroundService(context, 3, startIntent);

        Notification.Builder builder = new Notification.Builder(context, ALERT_CHANNEL);
        builder.setContentTitle("Ready to record this drive")
                .setContentText("Tap to start trip recording.")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentIntent(startPendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_media_play, "Start", startPendingIntent);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(AUTOSTART_BLOCKED_ID, builder.build());
        }
    }

    static void showResumeTripRequired(Context context) {
        if (!canPostNotifications(context)) {
            return;
        }
        ensureChannels(context);
        Intent startIntent = new Intent(context, TripTrackingService.class)
                .setAction(TripTrackingService.ACTION_START);
        PendingIntent startPendingIntent = pendingForegroundService(context, 4, startIntent);

        Notification.Builder builder = new Notification.Builder(context, ALERT_CHANNEL);
        builder.setContentTitle("Tripbook needs attention")
                .setContentText("Tap to resume background trip recording.")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentIntent(startPendingIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_media_play, "Resume", startPendingIntent);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(RESUME_REQUIRED_ID, builder.build());
        }
    }

    static void showTripNeedsReview(Context context) {
        if (!canPostNotifications(context)) {
            return;
        }
        ensureChannels(context);
        Intent reviewIntent = new Intent(context, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent reviewPendingIntent = PendingIntent.getActivity(context, 6, reviewIntent, flags());

        Notification.Builder builder = new Notification.Builder(context, REVIEW_CHANNEL);
        builder.setContentTitle("Trip needs review")
                .setContentText("Tap to confirm category or decline this trip.")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentIntent(reviewPendingIntent)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(android.R.drawable.ic_menu_edit, "Review", reviewPendingIntent);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(TRIP_REVIEW_ID, builder.build());
        }
    }

    static PendingIntent pendingForegroundService(Context context, int requestCode, Intent intent) {
        return PendingIntent.getForegroundService(context, requestCode, intent, flags());
    }

    private static boolean canPostNotifications(Context context) {
        return AppPermissions.hasNotificationPermission(context);
    }

    private static int flags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }
}
