package com.snupai.tripbook;

final class CsvFormat {
    private CsvFormat() {
    }

    static String cell(String raw) {
        if (raw == null) {
            return "";
        }
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}
