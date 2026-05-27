package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public final class WorkHoursRuleTest {
    private final TimeZone utc = TimeZone.getTimeZone("UTC");

    @Test
    public void disabledRuleReturnsNull() {
        assertNull(WorkHoursRule.categoryFor(millis(2026, Calendar.MAY, 25, 10, 0),
                false,
                WorkHoursRule.WEEKDAYS,
                9 * 60,
                17 * 60,
                utc));
    }

    @Test
    public void workWindowReturnsBusiness() {
        assertEquals(TripCategory.BUSINESS,
                WorkHoursRule.categoryFor(millis(2026, Calendar.MAY, 25, 10, 0),
                        true,
                        WorkHoursRule.WEEKDAYS,
                        9 * 60,
                        17 * 60,
                        utc));
    }

    @Test
    public void outsideWorkWindowReturnsPersonal() {
        assertEquals(TripCategory.PERSONAL,
                WorkHoursRule.categoryFor(millis(2026, Calendar.MAY, 25, 20, 0),
                        true,
                        WorkHoursRule.WEEKDAYS,
                        9 * 60,
                        17 * 60,
                        utc));
    }

    @Test
    public void weekendReturnsPersonalWhenNotInMask() {
        assertEquals(TripCategory.PERSONAL,
                WorkHoursRule.categoryFor(millis(2026, Calendar.MAY, 24, 10, 0),
                        true,
                        WorkHoursRule.WEEKDAYS,
                        9 * 60,
                        17 * 60,
                        utc));
    }

    private long millis(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(utc);
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
