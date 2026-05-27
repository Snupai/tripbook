package com.snupai.tripbook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class MapPickerActivity extends Activity {
    static final String EXTRA_LAT = "lat";
    static final String EXTRA_LNG = "lng";

    private static final double DEFAULT_LAT = 52.5200;
    private static final double DEFAULT_LNG = 13.4050;

    private double pickedLat = DEFAULT_LAT;
    private double pickedLng = DEFAULT_LNG;
    private TextView coordinateText;
    private LinearLayout header;
    private LinearLayout footer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickedLat = getIntent().getDoubleExtra(EXTRA_LAT, DEFAULT_LAT);
        pickedLng = getIntent().getDoubleExtra(EXTRA_LNG, DEFAULT_LNG);
        buildLayout();
    }

    private void buildLayout() {
        applySystemBars();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.app_bg));

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(10));
        header.setBackgroundColor(getColorCompat(R.color.app_bg));
        header.addView(text("Pick place", 22, getColorCompat(R.color.ink), Typeface.BOLD));
        header.addView(text("Tap the map or drag the marker", 14, getColorCompat(R.color.muted), Typeface.NORMAL));
        root.addView(header, fullWidthLayout());

        WebView map = new WebView(this);
        configureMap(map);
        root.addView(map, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(18), dp(10), dp(18), dp(12));
        footer.setBackgroundColor(getColorCompat(R.color.surface));
        coordinateText = text("", 13, getColorCompat(R.color.muted), Typeface.NORMAL);
        updateCoordinateText();
        footer.addView(coordinateText, fullWidthLayout());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = secondaryButton("Cancel");
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel, halfButtonLayout(true));
        Button use = primaryButton("Use this place");
        use.setOnClickListener(v -> finishWithSelection());
        buttons.addView(use, halfButtonLayout(false));
        footer.addView(buttons, topMarginLayout(10));
        root.addView(footer, fullWidthLayout());

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            header.setPadding(dp(18), insets.getSystemWindowInsetTop() + dp(12), dp(18), dp(10));
            footer.setPadding(dp(18), dp(10), dp(18), insets.getSystemWindowInsetBottom() + dp(12));
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureMap(WebView map) {
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        map.addJavascriptInterface(new PickerBridge(), "AndroidPicker");
        map.loadDataWithBaseURL(
                "https://tile.openstreetmap.org/",
                mapHtml(pickedLat, pickedLng),
                "text/html",
                "UTF-8",
                null);
    }

    private String mapHtml(double lat, double lng) {
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<style>html,body,#map{height:100%;margin:0;background:#101412;} .leaflet-control-attribution{font-size:10px;}</style>"
                + "</head><body><div id=\"map\"></div>"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<script>"
                + "var picked=[" + String.format(Locale.US, "%.7f", lat) + "," + String.format(Locale.US, "%.7f", lng) + "];"
                + "var map=L.map('map',{zoomControl:true}).setView(picked,15);"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);"
                + "var marker=L.marker(picked,{draggable:true}).addTo(map);"
                + "function setPicked(lat,lng){picked=[lat,lng];marker.setLatLng(picked);AndroidPicker.setPicked(lat,lng);}"
                + "map.on('click',function(e){setPicked(e.latlng.lat,e.latlng.lng);});"
                + "marker.on('dragend',function(){var p=marker.getLatLng();setPicked(p.lat,p.lng);});"
                + "AndroidPicker.setPicked(picked[0],picked[1]);"
                + "</script></body></html>";
    }

    private void finishWithSelection() {
        Intent data = new Intent()
                .putExtra(EXTRA_LAT, pickedLat)
                .putExtra(EXTRA_LNG, pickedLng);
        setResult(RESULT_OK, data);
        finish();
    }

    private void updateCoordinateText() {
        if (coordinateText != null) {
            coordinateText.setText(String.format(Locale.US, "%.5f, %.5f", pickedLat, pickedLng));
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(getColorCompat(R.color.app_bg));
        getWindow().setNavigationBarColor(getColorCompat(R.color.surface));
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int flags = 0;
        if (!isNight) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private Button primaryButton(String label) {
        return styledButton(label, getColorCompat(R.color.accent), getColorCompat(R.color.on_accent));
    }

    private Button secondaryButton(String label) {
        return styledButton(label, getColorCompat(R.color.surface_muted), getColorCompat(R.color.ink));
    }

    private Button styledButton(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        return button;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        view.setLetterSpacing(0);
        return view;
    }

    private LinearLayout.LayoutParams fullWidthLayout() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMarginLayout(int topDp) {
        LinearLayout.LayoutParams params = fullWidthLayout();
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams halfButtonLayout(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1);
        params.setMargins(left ? 0 : dp(6), 0, left ? dp(6) : 0, 0);
        return params;
    }

    private int getColorCompat(int colorRes) {
        return getColor(colorRes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class PickerBridge {
        @JavascriptInterface
        public void setPicked(double lat, double lng) {
            runOnUiThread(() -> {
                pickedLat = lat;
                pickedLng = lng;
                updateCoordinateText();
            });
        }
    }
}
