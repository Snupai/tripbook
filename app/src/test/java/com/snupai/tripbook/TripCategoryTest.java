package com.snupai.tripbook;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TripCategoryTest {
    @Test
    public void indexOfFindsKnownCategory() {
        assertEquals(0, TripCategory.indexOf(TripCategory.BUSINESS));
    }

    @Test
    public void indexOfDefaultsUnknownToUncategorized() {
        assertEquals(TripCategory.ALL.length - 1, TripCategory.indexOf("Missing"));
        assertEquals(TripCategory.ALL.length - 1, TripCategory.indexOf(null));
    }

    @Test
    public void normalizeKeepsCustomCategories() {
        assertEquals("School", TripCategory.normalize(" School "));
        assertEquals(TripCategory.UNCATEGORIZED, TripCategory.normalize(""));
        assertEquals(TripCategory.UNCATEGORIZED, TripCategory.normalize(null));
    }
}
