package com.snupai.tripbook;

final class AutoStartSource {
    static final String MANUAL = "manual";
    static final String BLUETOOTH = "bluetooth";
    static final String MOTION = "motion";

    private AutoStartSource() {
    }

    static String normalize(String source, boolean autoStarted) {
        if (MOTION.equals(source)) {
            return MOTION;
        }
        if (BLUETOOTH.equals(source)) {
            return BLUETOOTH;
        }
        return autoStarted ? BLUETOOTH : MANUAL;
    }

    static String label(String source) {
        String normalized = normalize(source, false);
        if (MOTION.equals(normalized)) {
            return "Motion";
        }
        if (BLUETOOTH.equals(normalized)) {
            return "Bluetooth";
        }
        return "Manual";
    }
}
