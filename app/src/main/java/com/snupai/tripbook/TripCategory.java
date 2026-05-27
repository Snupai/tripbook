package com.snupai.tripbook;

final class TripCategory {
    static final String UNCATEGORIZED = "Uncategorized";
    static final String BUSINESS = "Business";
    static final String PERSONAL = "Personal";
    static final String COMMUTE = "Commute";
    static final String MEDICAL = "Medical";
    static final String CHARITY = "Charity";
    static final String OTHER = "Other";

    static final String[] ALL = {
            BUSINESS,
            PERSONAL,
            COMMUTE,
            MEDICAL,
            CHARITY,
            OTHER,
            UNCATEGORIZED
    };

    private TripCategory() {
    }

    static int indexOf(String category) {
        if (category == null) {
            return ALL.length - 1;
        }
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(category)) {
                return i;
            }
        }
        return ALL.length - 1;
    }

    static String normalize(String category) {
        if (category == null) {
            return UNCATEGORIZED;
        }
        String trimmed = category.trim();
        return trimmed.isEmpty() ? UNCATEGORIZED : trimmed;
    }
}
