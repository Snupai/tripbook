package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TimeFormatTest {
    @Test
    public void csvDateTimeLeavesNullEmpty() {
        assertEquals("", TimeFormat.csvDateTime(null));
    }
}
