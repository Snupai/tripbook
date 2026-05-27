package com.snupai.tripbook;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TimeFormat {
    private static final SimpleDateFormat SHORT = new SimpleDateFormat("MMM d, h:mm a", Locale.US);
    private static final SimpleDateFormat CSV = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private TimeFormat() {
    }

    static String shortDateTime(long millis) {
        synchronized (SHORT) {
            return SHORT.format(new Date(millis));
        }
    }

    static String csvDateTime(Long millis) {
        if (millis == null) {
            return "";
        }
        synchronized (CSV) {
            return CSV.format(new Date(millis));
        }
    }

    static String dateOnly(long millis) {
        synchronized (DATE) {
            return DATE.format(new Date(millis));
        }
    }
}
