package com.snupai.tripbook;

final class VehicleRecord {
    long id;
    String name;
    String brand;
    String model;
    boolean odometerPromptEnabled;
    String bluetoothName;
    String bluetoothAddress;

    String label() {
        String display = (clean(brand) + " " + clean(model)).trim();
        if (!display.isEmpty()) {
            return display;
        }
        String fallback = clean(name);
        return fallback.isEmpty() ? "Vehicle" : fallback;
    }

    boolean hasBluetooth() {
        return !clean(bluetoothName).isEmpty() || !clean(bluetoothAddress).isEmpty();
    }

    String bluetoothLabel() {
        String nameLabel = clean(bluetoothName);
        String addressLabel = clean(bluetoothAddress);
        if (!nameLabel.isEmpty() && !addressLabel.isEmpty()) {
            return nameLabel + " / " + addressLabel;
        }
        if (!nameLabel.isEmpty()) {
            return nameLabel;
        }
        if (!addressLabel.isEmpty()) {
            return addressLabel;
        }
        return "No Bluetooth";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
