package com.snupai.tripbook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class ReportFileProvider extends ContentProvider {
    private static final String DIR = "shared_reports";

    static File reportFile(Context context, String name) {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, sanitize(name));
    }

    static Uri uriFor(Context context, String name) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".reports")
                .appendPath(sanitize(name))
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name != null && name.endsWith(".csv")) {
            return "text/csv";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Read-only provider");
        }
        File file = fileFor(uri);
        if (!file.exists()) {
            throw new FileNotFoundException(file.getName());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File fileFor(Uri uri) {
        Context context = getContext();
        String name = uri.getLastPathSegment();
        return reportFile(context, name == null ? "report" : name);
    }

    private static String sanitize(String name) {
        String raw = name == null || name.trim().isEmpty() ? "report" : name.trim();
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
