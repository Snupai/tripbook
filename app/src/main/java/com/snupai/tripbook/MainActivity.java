package com.snupai.tripbook;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_BASIC_PERMISSIONS = 40;
    private static final int REQ_BACKGROUND_LOCATION = 41;
    private static final int REQ_CREATE_DOCUMENT = 42;
    private static final int REQ_PICK_PLACE = 43;
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private static final String STATE_PENDING_EDIT_PLACE_ID = "pending_edit_place_id";
    private static final String STATE_NEEDS_REVIEW_ONLY = "needs_review_only";
    static final String ACTION_SHOW_REVIEW = "com.snupai.tripbook.action.SHOW_REVIEW";
    private static final int TAB_TRACK = 0;
    private static final int TAB_TRIPS = 1;
    private static final int TAB_PLACES = 2;
    private static final int TAB_SETTINGS = 3;
    private static final long ODOMETER_REMINDER_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L;

    private final BroadcastReceiver tripUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    private TripRepository repository;
    private SettingsStore settings;
    private Geocoder geocoder;
    private final ExecutorService geocodeExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, String> addressCache = new LinkedHashMap<>();
    private final Set<String> pendingAddressLookups = new LinkedHashSet<>();
    private LinearLayout appHeader;
    private FrameLayout bodyContainer;
    private LinearLayout bottomNav;
    private LinearLayout content;
    private boolean receiverRegistered;
    private byte[] pendingDocument;
    private String pendingDocumentSuccess;
    private int selectedTab = TAB_TRACK;
    private int lastRenderedTab = TAB_TRACK;
    private boolean animateNextBody;
    private long pendingEditPlaceId = -1;
    private boolean needsReviewOnly = true;

    private interface BluetoothSelectionHandler {
        void onSelected(String name, String address);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(STATE_SELECTED_TAB, TAB_TRACK);
            lastRenderedTab = selectedTab;
            pendingEditPlaceId = savedInstanceState.getLong(STATE_PENDING_EDIT_PLACE_ID, -1);
            needsReviewOnly = savedInstanceState.getBoolean(STATE_NEEDS_REVIEW_ONLY, true);
        }
        repository = new TripRepository(this);
        settings = new SettingsStore(this);
        geocoder = Geocoder.isPresent() ? new Geocoder(this, Locale.getDefault()) : null;
        TripNotifications.ensureChannels(this);
        handleNavigationIntent(getIntent());
        buildRoot();
        render();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TripTrackingService.ACTION_TRIP_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tripUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tripUpdateReceiver, filter);
        }
        receiverRegistered = true;
        syncPersistentTrackingService();
        render();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(tripUpdateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        geocodeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab);
        outState.putLong(STATE_PENDING_EDIT_PLACE_ID, pendingEditPlaceId);
        outState.putBoolean(STATE_NEEDS_REVIEW_ONLY, needsReviewOnly);
        super.onSaveInstanceState(outState);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null || !ACTION_SHOW_REVIEW.equals(intent.getAction())) {
            return;
        }
        boolean changed = selectedTab != TAB_TRIPS || !needsReviewOnly;
        selectedTab = TAB_TRIPS;
        needsReviewOnly = true;
        if (bodyContainer != null && changed) {
            animateNextBody = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CREATE_DOCUMENT) {
            if (requestCode == REQ_PICK_PLACE && resultCode == RESULT_OK && data != null) {
                Location picked = new Location("map");
                picked.setLatitude(data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0));
                picked.setLongitude(data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0));
                if (pendingEditPlaceId >= 0) {
                    PlaceRule place = findPlace(pendingEditPlaceId);
                    pendingEditPlaceId = -1;
                    if (place != null) {
                        showPlaceDialog(place, picked);
                        return;
                    }
                }
                showAddPlaceDialog(picked);
            }
            pendingEditPlaceId = -1;
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            clearPendingDocument();
            return;
        }
        writePendingDocument(data.getData());
    }

    private void buildRoot() {
        applySystemBars();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.app_bg));

        appHeader = new LinearLayout(this);
        appHeader.setOrientation(LinearLayout.VERTICAL);
        appHeader.setBackgroundColor(getColorCompat(R.color.app_bg));
        appHeader.setPadding(dp(18), dp(14), dp(18), dp(8));
        root.addView(appHeader, fullWidthLayout());

        bodyContainer = new FrameLayout(this);
        bodyContainer.setBackgroundColor(getColorCompat(R.color.app_bg));
        root.addView(bodyContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setBackgroundColor(getColorCompat(R.color.surface));
        bottomNav.setPadding(dp(12), dp(7), dp(12), dp(7));
        root.addView(bottomNav, fullWidthLayout());

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            appHeader.setPadding(dp(18), top + dp(12), dp(18), dp(8));
            bottomNav.setPadding(dp(12), dp(7), dp(12), bottom + dp(7));
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    private void render() {
        if (bodyContainer == null || appHeader == null || bottomNav == null) {
            return;
        }
        renderHeader();
        renderBottomNav();
        bodyContainer.removeAllViews();
        int tabToRender = selectedTab;
        int animationDirection = tabToRender >= lastRenderedTab ? 1 : -1;
        boolean animateBody = animateNextBody;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(getColorCompat(R.color.app_bg));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(20));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        bodyContainer.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        content.removeAllViews();
        if (selectedTab == TAB_TRIPS) {
            addScreenTitle("Trips", "Review and export trip records");
            addTripHistoryCard();
        } else if (selectedTab == TAB_PLACES) {
            addScreenTitle("Places", "Auto-categorize common start and end points");
            addPlacesCard();
        } else if (selectedTab == TAB_SETTINGS) {
            addScreenTitle("Settings", "Permissions, vehicles, rates, and auto-record");
            addPermissionCard();
            addAutoRecordCard();
            addUnitsCard();
            addWorkHoursCard();
            addVehicleCard();
            addRatesCard();
        } else {
            addTrackCard();
            addAutoRecordStatusCard();
        }
        if (animateBody) {
            animateTabContent(scrollView, animationDirection);
        }
        lastRenderedTab = tabToRender;
        animateNextBody = false;
    }

    private void renderHeader() {
        appHeader.removeAllViews();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Tripbook", 22, getColorCompat(R.color.ink), Typeface.BOLD));
        labels.addView(text("Private trip tracking for tax records", 12, getColorCompat(R.color.muted), Typeface.NORMAL));
        top.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TripRecord active = repository.getActiveTrip();
        TextView status = text(active == null ? "Ready" : GeoMath.distanceLabel(active.distanceMeters, activeDistanceUnit()),
                13,
                active == null ? getColorCompat(R.color.muted) : getColorCompat(R.color.accent_text),
                Typeface.BOLD);
        status.setGravity(Gravity.END);
        top.addView(status);
        appHeader.addView(top, fullWidthLayout());
    }

    private void renderBottomNav() {
        bottomNav.removeAllViews();
        bottomNav.addView(tabButton("Track", TAB_TRACK), tabLayout());
        bottomNav.addView(tabButton("Trips", TAB_TRIPS), tabLayout());
        bottomNav.addView(tabButton("Places", TAB_PLACES), tabLayout());
        bottomNav.addView(tabButton("Settings", TAB_SETTINGS), tabLayout());
    }

    private void addScreenTitle(String title, String subtitle) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(0, 0, 0, dp(2));
        heading.addView(text(title, 20, getColorCompat(R.color.ink), Typeface.BOLD));
        heading.addView(text(subtitle, 13, getColorCompat(R.color.muted), Typeface.NORMAL));
        content.addView(heading, fullWidthLayout());
    }

    private void addTrackCard() {
        LinearLayout card = card();
        TripRecord active = repository.getActiveTrip();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Current trip", 13, getColorCompat(R.color.muted), Typeface.NORMAL));
        if (active == null) {
            labels.addView(text("Ready to record", 21, getColorCompat(R.color.ink), Typeface.BOLD));
            top.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            card.addView(top);
            addTopMargin(card, text("No active trip is running on this phone.", 14, getColorCompat(R.color.muted), Typeface.NORMAL), 4);
            Button start = primaryButton("Start trip");
            start.setOnClickListener(v -> startTrip(false));
            card.addView(start, buttonLayout());
        } else {
            labels.addView(text("Recording", 21, getColorCompat(R.color.accent_text), Typeface.BOLD));
            labels.addView(text(active.title(), 14, getColorCompat(R.color.muted), Typeface.NORMAL));
            top.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView distance = text(GeoMath.distanceLabel(active.distanceMeters, activeDistanceUnit()), 28, getColorCompat(R.color.ink), Typeface.BOLD);
            distance.setGravity(Gravity.END);
            top.addView(distance);
            card.addView(top);

            Button stop = warningButton("Stop trip");
            stop.setOnClickListener(v -> stopTrip());
            card.addView(stop, buttonLayout());
        }
        content.addView(card);
    }

    private void addAutoRecordStatusCard() {
        LinearLayout card = sectionCard("Auto-record", null);
        boolean bluetoothReady = bluetoothAutoRecordReady();
        boolean motionReady = motionAutoRecordReady();
        card.addView(summaryRow("Bluetooth cars", bluetoothVehicleSummary()));
        card.addView(valueRow("Bluetooth ready", bluetoothReady ? "Yes" : "No",
                bluetoothReady ? getColorCompat(R.color.accent_text) : getColorCompat(R.color.warn_text)));
        card.addView(valueRow("Motion ready", motionReady ? "Yes" : "No",
                motionReady ? getColorCompat(R.color.accent_text) : getColorCompat(R.color.warn_text)));
        card.addView(valueRow("Bluetooth auto-record", settings.autoRecordEnabled() ? "On" : "Off",
                settings.autoRecordEnabled() ? getColorCompat(R.color.accent_text) : getColorCompat(R.color.muted)));
        card.addView(valueRow("Motion auto-record", settings.motionAutoRecordEnabled() ? "On" : "Off",
                settings.motionAutoRecordEnabled() ? getColorCompat(R.color.accent_text) : getColorCompat(R.color.muted)));

        Button settingsButton = secondaryButton("Open settings");
        settingsButton.setOnClickListener(v -> {
            selectedTab = TAB_SETTINGS;
            render();
        });
        card.addView(settingsButton, buttonLayout());
        content.addView(card);
    }

    private void addPermissionCard() {
        LinearLayout card = sectionCard("Permissions", null);
        card.addView(statusRow("Privacy notice", settings.locationDisclosureAccepted()));
        card.addView(statusRow("Location", hasLocationPermission()));
        card.addView(statusRow("Background location", hasBackgroundLocationPermission()));
        card.addView(statusRow("Bluetooth", hasBluetoothPermission()));
        card.addView(statusRow("Physical activity", hasActivityRecognitionPermission()));
        card.addView(statusRow("Notifications", hasNotificationPermission()));

        Button basic = secondaryButton("Grant basics");
        basic.setOnClickListener(v -> requestBasicPermissions());

        Button background = secondaryButton("Background");
        background.setOnClickListener(v -> requestBackgroundLocation());

        Button disclosure = textButton("Privacy notice");
        disclosure.setOnClickListener(v -> showLocationDisclosure(null));

        Button appSettings = textButton("App settings");
        appSettings.setOnClickListener(v -> openAppSettings());
        card.addView(buttonRow(basic, background), buttonLayout());
        card.addView(buttonRow(disclosure, appSettings), buttonLayout());
        content.addView(card);
    }

    private void addAutoRecordCard() {
        LinearLayout card = sectionCard("Auto-record", null);
        boolean bluetoothReady = bluetoothAutoRecordReady();
        boolean motionReady = motionAutoRecordReady();

        card.addView(summaryRow("Vehicle Bluetooth", bluetoothVehicleSummary()));
        card.addView(statusRow("Bluetooth trigger", bluetoothReady));
        Switch bluetoothSwitch = new Switch(this);
        bluetoothSwitch.setText("Start when vehicle connects");
        bluetoothSwitch.setTextColor(getColorCompat(R.color.ink));
        bluetoothSwitch.setTextSize(15);
        bluetoothSwitch.setPadding(0, dp(6), 0, 0);
        bluetoothSwitch.setEnabled(bluetoothReady);
        bluetoothSwitch.setChecked(bluetoothReady && settings.autoRecordEnabled());
        bluetoothSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            if (isChecked && !bluetoothAutoRecordReady()) {
                buttonView.setChecked(false);
                Toast.makeText(this, "Finish auto-record setup first.", Toast.LENGTH_LONG).show();
                return;
            }
            settings.setAutoRecordEnabled(isChecked);
            syncPersistentTrackingService();
        });
        card.addView(bluetoothSwitch, fullWidthLayout());
        if (!bluetoothReady) {
            addTopMargin(card, text("Needs privacy notice, background location, Bluetooth, notifications, and a vehicle Bluetooth device.", 13, getColorCompat(R.color.muted), Typeface.NORMAL), 3);
        }

        addDivider(card);
        card.addView(statusRow("Motion trigger", motionReady));
        Switch motionSwitch = new Switch(this);
        motionSwitch.setText("Start when driving is detected");
        motionSwitch.setTextColor(getColorCompat(R.color.ink));
        motionSwitch.setTextSize(15);
        motionSwitch.setPadding(0, dp(6), 0, 0);
        motionSwitch.setEnabled(motionReady);
        motionSwitch.setChecked(motionReady && settings.motionAutoRecordEnabled());
        motionSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            if (isChecked && !motionAutoRecordReady()) {
                buttonView.setChecked(false);
                Toast.makeText(this, "Finish motion setup first.", Toast.LENGTH_LONG).show();
                return;
            }
            settings.setMotionAutoRecordEnabled(isChecked);
            syncPersistentTrackingService();
        });
        card.addView(motionSwitch, fullWidthLayout());
        if (!motionReady) {
            addTopMargin(card, text("Needs privacy notice, location, background location, physical activity, and notifications.", 13, getColorCompat(R.color.muted), Typeface.NORMAL), 3);
        }
        content.addView(card);
    }

    private void addUnitsCard() {
        LinearLayout card = sectionCard("Units", null);
        card.addView(summaryRow("Distance", DistanceUnit.settingLabel(settings.distanceUnitPreference())));
        Button change = secondaryButton("Change units");
        change.setOnClickListener(v -> showUnitsDialog());
        card.addView(change, buttonLayout());
        content.addView(card);
    }

    private void showUnitsDialog() {
        String[] labels = {
                "Automatic (" + DistanceUnit.displayName(DistanceUnit.AUTOMATIC) + ")",
                "Metric",
                "Imperial"
        };
        String[] values = {
                DistanceUnit.AUTOMATIC,
                DistanceUnit.METRIC,
                DistanceUnit.IMPERIAL
        };
        String selected = settings.distanceUnitPreference();
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) {
                selectedIndex = i;
                break;
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Distance units")
                .setSingleChoiceItems(labels, selectedIndex, (choiceDialog, which) -> {
                    settings.setDistanceUnitPreference(values[which]);
                    choiceDialog.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        styleAlertDialog(dialog);
    }

    private void addWorkHoursCard() {
        LinearLayout card = sectionCard("Work hours", "Auto-suggest Business during work hours and Personal outside them.");
        Switch enabled = new Switch(this);
        enabled.setText(R.string.use_work_hours);
        enabled.setTextColor(getColorCompat(R.color.ink));
        enabled.setTextSize(15);
        enabled.setChecked(settings.workHoursEnabled());
        card.addView(enabled, fullWidthLayout());

        EditText start = modernEditText("09:00", InputType.TYPE_CLASS_DATETIME);
        start.setText(minutesLabel(settings.workStartMinutes()));
        EditText end = modernEditText("17:00", InputType.TYPE_CLASS_DATETIME);
        end.setText(minutesLabel(settings.workEndMinutes()));
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.addView(formField("Start", start, null), weightedLayout(1));
        LinearLayout.LayoutParams endParams = weightedLayout(1);
        endParams.setMargins(dp(8), 0, 0, 0);
        times.addView(formField("End", end, null), endParams);
        addTopMargin(card, times, 10);

        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.VERTICAL);
        CheckBox monday = compactCheckBox("Mon", (settings.workWeekdayMask() & WorkHoursRule.MONDAY) != 0);
        CheckBox tuesday = compactCheckBox("Tue", (settings.workWeekdayMask() & WorkHoursRule.TUESDAY) != 0);
        CheckBox wednesday = compactCheckBox("Wed", (settings.workWeekdayMask() & WorkHoursRule.WEDNESDAY) != 0);
        CheckBox thursday = compactCheckBox("Thu", (settings.workWeekdayMask() & WorkHoursRule.THURSDAY) != 0);
        CheckBox friday = compactCheckBox("Fri", (settings.workWeekdayMask() & WorkHoursRule.FRIDAY) != 0);
        CheckBox saturday = compactCheckBox("Sat", (settings.workWeekdayMask() & WorkHoursRule.SATURDAY) != 0);
        CheckBox sunday = compactCheckBox("Sun", (settings.workWeekdayMask() & WorkHoursRule.SUNDAY) != 0);
        days.addView(dayRow(monday, tuesday, wednesday));
        addTopMargin(days, dayRow(thursday, friday, saturday), 6);
        addTopMargin(days, dayRow(sunday), 6);
        addTopMargin(card, formField("Work days", days, null), 10);

        Button save = secondaryButton("Save work hours");
        save.setOnClickListener(v -> {
            int startMinutes = parseMinutes(start.getText().toString());
            int endMinutes = parseMinutes(end.getText().toString());
            if (!WorkHoursRule.isValidWindow(startMinutes, endMinutes)) {
                Toast.makeText(this, "Use a valid start and end time.", Toast.LENGTH_LONG).show();
                return;
            }
            int mask = 0;
            mask |= monday.isChecked() ? WorkHoursRule.MONDAY : 0;
            mask |= tuesday.isChecked() ? WorkHoursRule.TUESDAY : 0;
            mask |= wednesday.isChecked() ? WorkHoursRule.WEDNESDAY : 0;
            mask |= thursday.isChecked() ? WorkHoursRule.THURSDAY : 0;
            mask |= friday.isChecked() ? WorkHoursRule.FRIDAY : 0;
            mask |= saturday.isChecked() ? WorkHoursRule.SATURDAY : 0;
            mask |= sunday.isChecked() ? WorkHoursRule.SUNDAY : 0;
            if (mask == 0) {
                Toast.makeText(this, "Choose at least one work day.", Toast.LENGTH_LONG).show();
                return;
            }
            settings.setWorkHours(enabled.isChecked(), mask, startMinutes, endMinutes);
            Toast.makeText(this, "Work hours saved.", Toast.LENGTH_SHORT).show();
            render();
        });
        card.addView(save, buttonLayout());
        content.addView(card);
    }

    private void addVehicleCard() {
        LinearLayout card = sectionCard("Vehicles and odometer", null);
        List<VehicleRecord> vehicles = repository.vehicles();
        long defaultVehicleId = settings.defaultVehicleId();
        card.addView(summaryRow("Default vehicle", vehicleName(defaultVehicleId)));

        Button add = secondaryButton("Add vehicle");
        add.setOnClickListener(v -> showVehicleDialog(null));
        card.addView(add, buttonLayout());

        for (VehicleRecord vehicle : vehicles) {
            addDivider(card);
            card.addView(vehicleRow(vehicle, defaultVehicleId));
        }

        List<OdometerReading> readings = repository.recentOdometerReadings(5);
        if (!readings.isEmpty()) {
            addDivider(card);
            card.addView(text("Recent odometer readings", 14, getColorCompat(R.color.ink), Typeface.BOLD));
            for (OdometerReading reading : readings) {
                String detail = TimeFormat.shortDateTime(reading.recordedAt)
                        + " - " + vehicleName(reading.vehicleId)
                        + " - " + String.format(Locale.US, "%.1f %s",
                        reading.odometerValue,
                        DistanceUnit.shortLabel(activeDistanceUnit()));
                addTopMargin(card, text(detail, 13, getColorCompat(R.color.muted), Typeface.NORMAL), 6);
            }
        }
        content.addView(card);
    }

    private View vehicleRow(VehicleRecord vehicle, long defaultVehicleId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(2));
        String label = vehicle.label() + (vehicle.id == defaultVehicleId ? " - default" : "");
        row.addView(text(label, 15, getColorCompat(R.color.ink), Typeface.BOLD));
        String detail = (vehicle.odometerPromptEnabled ? "Monthly odometer reminder" : "Odometer reminder off")
                + " - Bluetooth " + (vehicle.hasBluetooth() ? vehicle.bluetoothLabel() : "not set");
        row.addView(text(detail, 13, getColorCompat(R.color.muted), Typeface.NORMAL));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button setDefault = textButton("Default");
        setDefault.setOnClickListener(v -> {
            settings.setDefaultVehicleId(vehicle.id);
            render();
        });
        Button odometer = textButton("Odometer");
        odometer.setOnClickListener(v -> showOdometerDialog(vehicle));
        Button bluetooth = textButton("Bluetooth");
        bluetooth.setOnClickListener(v -> showBluetoothPicker((name, address) -> {
            repository.updateVehicleBluetooth(vehicle.id, name, address);
            render();
        }));
        Button edit = textButton("Edit");
        edit.setOnClickListener(v -> showVehicleDialog(vehicle));
        Button delete = dangerTextButton("Delete");
        delete.setOnClickListener(v -> {
            repository.deleteVehicle(vehicle.id);
            render();
        });
        actions.addView(setDefault);
        actions.addView(odometer);
        actions.addView(bluetooth);
        row.addView(actions, fullWidthLayout());

        LinearLayout editActions = new LinearLayout(this);
        editActions.setOrientation(LinearLayout.HORIZONTAL);
        editActions.addView(edit);
        editActions.addView(delete);
        row.addView(editActions, fullWidthLayout());
        return row;
    }

    private void showVehicleDialog(VehicleRecord existing) {
        boolean editing = existing != null;
        LinearLayout form = dialogForm();

        EditText brand = modernEditText("Brand", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText model = modernEditText("Model", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        CheckBox askOdometer = modernCheckBox("Monthly odometer reminder",
                editing && existing.odometerPromptEnabled);
        final String[] bluetoothName = {editing ? existing.bluetoothName : ""};
        final String[] bluetoothAddress = {editing ? existing.bluetoothAddress : ""};
        TextView bluetoothValue = text(bluetoothSummary(bluetoothName[0], bluetoothAddress[0]),
                14,
                getColorCompat(R.color.muted),
                Typeface.NORMAL);

        if (editing) {
            String existingBrand = existing.brand == null || existing.brand.trim().isEmpty()
                    ? existing.name
                    : existing.brand;
            brand.setText(existingBrand == null ? "" : existingBrand);
            model.setText(existing.model == null ? "" : existing.model);
        }

        form.addView(formField("Brand", brand, null), fullWidthLayout());
        addTopMargin(form, formField("Model", model, null), 12);

        EditText initialOdometer = null;
        if (!editing) {
            initialOdometer = modernEditText("Optional", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            addTopMargin(form, formField("Current odometer " + DistanceUnit.shortLabel(activeDistanceUnit()), initialOdometer, "Leave blank if you do not want to add a reading now."), 12);
        }

        addTopMargin(form, askOdometer, 12);
        addTopMargin(form, text("Shown after a trip only when this vehicle has no recent odometer reading.", 12, getColorCompat(R.color.muted), Typeface.NORMAL), 5);

        LinearLayout bluetoothField = new LinearLayout(this);
        bluetoothField.setOrientation(LinearLayout.VERTICAL);
        bluetoothField.addView(bluetoothValue);
        LinearLayout bluetoothActions = new LinearLayout(this);
        bluetoothActions.setOrientation(LinearLayout.HORIZONTAL);
        Button setBluetooth = secondaryButton("Pick Bluetooth");
        setBluetooth.setOnClickListener(v -> showBluetoothPicker((name, address) -> {
            bluetoothName[0] = name;
            bluetoothAddress[0] = address;
            bluetoothValue.setText(bluetoothSummary(name, address));
        }));
        bluetoothActions.addView(setBluetooth, weightedLayout(1));
        Button clearBluetooth = textButton("Clear");
        clearBluetooth.setOnClickListener(v -> {
            bluetoothName[0] = "";
            bluetoothAddress[0] = "";
            bluetoothValue.setText(bluetoothSummary("", ""));
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.setMargins(dp(8), 0, 0, 0);
        bluetoothActions.addView(clearBluetooth, clearParams);
        addTopMargin(bluetoothField, bluetoothActions, 8);
        addTopMargin(form, formField("Bluetooth", bluetoothField, "Used only when Bluetooth auto-record is enabled."), 12);

        final EditText odometerInput = initialOdometer;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "Edit vehicle" : "Add vehicle")
                .setView(dialogScroll(form))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String brandValue = brand.getText().toString().trim();
                    String modelValue = model.getText().toString().trim();
                    if (brandValue.isEmpty() && modelValue.isEmpty()) {
                        brand.setError("Enter a brand or model");
                        return;
                    }
                    if (odometerInput != null && invalidOptionalOdometer(odometerInput)) {
                        return;
                    }
                    if (editing) {
                        repository.updateVehicle(
                                existing.id,
                                brandValue,
                                modelValue,
                                askOdometer.isChecked(),
                                bluetoothName[0],
                                bluetoothAddress[0]);
                    } else {
                        long id = repository.addVehicle(
                                brandValue,
                                modelValue,
                                askOdometer.isChecked(),
                                bluetoothName[0],
                                bluetoothAddress[0]);
                        Double odometer = parseOptionalDouble(odometerInput.getText().toString());
                        if (odometer != null) {
                            repository.addOdometerReading(id, odometer, "Initial reading");
                        }
                        if (settings.defaultVehicleId() <= 0) {
                            settings.setDefaultVehicleId(id);
                        }
                    }
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
        styleAlertDialog(dialog);
    }

    private void showOdometerDialog(VehicleRecord vehicle) {
        LinearLayout form = dialogForm();
        EditText reading = editText("Odometer " + DistanceUnit.shortLabel(activeDistanceUnit()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText notes = editText("Notes", InputType.TYPE_CLASS_TEXT);
        form.addView(reading);
        form.addView(notes);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add odometer reading")
                .setView(form)
                .setPositiveButton("Save", (choiceDialog, which) -> {
                    double distance = parseDouble(reading.getText().toString(), -1);
                    if (distance < 0) {
                        Toast.makeText(this, "Enter a valid odometer value.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    repository.addOdometerReading(vehicle.id, distance, notes.getText().toString());
                    render();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        styleAlertDialog(dialog);
    }

    private void showOdometerReminderDialog(VehicleRecord vehicle) {
        settings.setLastOdometerPromptAt(vehicle.id, System.currentTimeMillis());
        LinearLayout form = dialogForm();
        form.addView(text(vehicle.label(), 14, getColorCompat(R.color.muted), Typeface.NORMAL));
        EditText reading = modernEditText("Optional", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addTopMargin(form, formField("Odometer " + DistanceUnit.shortLabel(activeDistanceUnit()), reading, "Leave blank to skip this reminder."), 10);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Odometer reminder")
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (invalidOptionalOdometer(reading)) {
                return;
            }
            Double odometer = parseOptionalDouble(reading.getText().toString());
            if (odometer != null) {
                repository.addOdometerReading(vehicle.id, odometer, "Reminder");
            }
            dialog.dismiss();
            render();
        }));
        dialog.show();
        styleAlertDialog(dialog);
    }

    private void addRatesCard() {
        LinearLayout card = sectionCard("Category rates", "Rates are per " + DistanceUnit.shortLabel(activeDistanceUnit()) + " and used only for report totals.");
        card.addView(summaryRow("Saved rates", categoryRateSummary()));
        Button edit = secondaryButton("Edit rates");
        edit.setOnClickListener(v -> showRatesDialog());
        card.addView(edit, buttonLayout());
        content.addView(card);
    }

    private void showRatesDialog() {
        LinearLayout form = dialogForm();
        addTopMargin(form, text("Rates are per " + DistanceUnit.shortLabel(activeDistanceUnit()) + " and used only for report totals.", 13, getColorCompat(R.color.muted), Typeface.NORMAL), 0);
        Map<String, Double> savedRates = settings.categoryRates();
        LinkedHashMap<String, EditText> inputs = new LinkedHashMap<>();
        for (String category : categoryOptions()) {
            EditText input = modernEditText("No rate", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            Double rate = savedRates.get(TripCategory.normalize(category));
            if (rate != null && rate > 0) {
                input.setText(String.format(Locale.US, "%.2f", rate));
            }
            inputs.put(category, input);
            addTopMargin(form, formField(category, input, null), 10);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Category rates")
                .setView(dialogScroll(form))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
                String raw = entry.getValue().getText().toString().trim();
                Double rate = raw.isEmpty() ? null : parseDouble(raw, -1);
                if (rate != null && rate < 0) {
                    entry.getValue().setError("Use a positive number");
                    return;
                }
                settings.setCategoryRate(entry.getKey(), rate);
            }
            Toast.makeText(this, "Rates saved.", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            render();
        }));
        dialog.show();
        styleAlertDialog(dialog);
    }

    private void addPlacesCard() {
        LinearLayout card = sectionCard("Place Rules", null);

        Button add = secondaryButton("Pick place on map");
        add.setOnClickListener(v -> pickPlaceOnMap());
        card.addView(add, buttonLayout());

        List<PlaceRule> places = repository.places();
        if (places.isEmpty()) {
            addTopMargin(card, text("No saved places yet.", 14, getColorCompat(R.color.muted), Typeface.NORMAL), 12);
        } else {
            for (int i = 0; i < places.size(); i++) {
                addDivider(card);
                card.addView(placeRow(places.get(i)));
            }
        }
        content.addView(card);
    }

    private void addTripHistoryCard() {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(needsReviewOnly ? "Needs review" : "History", 18, getColorCompat(R.color.ink), Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button export = textButton("Reports");
        export.setOnClickListener(v -> showReportDialog());
        heading.addView(export);
        card.addView(heading);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        Button review = secondaryButton("Needs review");
        review.setEnabled(!needsReviewOnly);
        review.setOnClickListener(v -> {
            needsReviewOnly = true;
            render();
        });
        Button all = secondaryButton("All trips");
        all.setEnabled(needsReviewOnly);
        all.setOnClickListener(v -> {
            needsReviewOnly = false;
            render();
        });
        filters.addView(review, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        allParams.setMargins(dp(8), 0, 0, 0);
        filters.addView(all, allParams);
        card.addView(filters, buttonLayout());

        List<TripRecord> trips = needsReviewOnly ? repository.tripsNeedingReview(50) : repository.recentTrips(50);
        if (trips.isEmpty()) {
            addTopMargin(card, text(needsReviewOnly ? "No trips need review." : "No trips recorded.", 14, getColorCompat(R.color.muted), Typeface.NORMAL), 8);
        } else {
            for (int i = 0; i < trips.size(); i++) {
                if (i > 0) {
                    addDivider(card);
                }
                card.addView(tripRow(trips.get(i)));
            }
        }
        content.addView(card);
    }

    private View placeRow(PlaceRule place) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));

        String detail = place.category + " - " + Math.round(place.radiusMeters) + " m"
                + " - " + (place.matchStart ? "start" : "") + (place.matchStart && place.matchEnd ? " + " : "")
                + (place.matchEnd ? "end" : "");
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(place.name, 15, getColorCompat(R.color.ink), Typeface.BOLD));
        labels.addView(text(detail, 13, getColorCompat(R.color.muted), Typeface.NORMAL));
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button edit = textButton("Edit");
        edit.setOnClickListener(v -> showEditPlaceDialog(place));
        row.addView(edit);

        Button delete = dangerTextButton("Delete");
        delete.setOnClickListener(v -> {
            repository.deletePlace(place.id);
            render();
        });
        row.addView(delete);
        return row;
    }

    private View tripRow(TripRecord trip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(trip.title(), 15, getColorCompat(R.color.ink), Typeface.BOLD));
        labels.addView(text(trip.summary(activeDistanceUnit()), 14, getColorCompat(R.color.accent_text), Typeface.BOLD));
        top.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button route = textButton("Route");
        route.setOnClickListener(v -> showTripRouteDialog(trip));
        top.addView(route);
        Button edit = textButton("Edit");
        edit.setOnClickListener(v -> showEditTripDialog(trip));
        top.addView(edit);
        if (!trip.active && trip.reviewed) {
            Button delete = dangerTextButton("Delete");
            delete.setOnClickListener(v -> confirmDeleteTrip(trip));
            top.addView(delete);
        }
        row.addView(top);

        TextView points = text(routeEndpointLabels(trip), 12, getColorCompat(R.color.muted), Typeface.NORMAL);
        row.addView(points);
        resolveEndpointLabels(trip, points);
        String detail = trip.reviewLabel()
                + " - " + AutoStartSource.label(trip.autoStartSource)
                + " - " + vehicleName(trip.vehicleId);
        addTopMargin(row, text(detail, 12, trip.reviewed ? getColorCompat(R.color.muted) : getColorCompat(R.color.warn_text), Typeface.BOLD), 4);
        if (trip.startOdometer != null || trip.endOdometer != null) {
            String odometer = "Odometer " + optionalNumber(trip.startOdometer) + " -> " + optionalNumber(trip.endOdometer);
            addTopMargin(row, text(odometer, 12, getColorCompat(R.color.muted), Typeface.NORMAL), 3);
        }
        if (!trip.active && !trip.reviewed) {
            addReviewActions(row, trip);
        }
        if (trip.notes != null && !trip.notes.trim().isEmpty()) {
            addTopMargin(row, text(trip.notes.trim(), 13, getColorCompat(R.color.ink), Typeface.NORMAL), 4);
        }
        return row;
    }

    private void addReviewActions(LinearLayout row, TripRecord trip) {
        String category = TripCategory.normalize(trip.category);
        if (!TripCategory.UNCATEGORIZED.equals(category)) {
            Button confirm = secondaryButton("Confirm category");
            confirm.setOnClickListener(v -> {
                repository.reviewTrip(trip.id, category);
                render();
            });
            addTopMargin(row, confirm, 8);
        } else {
            LinearLayout quick = new LinearLayout(this);
            quick.setOrientation(LinearLayout.HORIZONTAL);
            addReviewButton(quick, trip, TripCategory.BUSINESS);
            addReviewButton(quick, trip, TripCategory.PERSONAL);
            addReviewButton(quick, trip, TripCategory.COMMUTE);
            addTopMargin(row, quick, 8);
        }

        Button decline = warningButton("Decline trip");
        decline.setOnClickListener(v -> confirmDeclineTrip(trip));
        addTopMargin(row, decline, 8);
    }

    private String routeEndpointLabels(TripRecord trip) {
        return "Start " + locationLabel(trip.startLat, trip.startLng)
                + "\nEnd " + locationLabel(trip.endLat, trip.endLng);
    }

    private String locationLabel(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return "Unknown";
        }
        String key = addressKey(lat, lng);
        String cached = addressCache.get(key);
        return cached == null ? GeoMath.coordinateLabel(lat, lng) : cached;
    }

    private void resolveEndpointLabels(TripRecord trip, TextView target) {
        requestAddressLabel(trip.startLat, trip.startLng);
        requestAddressLabel(trip.endLat, trip.endLng);
        target.setText(routeEndpointLabels(trip));
    }

    private void requestAddressLabel(Double lat, Double lng) {
        if (geocoder == null || lat == null || lng == null) {
            return;
        }
        String key = addressKey(lat, lng);
        if (addressCache.containsKey(key) || pendingAddressLookups.contains(key)) {
            return;
        }
        pendingAddressLookups.add(key);
        geocodeExecutor.execute(() -> {
            String label = reverseGeocode(lat, lng);
            runOnUiThread(() -> {
                pendingAddressLookups.remove(key);
                addressCache.put(key, label == null ? GeoMath.coordinateLabel(lat, lng) : label);
                if (selectedTab == TAB_TRIPS) {
                    render();
                }
            });
        });
    }

    private String reverseGeocode(double lat, double lng) {
        try {
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses == null || addresses.isEmpty()) {
                return null;
            }
            return formatAddress(addresses.get(0));
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatAddress(Address address) {
        if (address == null) {
            return null;
        }
        String street = clean(address.getThoroughfare());
        String house = clean(address.getSubThoroughfare());
        String place = !street.isEmpty()
                ? (street + (house.isEmpty() ? "" : " " + house))
                : clean(address.getFeatureName());
        if (place.isEmpty()) {
            place = clean(address.getPremises());
        }
        if (place.isEmpty()) {
            place = clean(address.getAddressLine(0));
        }

        String area = clean(address.getSubLocality());
        if (area.isEmpty()) {
            area = clean(address.getLocality());
        }
        if (area.isEmpty()) {
            area = clean(address.getAdminArea());
        }
        if (!place.isEmpty() && !area.isEmpty() && !place.equals(area)) {
            return place + ", " + area;
        }
        return place.isEmpty() ? null : place;
    }

    private String addressKey(double lat, double lng) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lng);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void addReviewButton(LinearLayout parent, TripRecord trip, String category) {
        Button button = textButton(category);
        button.setOnClickListener(v -> {
            repository.reviewTrip(trip.id, category);
            render();
        });
        parent.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private void confirmDeleteTrip(TripRecord trip) {
        confirmRemoveTrip(trip, "Delete trip?", "Delete");
    }

    private void confirmDeclineTrip(TripRecord trip) {
        confirmRemoveTrip(trip, "Decline trip?", "Decline");
    }

    private void confirmRemoveTrip(TripRecord trip, String title, String action) {
        if (trip.active) {
            Toast.makeText(this, "Stop the active trip before deleting it.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("This removes the trip record and its recorded points from this device.")
                .setPositiveButton(action, (dialog, which) -> {
                    repository.deleteTrip(trip.id);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startTrip(boolean autoStarted) {
        if (!hasLocationPermission()) {
            requestBasicPermissions();
            return;
        }
        if (!AppPermissions.hasEnabledLocationProvider(this)) {
            Toast.makeText(this, "Turn on device Location before starting a trip.", Toast.LENGTH_LONG).show();
            return;
        }
        VehicleRecord vehicle = autoStarted ? null : defaultVehicle();
        startTripService(autoStarted, vehicle == null ? null : vehicle.id);
    }

    private void startTripService(boolean autoStarted, Long vehicleId) {
        Intent intent = new Intent(this, TripTrackingService.class)
                .setAction(TripTrackingService.ACTION_START)
                .putExtra(TripTrackingService.EXTRA_AUTO_STARTED, autoStarted)
                .putExtra(TripTrackingService.EXTRA_START_SOURCE,
                        autoStarted ? AutoStartSource.BLUETOOTH : AutoStartSource.MANUAL);
        if (vehicleId != null && vehicleId > 0) {
            intent.putExtra(TripTrackingService.EXTRA_VEHICLE_ID, vehicleId.longValue());
        }
        startForegroundService(intent);
        render();
    }

    private void stopTrip() {
        TripRecord active = repository.getActiveTrip();
        VehicleRecord vehicle = active == null || active.vehicleId == null ? null : repository.vehicle(active.vehicleId);
        stopTripService(null);
        if (active != null && shouldShowOdometerReminder(vehicle)) {
            showOdometerReminderDialog(vehicle);
        }
    }

    private boolean shouldShowOdometerReminder(VehicleRecord vehicle) {
        if (vehicle == null || !vehicle.odometerPromptEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lastReadingAt = repository.latestOdometerReadingAt(vehicle.id);
        if (lastReadingAt > 0 && now - lastReadingAt >= 0 && now - lastReadingAt < ODOMETER_REMINDER_INTERVAL_MS) {
            return false;
        }
        long lastPromptAt = settings.lastOdometerPromptAt(vehicle.id);
        return lastPromptAt <= 0 || now - lastPromptAt < 0 || now - lastPromptAt >= ODOMETER_REMINDER_INTERVAL_MS;
    }

    private void stopTripService(Double endOdometer) {
        Intent intent = new Intent(this, TripTrackingService.class).setAction(TripTrackingService.ACTION_STOP);
        if (endOdometer != null && endOdometer >= 0) {
            intent.putExtra(TripTrackingService.EXTRA_END_ODOMETER, endOdometer.doubleValue());
        }
        startService(intent);
        render();
    }

    private void syncPersistentTrackingService() {
        if (AppPermissions.canAnyAutoRecord(this, settings, repository)) {
            Intent intent = new Intent(this, TripTrackingService.class)
                    .setAction(TripTrackingService.ACTION_ENABLE_AUTO_DETECT);
            try {
                startForegroundService(intent);
            } catch (RuntimeException exception) {
                TripNotifications.showResumeTripRequired(this);
            }
            return;
        }

        if (repository.getActiveTrip() != null && hasLocationPermission()) {
            Intent intent = new Intent(this, TripTrackingService.class)
                    .setAction(TripTrackingService.ACTION_START);
            try {
                startForegroundService(intent);
            } catch (RuntimeException exception) {
                TripNotifications.showResumeTripRequired(this);
            }
            return;
        }

        if (!settings.autoRecordEnabled() && !settings.motionAutoRecordEnabled()) {
            startService(new Intent(this, TripTrackingService.class)
                    .setAction(TripTrackingService.ACTION_DISABLE_AUTO_DETECT));
        }
    }

    private void pickPlaceOnMap() {
        pendingEditPlaceId = -1;
        Intent intent = new Intent(this, MapPickerActivity.class);
        Location startLocation = null;
        if (hasLocationPermission()) {
            startLocation = bestLastLocation();
        }
        if (startLocation == null) {
            startLocation = fallbackPlacePickerLocation();
        }
        if (startLocation != null) {
            intent.putExtra(MapPickerActivity.EXTRA_LAT, startLocation.getLatitude());
            intent.putExtra(MapPickerActivity.EXTRA_LNG, startLocation.getLongitude());
        }
        startActivityForResult(intent, REQ_PICK_PLACE);
    }

    private void pickPlaceOnMapForEdit(PlaceRule place) {
        pendingEditPlaceId = place.id;
        Intent intent = new Intent(this, MapPickerActivity.class)
                .putExtra(MapPickerActivity.EXTRA_LAT, place.latitude)
                .putExtra(MapPickerActivity.EXTRA_LNG, place.longitude);
        startActivityForResult(intent, REQ_PICK_PLACE);
    }

    private PlaceRule findPlace(long id) {
        for (PlaceRule place : repository.places()) {
            if (place.id == id) {
                return place;
            }
        }
        return null;
    }

    private void showAddPlaceDialog(Location location) {
        showPlaceDialog(null, location);
    }

    private void showEditPlaceDialog(PlaceRule place) {
        Location location = new Location("place");
        location.setLatitude(place.latitude);
        location.setLongitude(place.longitude);
        showPlaceDialog(place, location);
    }

    private void showPlaceDialog(PlaceRule existingPlace, Location location) {
        boolean editing = existingPlace != null;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout modal = modalContainer();
        modal.addView(text(editing ? "Edit place" : "Save picked place", 20, getColorCompat(R.color.ink), Typeface.BOLD));
        addTopMargin(modal, text(editing ? "Update this auto-categorizing rule." : "Use this point to auto-categorize trips.", 14, getColorCompat(R.color.muted), Typeface.NORMAL), 4);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(12), 0, 0);

        EditText name = modernEditText("Optional", InputType.TYPE_CLASS_TEXT);
        if (editing) {
            name.setText(existingPlace.name);
        }
        form.addView(formField("Name", name, null), fullWidthLayout());

        AutoCompleteTextView category = categoryInput(editing ? existingPlace.category : TripCategory.BUSINESS);
        addTopMargin(form, formField("Category", category, "Pick a saved category or type a custom one."), 12);

        EditText radius = modernEditText("150", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (editing) {
            radius.setText(String.valueOf(Math.round(existingPlace.radiusMeters)));
        } else {
            radius.setText(R.string.default_place_radius);
        }
        radius.setSelectAllOnFocus(true);
        addTopMargin(form, formField("Match radius (meters)", radius, "Trips inside this distance can match the rule."), 12);
        WebView radiusPreview = radiusMapPreview(location, radius);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(154));
        previewParams.setMargins(0, dp(10), 0, 0);
        form.addView(radiusPreview, previewParams);

        if (editing) {
            Button changeLocation = secondaryButton("Change map point");
            changeLocation.setOnClickListener(v -> {
                dialog.dismiss();
                pickPlaceOnMapForEdit(existingPlace);
            });
            addTopMargin(form, changeLocation, 10);
        }

        CheckBox start = modernCheckBox(getString(R.string.match_trip_start), !editing || existingPlace.matchStart);
        CheckBox end = modernCheckBox(getString(R.string.match_trip_end), !editing || existingPlace.matchEnd);
        addTopMargin(form, start, 14);
        addTopMargin(form, end, 8);

        modal.addView(form, fullWidthLayout());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = textButton("Cancel");
        Button save = primaryButton("Save");
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            double radiusMeters = parseDouble(radius.getText().toString(), -1);
            if (radiusMeters < 25) {
                radius.setError("Use 25 meters or more");
                return;
            }
            if (!start.isChecked() && !end.isChecked()) {
                Toast.makeText(this, "Choose start, end, or both.", Toast.LENGTH_LONG).show();
                return;
            }
            if (editing) {
                repository.updatePlace(
                        existingPlace.id,
                        name.getText().toString(),
                        location.getLatitude(),
                        location.getLongitude(),
                        radiusMeters,
                        cleanCategoryInput(category.getText()),
                        start.isChecked(),
                        end.isChecked());
            } else {
                repository.addPlace(
                        name.getText().toString(),
                        location.getLatitude(),
                        location.getLongitude(),
                        radiusMeters,
                        cleanCategoryInput(category.getText()),
                        start.isChecked(),
                        end.isChecked());
            }
            dialog.dismiss();
            render();
        });
        actions.addView(cancel);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(save, saveParams);
        addTopMargin(modal, actions, 18);

        dialog.setContentView(dialogScroll(modal));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.62f;
            window.setAttributes(params);
        }
    }

    private Location fallbackPlacePickerLocation() {
        List<PlaceRule> places = repository.places();
        if (!places.isEmpty()) {
            PlaceRule place = places.get(0);
            Location location = new Location("place");
            location.setLatitude(place.latitude);
            location.setLongitude(place.longitude);
            return location;
        }
        for (TripRecord trip : repository.recentTrips(50)) {
            Double lat = trip.endLat != null ? trip.endLat : trip.startLat;
            Double lng = trip.endLng != null ? trip.endLng : trip.startLng;
            if (lat != null && lng != null) {
                Location location = new Location("trip");
                location.setLatitude(lat);
                location.setLongitude(lng);
                return location;
            }
        }
        return null;
    }

    private void showEditTripDialog(TripRecord trip) {
        LinearLayout form = dialogForm();
        AutoCompleteTextView category = categoryInput(trip.category);
        form.addView(category);
        List<VehicleRecord> vehicles = repository.vehicles();
        Spinner vehicle = new Spinner(this);
        List<String> vehicleLabels = new ArrayList<>();
        vehicleLabels.add("No vehicle");
        int selectedVehicle = 0;
        for (int i = 0; i < vehicles.size(); i++) {
            VehicleRecord record = vehicles.get(i);
            vehicleLabels.add(record.label());
            if (trip.vehicleId != null && trip.vehicleId == record.id) {
                selectedVehicle = i + 1;
            }
        }
        vehicle.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, vehicleLabels));
        vehicle.setSelection(selectedVehicle);
        form.addView(vehicle);

        String odometerUnit = DistanceUnit.shortLabel(activeDistanceUnit());
        EditText startOdometer = editText("Start odometer " + odometerUnit, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        startOdometer.setText(optionalNumber(trip.startOdometer));
        form.addView(startOdometer);
        EditText endOdometer = editText("End odometer " + odometerUnit, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        endOdometer.setText(optionalNumber(trip.endOdometer));
        form.addView(endOdometer);
        EditText notes = editText("Notes", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setMinLines(3);
        notes.setText(trip.notes == null ? "" : trip.notes);
        form.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("Edit trip")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    Long vehicleId = null;
                    int vehicleSelection = vehicle.getSelectedItemPosition();
                    if (vehicleSelection > 0 && vehicleSelection - 1 < vehicles.size()) {
                        vehicleId = vehicles.get(vehicleSelection - 1).id;
                    }
                    repository.updateTrip(
                            trip.id,
                            cleanCategoryInput(category.getText()),
                            notes.getText().toString(),
                            vehicleId,
                            parseOptionalDouble(startOdometer.getText().toString()),
                            parseOptionalDouble(endOdometer.getText().toString()));
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTripRouteDialog(TripRecord trip) {
        List<TripPoint> points = repository.tripPoints(trip.id);
        if (points.isEmpty()) {
            Toast.makeText(this, "No route points were saved for this trip.", Toast.LENGTH_LONG).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout modal = modalContainer();
        modal.addView(text("Trip route", 20, getColorCompat(R.color.ink), Typeface.BOLD));
        addTopMargin(modal, text(trip.title() + " - " + GeoMath.distanceLabel(trip.distanceMeters, activeDistanceUnit()),
                13,
                getColorCompat(R.color.muted),
                Typeface.NORMAL), 3);

        WebView map = routeMap(points);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360));
        mapParams.setMargins(0, dp(14), 0, 0);
        modal.addView(map, mapParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button close = primaryButton("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        actions.addView(close, new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));
        addTopMargin(modal, actions, 14);

        dialog.setContentView(modal);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(620));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.62f;
            window.setAttributes(params);
        }
    }

    private void showBluetoothPicker(BluetoothSelectionHandler handler) {
        if (!hasBluetoothPermission()) {
            requestBasicPermissions();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device.", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bondedDevices;
        try {
            bondedDevices = adapter.getBondedDevices();
        } catch (SecurityException exception) {
            requestBasicPermissions();
            return;
        }
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(this, "Pair the car in Android Bluetooth settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(bondedDevices);
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            String name = safeBluetoothName(device);
            String address = safeBluetoothAddress(device);
            labels[i] = (name == null || name.isEmpty() ? "Unnamed device" : name)
                    + (address == null || address.isEmpty() ? "" : "\n" + address);
        }

        new AlertDialog.Builder(this)
                .setTitle("Pick vehicle Bluetooth")
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice device = devices.get(which);
                    handler.onSelected(safeBluetoothName(device), safeBluetoothAddress(device));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportDialog() {
        LinearLayout form = dialogForm();
        EditText start = editText("Start date YYYY-MM-DD", InputType.TYPE_CLASS_DATETIME);
        EditText end = editText("End date YYYY-MM-DD", InputType.TYPE_CLASS_DATETIME);
        start.setText(TimeFormat.dateOnly(startOfCurrentYear()));
        end.setText(TimeFormat.dateOnly(System.currentTimeMillis()));
        form.addView(start);
        form.addView(end);

        new AlertDialog.Builder(this)
                .setTitle("Trip report")
                .setView(form)
                .setPositiveButton("Continue", (dialog, which) -> {
                    Long startMillis = parseDate(start.getText().toString(), true);
                    Long endMillis = parseDate(end.getText().toString(), false);
                    if (startMillis == null || endMillis == null || startMillis > endMillis) {
                        Toast.makeText(this, "Enter a valid date range.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showReportActions(repository.report(startMillis, endMillis));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showReportActions(TripReport report) {
        new AlertDialog.Builder(this)
                .setTitle("Export report")
                .setItems(new String[]{"Save PDF", "Share PDF", "Save CSV", "Share CSV"}, (dialog, which) -> {
                    if (which == 0) {
                        savePdf(report);
                    } else if (which == 1) {
                        sharePdf(report);
                    } else if (which == 2) {
                        saveCsv(report);
                    } else {
                        shareCsv(report);
                    }
                })
                .show();
    }

    private void saveCsv(TripReport report) {
        String csv = TripReportCsv.render(report);
        saveDocument(csv.getBytes(StandardCharsets.UTF_8), "text/csv", "trip-report.csv", "CSV saved.");
    }

    private void savePdf(TripReport report) {
        try {
            saveDocument(TripReportPdf.render(report), "application/pdf", "trip-report.pdf", "PDF saved.");
        } catch (IOException exception) {
            Toast.makeText(this, "Could not build PDF.", Toast.LENGTH_LONG).show();
        }
    }

    private void saveDocument(byte[] bytes, String mime, String name, String successMessage) {
        pendingDocument = bytes;
        pendingDocumentSuccess = successMessage;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(create, REQ_CREATE_DOCUMENT);
        } catch (ActivityNotFoundException exception) {
            clearPendingDocument();
            Toast.makeText(this, "No file picker is available.", Toast.LENGTH_LONG).show();
        }
    }

    private void shareCsv(TripReport report) {
        String csv = TripReportCsv.render(report);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_SUBJECT, "Tripbook CSV")
                .putExtra(Intent.EXTRA_TEXT, csv);
        startActivity(Intent.createChooser(send, "Share CSV"));
    }

    private void sharePdf(TripReport report) {
        try {
            String name = "trip-report.pdf";
            File file = ReportFileProvider.reportFile(this, name);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(TripReportPdf.render(report));
            }
            Uri uri = ReportFileProvider.uriFor(this, name);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_SUBJECT, "Tripbook PDF")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share PDF"));
        } catch (IOException | SecurityException exception) {
            Toast.makeText(this, "Could not share PDF.", Toast.LENGTH_LONG).show();
        }
    }

    private void writePendingDocument(Uri uri) {
        byte[] bytes = pendingDocument;
        String success = pendingDocumentSuccess;
        clearPendingDocument();
        if (bytes == null) {
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                Toast.makeText(this, "Could not open the selected file.", Toast.LENGTH_LONG).show();
                return;
            }
            output.write(bytes);
            Toast.makeText(this, success == null ? "File saved." : success, Toast.LENGTH_SHORT).show();
        } catch (IOException | SecurityException exception) {
            Toast.makeText(this, "Could not save file.", Toast.LENGTH_LONG).show();
        }
    }

    private void clearPendingDocument() {
        pendingDocument = null;
        pendingDocumentSuccess = null;
    }

    private Location bestLastLocation() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        best = newer(best, lastKnown(manager, LocationManager.GPS_PROVIDER));
        best = newer(best, lastKnown(manager, LocationManager.NETWORK_PROVIDER));
        return best;
    }

    private Location lastKnown(LocationManager manager, String provider) {
        try {
            if (!manager.isProviderEnabled(provider)) {
                return null;
            }
            return manager.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private Location newer(Location current, Location candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.getTime() > current.getTime()) {
            return candidate;
        }
        return current;
    }

    private void requestBasicPermissions() {
        List<String> permissions = new ArrayList<>();
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognitionPermission()) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (permissions.isEmpty()) {
            Toast.makeText(this, "Basic permissions are already granted.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(permissions.toArray(new String[0]), REQ_BASIC_PERMISSIONS);
    }

    private void requestBackgroundLocation() {
        if (!settings.locationDisclosureAccepted()) {
            showLocationDisclosure(this::requestBackgroundLocation);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Background location is already covered on this Android version.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasLocationPermission()) {
            requestBasicPermissions();
            return;
        }
        if (hasBackgroundLocationPermission()) {
            Toast.makeText(this, "Background location is already allowed.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAppSettings();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private boolean hasLocationPermission() {
        return AppPermissions.hasLocation(this);
    }

    private boolean hasBackgroundLocationPermission() {
        return AppPermissions.hasBackgroundLocation(this);
    }

    private boolean hasBluetoothPermission() {
        return AppPermissions.hasBluetoothConnect(this);
    }

    private boolean hasNotificationPermission() {
        return AppPermissions.hasNotificationPermission(this);
    }

    private boolean hasActivityRecognitionPermission() {
        return AppPermissions.hasActivityRecognition(this);
    }

    private boolean bluetoothAutoRecordReady() {
        return settings.locationDisclosureAccepted()
                && repository.hasBluetoothVehicle()
                && hasLocationPermission()
                && hasBackgroundLocationPermission()
                && hasBluetoothPermission()
                && hasNotificationPermission();
    }

    private boolean motionAutoRecordReady() {
        return settings.locationDisclosureAccepted()
                && hasLocationPermission()
                && hasBackgroundLocationPermission()
                && hasActivityRecognitionPermission()
                && hasNotificationPermission();
    }

    private void showLocationDisclosure(Runnable afterAccepted) {
        new AlertDialog.Builder(this)
                .setTitle("Privacy and background location")
                .setMessage("Tripbook collects location data to record trips and enable Bluetooth or motion auto-recording even when the app is closed or not in use. Motion auto-recording may also use physical activity recognition. Tripbook shows an ongoing recording notification while tracking or waiting for drives.\n\nTrip coordinates, vehicles, categories, notes, odometer values, and exports stay on this device unless you choose to save or share them. The app has no account, ads, analytics, or server sync.")
                .setPositiveButton("I understand", (dialog, which) -> {
                    settings.setLocationDisclosureAccepted(true);
                    render();
                    if (afterAccepted != null) {
                        afterAccepted.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String safeBluetoothName(BluetoothDevice device) {
        try {
            return device.getName();
        } catch (SecurityException exception) {
            return "";
        }
    }

    private String safeBluetoothAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException exception) {
            return "";
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(getColorCompat(R.color.app_bg));
        getWindow().setNavigationBarColor(getColorCompat(R.color.app_bg));

        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int flags = 0;
        if (!isNight) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private Button tabButton(String label, int tab) {
        Button button = new Button(this);
        boolean selected = selectedTab == tab;
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? getColorCompat(R.color.ink) : getColorCompat(R.color.muted));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? getColorCompat(R.color.surface_muted) : Color.TRANSPARENT);
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        button.setOnClickListener(v -> {
            if (selectedTab == tab) {
                return;
            }
            animateNextBody = true;
            selectedTab = tab;
            render();
        });
        return button;
    }

    private void animateTabContent(View view, int direction) {
        view.setAlpha(0f);
        view.setTranslationX(dp(12) * direction);
        view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(150L)
                .start();
    }

    private LinearLayout.LayoutParams tabLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout sectionCard(String title, String subtitle) {
        LinearLayout layout = card();
        layout.addView(text(title, 16, getColorCompat(R.color.ink), Typeface.BOLD));
        if (subtitle != null && !subtitle.isEmpty()) {
            addTopMargin(layout, text(subtitle, 13, getColorCompat(R.color.muted), Typeface.NORMAL), 3);
        }
        return layout;
    }

    private View statusRow(String label, boolean granted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        row.addView(text(label, 14, getColorCompat(R.color.ink), Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView value = text(granted ? "Allowed" : "Needed",
                14,
                granted ? getColorCompat(R.color.accent_text) : getColorCompat(R.color.warn_text),
                Typeface.BOLD);
        value.setGravity(Gravity.END);
        row.addView(value);
        return row;
    }

    private View valueRow(String label, String rawValue, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        row.addView(text(label, 14, getColorCompat(R.color.ink), Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView value = text(rawValue, 14, valueColor, Typeface.BOLD);
        value.setGravity(Gravity.END);
        row.addView(value);
        return row;
    }

    private View summaryRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(1));
        row.addView(text(label, 13, getColorCompat(R.color.muted), Typeface.NORMAL));
        row.addView(text(value, 15, getColorCompat(R.color.ink), Typeface.BOLD));
        return row;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColorCompat(R.color.surface));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), getColorCompat(R.color.border));
        layout.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(0, 1.05f);
        view.setLetterSpacing(0);
        return view;
    }

    private Button primaryButton(String label) {
        return styledButton(label, getColorCompat(R.color.accent), getColorCompat(R.color.on_accent));
    }

    private Button warningButton(String label) {
        return styledButton(label, getColorCompat(R.color.warn), getColorCompat(R.color.on_warn));
    }

    private Button secondaryButton(String label) {
        return styledButton(label, getColorCompat(R.color.surface_muted), getColorCompat(R.color.ink));
    }

    private Button textButton(String label) {
        return transparentButton(label, getColorCompat(R.color.accent_text));
    }

    private Button dangerTextButton(String label) {
        return transparentButton(label, getColorCompat(R.color.warn_text));
    }

    private Button transparentButton(String label, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(13);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(dp(34));
        button.setMinimumHeight(dp(34));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private Button styledButton(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = fullWidthLayout();
        params.setMargins(0, dp(10), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams fullWidthLayout() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedLayout(float weight) {
        return new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight);
    }

    private void addTopMargin(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams params = fullWidthLayout();
        params.setMargins(0, dp(topDp), 0, 0);
        parent.addView(child, params);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1));
        params.setMargins(0, dp(12), 0, 0);
        parent.addView(divider, params);
    }

    private LinearLayout buttonRow(Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(first, weightedLayout(1));
        LinearLayout.LayoutParams secondParams = weightedLayout(1);
        secondParams.setMargins(dp(8), 0, 0, 0);
        row.addView(second, secondParams);
        return row;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        form.setPadding(pad, dp(8), pad, 0);
        return form;
    }

    private EditText editText(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setInputType(inputType);
        editText.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        editText.setTextColor(getColorCompat(R.color.ink));
        editText.setHintTextColor(getColorCompat(R.color.muted));
        return editText;
    }

    private ScrollView dialogScroll(View contentView) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(contentView, fullWidthLayout());
        return scroll;
    }

    private LinearLayout modalContainer() {
        LinearLayout modal = new LinearLayout(this);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setPadding(dp(18), dp(16), dp(18), dp(14));
        modal.setBackground(roundedBackground(getColorCompat(R.color.surface), getColorCompat(R.color.border), 10));
        return modal;
    }

    private void styleAlertDialog(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(getColorCompat(R.color.accent_text));
            positive.setAllCaps(false);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setTextColor(getColorCompat(R.color.muted));
            negative.setAllCaps(false);
        }
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(roundedBackground(getColorCompat(R.color.surface), getColorCompat(R.color.border), 10));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.dimAmount = 0.62f;
        window.setAttributes(params);
    }

    private LinearLayout formField(String label, View input, String helper) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(text(label, 13, getColorCompat(R.color.ink), Typeface.BOLD));
        LinearLayout.LayoutParams inputParams = fullWidthLayout();
        inputParams.setMargins(0, dp(6), 0, 0);
        field.addView(input, inputParams);
        if (helper != null && !helper.isEmpty()) {
            addTopMargin(field, text(helper, 12, getColorCompat(R.color.muted), Typeface.NORMAL), 5);
        }
        return field;
    }

    private EditText modernEditText(String hint, int inputType) {
        EditText input = editText(hint, inputType);
        input.setTextSize(15);
        input.setMinHeight(dp(46));
        input.setMinimumHeight(dp(46));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(roundedBackground(getColorCompat(R.color.surface_muted), getColorCompat(R.color.border), 8));
        return input;
    }

    private CheckBox modernCheckBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(getColorCompat(R.color.ink));
        box.setTextSize(14);
        box.setChecked(checked);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinHeight(dp(42));
        box.setMinimumHeight(dp(42));
        box.setPadding(dp(4), 0, dp(10), 0);
        box.setBackground(roundedBackground(getColorCompat(R.color.surface_muted), Color.TRANSPARENT, 8));
        return box;
    }

    private CheckBox compactCheckBox(String label, boolean checked) {
        CheckBox box = modernCheckBox(label, checked);
        box.setTextSize(13);
        box.setMinHeight(dp(38));
        box.setMinimumHeight(dp(38));
        box.setPadding(0, 0, dp(4), 0);
        return box;
    }

    private LinearLayout dayRow(CheckBox... boxes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < boxes.length; i++) {
            LinearLayout.LayoutParams params = weightedLayout(1);
            if (i > 0) {
                params.setMargins(dp(6), 0, 0, 0);
            }
            row.addView(boxes[i], params);
        }
        return row;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView routeMap(List<TripPoint> points) {
        WebView map = new WebView(this);
        map.setBackground(roundedBackground(getColorCompat(R.color.surface_muted), getColorCompat(R.color.border), 8));
        map.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings webSettings = map.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        map.loadDataWithBaseURL(
                "https://tile.openstreetmap.org/",
                routeMapHtml(points),
                "text/html",
                "UTF-8",
                null);
        return map;
    }

    private String routeMapHtml(List<TripPoint> points) {
        String accent = hexColor(getColorCompat(R.color.accent));
        String warn = hexColor(getColorCompat(R.color.warn));
        StringBuilder route = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            TripPoint point = points.get(i);
            if (i > 0) {
                route.append(',');
            }
            route.append('[')
                    .append(String.format(Locale.US, "%.7f", point.latitude))
                    .append(',')
                    .append(String.format(Locale.US, "%.7f", point.longitude))
                    .append(']');
        }
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<style>html,body,#map{height:100%;margin:0;background:#101412;}"
                + ".leaflet-container{font-family:sans-serif;}"
                + ".leaflet-control-attribution{font-size:9px;line-height:1.1;}"
                + ".pin{width:12px;height:12px;border-radius:50%;background:#fff;border:3px solid " + accent + ";box-shadow:0 1px 4px rgba(0,0,0,.35);}"
                + ".pin.end{border-color:" + warn + ";}</style>"
                + "</head><body><div id=\"map\"></div>"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<script>"
                + "var route=[" + route + "];"
                + "var map=L.map('map',{zoomControl:true}).setView(route[0],15);"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);"
                + "var line=L.polyline(route,{color:'" + accent + "',weight:5,opacity:.9,lineCap:'round',lineJoin:'round'}).addTo(map);"
                + "var startIcon=L.divIcon({className:'',html:'<div class=\"pin\"></div>',iconSize:[18,18],iconAnchor:[9,9]});"
                + "var endIcon=L.divIcon({className:'',html:'<div class=\"pin end\"></div>',iconSize:[18,18],iconAnchor:[9,9]});"
                + "L.marker(route[0],{icon:startIcon,title:'Start'}).addTo(map);"
                + "L.marker(route[route.length-1],{icon:endIcon,title:'End'}).addTo(map);"
                + "if(route.length>1){map.fitBounds(line.getBounds(),{padding:[24,24],maxZoom:17,animate:false});}"
                + "</script></body></html>";
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView radiusMapPreview(Location location, EditText radiusInput) {
        WebView map = new WebView(this);
        map.setBackground(roundedBackground(getColorCompat(R.color.surface_muted), getColorCompat(R.color.border), 8));
        map.setOverScrollMode(View.OVER_SCROLL_NEVER);
        map.setVerticalScrollBarEnabled(false);
        map.setHorizontalScrollBarEnabled(false);
        map.setFocusable(false);
        map.setFocusableInTouchMode(false);
        map.setClickable(true);
        map.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        WebSettings webSettings = map.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        map.addJavascriptInterface(new RadiusPreviewBridge(location), "AndroidRadiusPreview");
        map.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                updateRadiusPreview(view, radiusInput);
            }
        });
        radiusInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateRadiusPreview(map, radiusInput);
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        double radiusMeters = previewRadiusMeters(radiusInput);
        map.loadDataWithBaseURL(
                "https://tile.openstreetmap.org/",
                radiusMapHtml(location.getLatitude(), location.getLongitude(), radiusMeters),
                "text/html",
                "UTF-8",
                null);
        return map;
    }

    private void updateRadiusPreview(WebView map, EditText radiusInput) {
        double radiusMeters = previewRadiusMeters(radiusInput);
        map.evaluateJavascript(
                "window.setRadius && window.setRadius("
                        + String.format(Locale.US, "%.2f", radiusMeters)
                        + ");",
                null);
    }

    private double previewRadiusMeters(EditText radiusInput) {
        return Math.max(25, parseDouble(radiusInput.getText().toString(), 150));
    }

    private String radiusMapHtml(double lat, double lng, double radiusMeters) {
        String accent = hexColor(getColorCompat(R.color.accent));
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<style>html,body,#map{height:100%;margin:0;background:#101412;}"
                + ".leaflet-container{font-family:sans-serif;}"
                + ".leaflet-control-attribution{font-size:9px;line-height:1.1;}"
                + ".radius-label{background:rgba(16,20,18,.86);color:#eef2ef;border:1px solid rgba(255,255,255,.18);"
                + "border-radius:6px;padding:4px 7px;font:600 12px sans-serif;box-shadow:none;}</style>"
                + "</head><body><div id=\"map\"></div>"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<script>"
                + "var center=[" + String.format(Locale.US, "%.7f", lat) + "," + String.format(Locale.US, "%.7f", lng) + "];"
                + "var radius=" + String.format(Locale.US, "%.2f", radiusMeters) + ";"
                + "var map=L.map('map',{zoomControl:false,dragging:false,touchZoom:false,scrollWheelZoom:false,doubleClickZoom:false,boxZoom:false,keyboard:false}).setView(center,16);"
                + "L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);"
                + "var circle=L.circle(center,{radius:radius,color:'" + accent + "',weight:2,fillColor:'" + accent + "',fillOpacity:.22}).addTo(map);"
                + "var marker=L.circleMarker(center,{radius:5,color:'#ffffff',weight:2,fillColor:'" + accent + "',fillOpacity:1}).addTo(map);"
                + "var label=L.tooltip({permanent:true,direction:'top',offset:[0,-8],className:'radius-label'}).setLatLng(center).setContent(Math.round(radius)+' m').addTo(map);"
                + "function fit(){map.fitBounds(circle.getBounds(),{padding:[18,18],maxZoom:17,animate:false});}"
                + "function setPicked(lat,lng){center=[lat,lng];circle.setLatLng(center);marker.setLatLng(center);label.setLatLng(center);AndroidRadiusPreview.setPicked(lat,lng);fit();}"
                + "window.setRadius=function(nextRadius){radius=Math.max(25,Number(nextRadius)||150);circle.setRadius(radius);label.setContent(Math.round(radius)+' m');fit();};"
                + "map.on('click',function(e){setPicked(e.latlng.lat,e.latlng.lng);});"
                + "setTimeout(fit,120);"
                + "</script></body></html>";
    }

    private final class RadiusPreviewBridge {
        private final Location location;

        RadiusPreviewBridge(Location location) {
            this.location = location;
        }

        @JavascriptInterface
        public void setPicked(double lat, double lng) {
            runOnUiThread(() -> {
                location.setLatitude(lat);
                location.setLongitude(lng);
            });
        }
    }

    private AutoCompleteTextView categoryInput(String selectedCategory) {
        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(getColorCompat(R.color.ink));
        input.setHintTextColor(getColorCompat(R.color.muted));
        input.setMinHeight(dp(48));
        input.setMinimumHeight(dp(48));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setThreshold(0);
        input.setBackground(roundedBackground(getColorCompat(R.color.surface_muted), getColorCompat(R.color.border), 8));
        input.setDropDownBackgroundDrawable(roundedBackground(getColorCompat(R.color.surface_muted), getColorCompat(R.color.border), 8));
        input.setAdapter(categoryAdapter());
        input.setText(TripCategory.normalize(selectedCategory), false);
        input.setOnClickListener(v -> input.showDropDown());
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                input.showDropDown();
            }
        });
        return input;
    }

    private ArrayAdapter<String> categoryAdapter() {
        return new ArrayAdapter<String>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                categoryOptions()) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return styleCategoryOption(super.getView(position, convertView, parent));
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return styleCategoryOption(super.getDropDownView(position, convertView, parent));
            }
        };
    }

    private View styleCategoryOption(View view) {
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            label.setTextColor(getColorCompat(R.color.ink));
            label.setTextSize(16);
            label.setMinHeight(dp(44));
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(12), 0, dp(12), 0);
            label.setBackgroundColor(getColorCompat(R.color.surface_muted));
        }
        return view;
    }

    private List<String> categoryOptions() {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (String category : TripCategory.ALL) {
            categories.add(category);
        }
        for (PlaceRule place : repository.places()) {
            addCategoryOption(categories, place.category);
        }
        for (TripRecord trip : repository.recentTrips(200)) {
            addCategoryOption(categories, trip.category);
        }
        return new ArrayList<>(categories);
    }

    private void addCategoryOption(LinkedHashSet<String> categories, String rawCategory) {
        String category = TripCategory.normalize(rawCategory);
        if (!category.isEmpty()) {
            categories.add(category);
        }
    }

    private String cleanCategoryInput(CharSequence category) {
        return TripCategory.normalize(category == null ? null : category.toString());
    }

    private String activeDistanceUnit() {
        return settings == null ? DistanceUnit.resolve(DistanceUnit.AUTOMATIC) : settings.resolvedDistanceUnit();
    }

    private String vehicleName(Long vehicleId) {
        if (vehicleId == null || vehicleId <= 0) {
            return "No vehicle";
        }
        return vehicleName(vehicleId.longValue());
    }

    private String vehicleName(long vehicleId) {
        if (vehicleId <= 0) {
            return "No vehicle";
        }
        VehicleRecord vehicle = repository.vehicle(vehicleId);
        if (vehicle != null) {
            return vehicle.label();
        }
        return "Vehicle #" + vehicleId;
    }

    private VehicleRecord defaultVehicle() {
        long vehicleId = settings.defaultVehicleId();
        return vehicleId <= 0 ? null : repository.vehicle(vehicleId);
    }

    private String bluetoothVehicleSummary() {
        List<VehicleRecord> vehicles = repository.bluetoothVehicles();
        if (vehicles.isEmpty()) {
            return "No vehicle Bluetooth set";
        }
        if (vehicles.size() == 1) {
            return vehicles.get(0).label();
        }
        return vehicles.size() + " vehicles";
    }

    private String categoryRateSummary() {
        int count = 0;
        for (Double rate : settings.categoryRates().values()) {
            if (rate != null && rate > 0) {
                count++;
            }
        }
        if (count == 0) {
            return "No custom rates";
        }
        return count == 1 ? "1 custom rate" : count + " custom rates";
    }

    private String bluetoothSummary(String name, String address) {
        String cleanName = name == null ? "" : name.trim();
        String cleanAddress = address == null ? "" : address.trim();
        if (!cleanName.isEmpty() && !cleanAddress.isEmpty()) {
            return cleanName + " / " + cleanAddress;
        }
        if (!cleanName.isEmpty()) {
            return cleanName;
        }
        if (!cleanAddress.isEmpty()) {
            return cleanAddress;
        }
        return "No Bluetooth";
    }

    private String optionalNumber(Double value) {
        return value == null ? "" : String.format(Locale.US, "%.1f", value);
    }

    private Double parseOptionalDouble(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return null;
        }
        double parsed = parseDouble(raw, -1);
        return parsed < 0 ? null : parsed;
    }

    private boolean invalidOptionalOdometer(EditText input) {
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        if (raw.isEmpty()) {
            return false;
        }
        if (parseDouble(raw, -1) >= 0) {
            return false;
        }
        input.setError("Enter a valid odometer value");
        return true;
    }

    private String minutesLabel(int minutes) {
        int clamped = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60);
    }

    private int parseMinutes(String value) {
        if (value == null) {
            return -1;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private long startOfCurrentYear() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.MONTH, Calendar.JANUARY);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private Long parseDate(String value, boolean startOfDay) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(format.parse(value == null ? "" : value.trim()));
            if (startOfDay) {
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
            } else {
                calendar.set(Calendar.HOUR_OF_DAY, 23);
                calendar.set(Calendar.MINUTE, 59);
                calendar.set(Calendar.SECOND, 59);
                calendar.set(Calendar.MILLISECOND, 999);
            }
            return calendar.getTimeInMillis();
        } catch (ParseException exception) {
            return null;
        }
    }

    private GradientDrawable roundedBackground(int color, int strokeColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
    }

    private String hexColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int getColorCompat(int colorRes) {
        return getColor(colorRes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
