package com.snupai.tripbook;

import java.util.Locale;

final class TripReportCsv {
    private TripReportCsv() {
    }

    static String render(TripReport report) {
        StringBuilder csv = new StringBuilder();
        String distanceName = DistanceUnit.distanceName(report.distanceUnit);
        csv.append("Start,End,Category,Reviewed,Distance ").append(distanceName)
                .append(",Rate,Amount,Vehicle,Start Odometer,End Odometer,Start Latitude,Start Longitude,End Latitude,End Longitude,Notes,Auto Start Source\n");
        for (TripRecord trip : report.trips) {
            double distance = DistanceUnit.fromMeters(trip.distanceMeters, report.distanceUnit);
            double rate = TripReport.rateFor(null, trip.category);
            csv.append(CsvFormat.cell(TimeFormat.csvDateTime(trip.startTime))).append(',')
                    .append(CsvFormat.cell(TimeFormat.csvDateTime(trip.endTime))).append(',')
                    .append(CsvFormat.cell(trip.category)).append(',')
                    .append(trip.reviewed ? "yes" : "no").append(',')
                    .append(decimal(distance)).append(',')
                    .append(rateCell(report, trip.category)).append(',')
                    .append(amountCell(report, trip.category, distance)).append(',')
                    .append(CsvFormat.cell(trip.vehicleName)).append(',')
                    .append(numberCell(trip.startOdometer)).append(',')
                    .append(numberCell(trip.endOdometer)).append(',')
                    .append(trip.startLat == null ? "" : coordinate(trip.startLat)).append(',')
                    .append(trip.startLng == null ? "" : coordinate(trip.startLng)).append(',')
                    .append(trip.endLat == null ? "" : coordinate(trip.endLat)).append(',')
                    .append(trip.endLng == null ? "" : coordinate(trip.endLng)).append(',')
                    .append(CsvFormat.cell(trip.notes)).append(',')
                    .append(CsvFormat.cell(AutoStartSource.label(trip.autoStartSource)))
                    .append('\n');
        }

        csv.append('\n').append("Category Totals\n");
        csv.append("Category,Trips,Distance ").append(distanceName).append(",Rate,Amount\n");
        for (TripReport.Total total : report.categoryTotals) {
            csv.append(CsvFormat.cell(total.label)).append(',')
                    .append(total.tripCount).append(',')
                    .append(decimal(total.distance)).append(',')
                    .append(total.rate > 0 ? decimal(total.rate) : "").append(',')
                    .append(total.amount > 0 ? decimal(total.amount) : "")
                    .append('\n');
        }

        csv.append('\n').append("Vehicle Totals\n");
        csv.append("Vehicle,Trips,Distance ").append(distanceName).append('\n');
        for (TripReport.Total total : report.vehicleTotals) {
            csv.append(CsvFormat.cell(total.label)).append(',')
                    .append(total.tripCount).append(',')
                    .append(decimal(total.distance))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String rateCell(TripReport report, String category) {
        for (TripReport.Total total : report.categoryTotals) {
            if (total.label.equals(TripCategory.normalize(category)) && total.rate > 0) {
                return decimal(total.rate);
            }
        }
        return "";
    }

    private static String amountCell(TripReport report, String category, double distance) {
        for (TripReport.Total total : report.categoryTotals) {
            if (total.label.equals(TripCategory.normalize(category)) && total.rate > 0) {
                return decimal(distance * total.rate);
            }
        }
        return "";
    }

    private static String numberCell(Double value) {
        return value == null ? "" : decimal(value);
    }

    private static String coordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
