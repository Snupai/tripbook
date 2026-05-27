package com.snupai.tripbook;

import java.util.Calendar;
import java.util.TimeZone;

final class WorkHoursRule {
    static final int MONDAY = 1 << 0;
    static final int TUESDAY = 1 << 1;
    static final int WEDNESDAY = 1 << 2;
    static final int THURSDAY = 1 << 3;
    static final int FRIDAY = 1 << 4;
    static final int SATURDAY = 1 << 5;
    static final int SUNDAY = 1 << 6;
    static final int WEEKDAYS = MONDAY | TUESDAY | WEDNESDAY | THURSDAY | FRIDAY;

    private WorkHoursRule() {
    }

    static String categoryFor(long timeMillis, boolean enabled, int weekdayMask,
                              int startMinutes, int endMinutes, TimeZone timeZone) {
        if (!enabled || !isValidWindow(startMinutes, endMinutes)) {
            return null;
        }
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(timeMillis);
        int dayBit = dayBit(calendar.get(Calendar.DAY_OF_WEEK));
        int minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        boolean workDay = (weekdayMask & dayBit) != 0;
        boolean inWindow = minuteOfDay >= startMinutes && minuteOfDay < endMinutes;
        return workDay && inWindow ? TripCategory.BUSINESS : TripCategory.PERSONAL;
    }

    static boolean isValidWindow(int startMinutes, int endMinutes) {
        return startMinutes >= 0
                && startMinutes < 24 * 60
                && endMinutes > 0
                && endMinutes <= 24 * 60
                && startMinutes < endMinutes;
    }

    private static int dayBit(int calendarDay) {
        switch (calendarDay) {
            case Calendar.MONDAY:
                return MONDAY;
            case Calendar.TUESDAY:
                return TUESDAY;
            case Calendar.WEDNESDAY:
                return WEDNESDAY;
            case Calendar.THURSDAY:
                return THURSDAY;
            case Calendar.FRIDAY:
                return FRIDAY;
            case Calendar.SATURDAY:
                return SATURDAY;
            case Calendar.SUNDAY:
            default:
                return SUNDAY;
        }
    }
}
