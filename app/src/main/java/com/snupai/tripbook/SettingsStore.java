package com.snupai.tripbook;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class SettingsStore {
    private static final String PREFS = "tripbook_settings";
    private static final String KEY_AUTO_RECORD = "auto_record";
    private static final String KEY_MOTION_AUTO_RECORD = "motion_auto_record";
    private static final String KEY_CAR_NAME = "car_name";
    private static final String KEY_CAR_ADDRESS = "car_address";
    private static final String KEY_DEFAULT_CATEGORY = "default_category";
    private static final String KEY_DEFAULT_VEHICLE_ID = "default_vehicle_id";
    private static final String KEY_LOCATION_DISCLOSURE_ACCEPTED = "location_disclosure_accepted";
    private static final String KEY_LAST_BLUETOOTH_EVENT = "last_bluetooth_event";
    private static final String KEY_LAST_BLUETOOTH_EVENT_AT = "last_bluetooth_event_at";
    private static final String KEY_WORK_HOURS_ENABLED = "work_hours_enabled";
    private static final String KEY_WORK_WEEKDAY_MASK = "work_weekday_mask";
    private static final String KEY_WORK_START_MINUTES = "work_start_minutes";
    private static final String KEY_WORK_END_MINUTES = "work_end_minutes";
    private static final String KEY_CATEGORY_RATES = "category_rates";
    private static final String KEY_ODOMETER_PROMPT_AT_PREFIX = "odometer_prompt_at_";
    private static final String KEY_DISTANCE_UNIT = "distance_unit";
    private static final long DUPLICATE_BLUETOOTH_EVENT_MS = 10_000L;
    private static final int DEFAULT_WORK_START_MINUTES = 9 * 60;
    private static final int DEFAULT_WORK_END_MINUTES = 17 * 60;

    private final SharedPreferences prefs;

    SettingsStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean autoRecordEnabled() {
        return prefs.getBoolean(KEY_AUTO_RECORD, false);
    }

    void setAutoRecordEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply();
    }

    boolean motionAutoRecordEnabled() {
        return prefs.getBoolean(KEY_MOTION_AUTO_RECORD, false);
    }

    void setMotionAutoRecordEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MOTION_AUTO_RECORD, enabled).apply();
    }

    String defaultCategory() {
        return prefs.getString(KEY_DEFAULT_CATEGORY, TripCategory.UNCATEGORIZED);
    }

    long defaultVehicleId() {
        return prefs.getLong(KEY_DEFAULT_VEHICLE_ID, -1L);
    }

    void setDefaultVehicleId(long vehicleId) {
        prefs.edit().putLong(KEY_DEFAULT_VEHICLE_ID, vehicleId).apply();
    }

    boolean locationDisclosureAccepted() {
        return prefs.getBoolean(KEY_LOCATION_DISCLOSURE_ACCEPTED, false);
    }

    void setLocationDisclosureAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_LOCATION_DISCLOSURE_ACCEPTED, accepted).apply();
    }

    String carName() {
        return prefs.getString(KEY_CAR_NAME, "");
    }

    String carAddress() {
        return prefs.getString(KEY_CAR_ADDRESS, "");
    }

    boolean shouldHandleBluetoothEvent(String action, String deviceKey, long nowMillis) {
        String key = (action == null ? "" : action) + "|" + (deviceKey == null ? "" : deviceKey);
        String lastKey = prefs.getString(KEY_LAST_BLUETOOTH_EVENT, "");
        long lastAt = prefs.getLong(KEY_LAST_BLUETOOTH_EVENT_AT, 0);
        if (key.equals(lastKey) && nowMillis - lastAt >= 0 && nowMillis - lastAt < DUPLICATE_BLUETOOTH_EVENT_MS) {
            return false;
        }
        prefs.edit()
                .putString(KEY_LAST_BLUETOOTH_EVENT, key)
                .putLong(KEY_LAST_BLUETOOTH_EVENT_AT, nowMillis)
                .apply();
        return true;
    }

    boolean workHoursEnabled() {
        return prefs.getBoolean(KEY_WORK_HOURS_ENABLED, false);
    }

    int workWeekdayMask() {
        return prefs.getInt(KEY_WORK_WEEKDAY_MASK, WorkHoursRule.WEEKDAYS);
    }

    int workStartMinutes() {
        return prefs.getInt(KEY_WORK_START_MINUTES, DEFAULT_WORK_START_MINUTES);
    }

    int workEndMinutes() {
        return prefs.getInt(KEY_WORK_END_MINUTES, DEFAULT_WORK_END_MINUTES);
    }

    void setWorkHours(boolean enabled, int weekdayMask, int startMinutes, int endMinutes) {
        prefs.edit()
                .putBoolean(KEY_WORK_HOURS_ENABLED, enabled)
                .putInt(KEY_WORK_WEEKDAY_MASK, weekdayMask)
                .putInt(KEY_WORK_START_MINUTES, startMinutes)
                .putInt(KEY_WORK_END_MINUTES, endMinutes)
                .apply();
    }

    String workHoursCategory(long timeMillis) {
        return WorkHoursRule.categoryFor(
                timeMillis,
                workHoursEnabled(),
                workWeekdayMask(),
                workStartMinutes(),
                workEndMinutes(),
                TimeZone.getDefault());
    }

    String distanceUnitPreference() {
        return DistanceUnit.normalize(prefs.getString(KEY_DISTANCE_UNIT, DistanceUnit.METRIC));
    }

    String resolvedDistanceUnit() {
        return DistanceUnit.resolve(distanceUnitPreference());
    }

    void setDistanceUnitPreference(String unit) {
        prefs.edit().putString(KEY_DISTANCE_UNIT, DistanceUnit.normalize(unit)).apply();
    }

    long lastOdometerPromptAt(long vehicleId) {
        if (vehicleId <= 0) {
            return 0;
        }
        return prefs.getLong(KEY_ODOMETER_PROMPT_AT_PREFIX + vehicleId, 0);
    }

    void setLastOdometerPromptAt(long vehicleId, long timeMillis) {
        if (vehicleId <= 0) {
            return;
        }
        prefs.edit().putLong(KEY_ODOMETER_PROMPT_AT_PREFIX + vehicleId, timeMillis).apply();
    }

    Map<String, Double> categoryRates() {
        LinkedHashMap<String, Double> rates = new LinkedHashMap<>();
        String raw = prefs.getString(KEY_CATEGORY_RATES, "");
        if (raw == null || raw.isEmpty()) {
            return rates;
        }
        String[] rows = raw.split("\n");
        for (String row : rows) {
            int separator = row.indexOf('=');
            if (separator <= 0 || separator >= row.length() - 1) {
                continue;
            }
            String category = decode(row.substring(0, separator));
            double rate = parseRate(row.substring(separator + 1));
            if (!category.isEmpty() && rate > 0) {
                rates.put(category, rate);
            }
        }
        return rates;
    }

    void setCategoryRate(String category, Double rate) {
        LinkedHashMap<String, Double> rates = new LinkedHashMap<>(categoryRates());
        String normalized = TripCategory.normalize(category);
        if (rate == null || rate <= 0) {
            rates.remove(normalized);
        } else {
            rates.put(normalized, rate);
        }
        setCategoryRates(rates);
    }

    void setCategoryRates(Map<String, Double> rates) {
        StringBuilder raw = new StringBuilder();
        if (rates != null) {
            for (Map.Entry<String, Double> entry : rates.entrySet()) {
                String category = TripCategory.normalize(entry.getKey());
                Double rate = entry.getValue();
                if (category.isEmpty() || rate == null || rate <= 0) {
                    continue;
                }
                raw.append(encode(category))
                        .append('=')
                        .append(String.format(Locale.US, "%.6f", rate))
                        .append('\n');
            }
        }
        prefs.edit().putString(KEY_CATEGORY_RATES, raw.toString()).apply();
    }

    private static double parseRate(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException exception) {
            return "";
        }
    }

    private static String decode(String value) {
        try {
            return TripCategory.normalize(URLDecoder.decode(value, "UTF-8"));
        } catch (UnsupportedEncodingException | IllegalArgumentException exception) {
            return "";
        }
    }
}
