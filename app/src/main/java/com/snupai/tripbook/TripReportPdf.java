package com.snupai.tripbook;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

final class TripReportPdf {
    private static final int PAGE_WIDTH = 612;
    private static final int PAGE_HEIGHT = 792;
    private static final int MARGIN = 42;
    private static final int LINE_HEIGHT = 16;

    private TripReportPdf() {
    }

    static byte[] render(TripReport report) throws IOException {
        Writer writer = new Writer(report);
        return writer.render();
    }

    private static final class Writer {
        private final TripReport report;
        private final PdfDocument document = new PdfDocument();
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Canvas canvas;
        private int pageNumber;
        private int y;

        private Writer(TripReport report) {
            this.report = report;
            titlePaint.setTextSize(20);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            headingPaint.setTextSize(12);
            headingPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            bodyPaint.setTextSize(10);
            bodyPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        }

        private byte[] render() throws IOException {
            startPage();
            line("Trip Report", titlePaint);
            line("Date range: " + TimeFormat.csvDateTime(report.startMillis)
                    + " to " + TimeFormat.csvDateTime(report.endMillis), bodyPaint);
            line("Trips: " + report.trips.size()
                    + "    " + DistanceUnit.distanceName(report.distanceUnit) + ": " + decimal(report.totalDistance)
                    + "    Amount: " + decimal(report.totalAmount), bodyPaint);
            blank();

            section("Category totals");
            for (TripReport.Total total : report.categoryTotals) {
                line(total.label + "    trips " + total.tripCount
                        + "    " + DistanceUnit.shortLabel(report.distanceUnit) + " " + decimal(total.distance)
                        + (total.rate > 0 ? "    rate " + decimal(total.rate) + "    amount " + decimal(total.amount) : ""),
                        bodyPaint);
            }
            blank();

            section("Vehicle totals");
            for (TripReport.Total total : report.vehicleTotals) {
                line(total.label + "    trips " + total.tripCount
                        + "    " + DistanceUnit.shortLabel(report.distanceUnit) + " " + decimal(total.distance), bodyPaint);
            }
            blank();

            section("Trips");
            for (TripRecord trip : report.trips) {
                line(TimeFormat.csvDateTime(trip.startTime)
                        + "  " + GeoMath.distanceLabel(trip.distanceMeters, report.distanceUnit)
                        + "  " + TripCategory.normalize(trip.category)
                        + "  " + trip.reviewLabel()
                        + "  " + AutoStartSource.label(trip.autoStartSource), bodyPaint);
                line("Vehicle: " + safe(trip.vehicleName)
                        + "    Odo: " + optional(trip.startOdometer) + " -> " + optional(trip.endOdometer), bodyPaint);
                line("Start: " + GeoMath.coordinateLabel(trip.startLat, trip.startLng)
                        + "    End: " + GeoMath.coordinateLabel(trip.endLat, trip.endLng), bodyPaint);
                if (trip.notes != null && !trip.notes.trim().isEmpty()) {
                    line("Notes: " + trip.notes.trim(), bodyPaint);
                }
                blank();
            }

            document.finishPage(currentPage);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.writeTo(output);
            document.close();
            return output.toByteArray();
        }

        private PdfDocument.Page currentPage;

        private void startPage() {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, ++pageNumber).create();
            currentPage = document.startPage(info);
            canvas = currentPage.getCanvas();
            y = MARGIN;
        }

        private void section(String text) {
            ensureSpace(LINE_HEIGHT * 2);
            line(text, headingPaint);
        }

        private void blank() {
            y += LINE_HEIGHT / 2;
        }

        private void line(String text, Paint paint) {
            ensureSpace(LINE_HEIGHT);
            canvas.drawText(ellipsize(text, paint), MARGIN, y, paint);
            y += LINE_HEIGHT;
        }

        private void ensureSpace(int needed) {
            if (y + needed <= PAGE_HEIGHT - MARGIN) {
                return;
            }
            document.finishPage(currentPage);
            startPage();
        }

        private String ellipsize(String text, Paint paint) {
            String value = text == null ? "" : text;
            int maxWidth = PAGE_WIDTH - MARGIN * 2;
            if (paint.measureText(value) <= maxWidth) {
                return value;
            }
            while (value.length() > 3 && paint.measureText(value + "...") > maxWidth) {
                value = value.substring(0, value.length() - 1);
            }
            return value + "...";
        }

        private String safe(String value) {
            return value == null || value.trim().isEmpty() ? "No vehicle" : value.trim();
        }

        private String optional(Double value) {
            return value == null ? "" : decimal(value);
        }

        private String decimal(double value) {
            return String.format(Locale.US, "%.2f", value);
        }
    }
}
