package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CsvFormatTest {
    @Test
    public void cellQuotesAndEscapesText() {
        assertEquals("\"hello\"", CsvFormat.cell("hello"));
        assertEquals("\"a,b\"", CsvFormat.cell("a,b"));
        assertEquals("\"said \"\"hi\"\"\"", CsvFormat.cell("said \"hi\""));
    }

    @Test
    public void cellLeavesNullEmpty() {
        assertEquals("", CsvFormat.cell(null));
    }
}
