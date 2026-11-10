package com.sanji.control;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Single-screen UI: a BIG ON/OFF toggle + a status line
 * (OFF / Connecting / ON + relay URL + data used this session)
 * + a short setup checklist (Accessibility Service, Screen capture,
 * Device Admin for remote lock) with buttons that open the right
 * Settings screens.
 *
 * ON flow: Accessibility must be enabled (opens Settings if not);
 * then the screen-capture permission is requested; the ControlService
 * starts with the capture result. Screen capture is optional — if the
 * user denies it, control still works but screenshots reply
 * "screen capture not allowed". The screen permission is asked EVERY
 * time the app is turned ON (Android privacy rule — device admin does
 * not make it permanent).
 * OFF: stops the service and closes the socket (zero data when OFF).
 * No login, no account.
 *
 * Auto-resume: if the app was ON when it was swiped away from recents
 * or killed (persisted wasOn), reopening the app restarts the service
 * by itself in dataSync mode — taps keep working, screenshots need a
 * fresh grant (turn OFF then ON, tap "Start now"). When the user turned
 * the app OFF, reopening it stays OFF.
 */
public class MainActivity extends Activity {

    private static final int RC_CAPTURE = 1001;
    private static final int RC_NOTIFICATION = 1002;

    private Button toggleButton;
    private TextView statusText;
    private TextView relayText;
    private TextView dataText;
    private TextView uidText;
    private TextView accessibilityStatus;
    private TextView captureStatus;
    private TextView deviceAdminStatus;
    private TextView batteryStatus;

    /** True when the user tapped ON but accessibility was not enabled yet. */
    private boolean pendingOn = false;
    /** True when screen capture was granted in the current ON session. */
    private boolean captureGrantedThisSession = false;

    private final AppState.Listener stateListener = this::refreshUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggleButton);
        statusText = findViewById(R.id.statusText);
        relayText = findViewById(R.id.relayText);
        dataText = findViewById(R.id.dataText);
        uidText = findViewById(R.id.uidText);
        accessibilityStatus = findViewById(R.id.accessibilityStatusText);
        captureStatus = findViewById(R.id.captureStatusText);
        deviceAdminStatus = findViewById(R.id.deviceAdminStatusText);
        batteryStatus = findViewById(R.id.batteryStatusText);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button captureButton = findViewById(R.id.captureButton);
        Button deviceAdminButton = findViewById(R.id.deviceAdminButton);
        Button batteryButton = findViewById(R.id.batteryButton);
        Button appSettingsButton = findViewById(R.id.appSettingsButton);

        toggleButton.setOnClickListener(v -> onToggleClicked());
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        captureButton.setOnClickListener(v -> requestCapturePermission());
        deviceAdminButton.setOnClickListener(v -> openDeviceAdminActivation());
        batteryButton.setOnClickListener(v -> openBatteryExemption());
        appSettingsButton.setOnClickListener(v -> openAppSettings());
        uidText.setOnClickListener(v -> copyUidToClipboard());

        AppState.get().addListener(stateListener);

        // Auto-resume after swipe-away / kill: if the app was ON when it
        // died (wasOn persisted), restart the service when the user
        // reopens the app — no ON tap needed. DataSync mode (no screen
        // capture): the grant died with the old process, so taps work but
        // screenshots need a fresh grant — the status line says so. When
        // wasOn is false (user turned OFF) the app stays OFF and uses
        // zero data. The capture permission is NOT auto-requested here
        // (it would be annoying); the user re-ONs for that.
        if (ControlService.shouldAutoStart(Prefs.wasOn(this), ControlService.isRunning())) {
            captureGrantedThisSession = false;
            ControlService.start(this, 0, null);
            AppState.get().setState(AppState.State.CONNECTING);
        }

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the user just enabled Accessibility in Settings, continue the ON flow.
        if (pendingOn && ControlAccessibilityService.isEnabled()) {
            pendingOn = false;
            requestCapturePermission();
        }
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppState.get().removeListener(stateListener);
    }

    // ---- Toggle ----

    private void onToggleClicked() {
        AppState.State state = AppState.get().getState();
        if (state == AppState.State.OFF) {
            startOnFlow();
        } else {
            // Turn OFF: stop the service (socket closes, zero data).
            ControlService.stop(this);
            Prefs.setWasOn(this, false);
            AppState.get().setState(AppState.State.OFF);
            AppState.get().resetCounters();
            captureGrantedThisSession = false;
            pendingOn = false;
            refreshUi();
        }
    }

    /** Begin the ON flow: notification permission, then Accessibility, then capture. */
    private void startOnFlow() {
        // Tip (never blocks the ON flow): without the battery exemption
        // Oppo/ColorOS may kill the app in the background, and it will
        // have to be turned ON again.
        if (!isBatteryExempt()) {
            Toast.makeText(this,
                    "Tip: allow background running below, so the app stays alive",
                    Toast.LENGTH_LONG).show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        RC_NOTIFICATION);
                return;
            }
        }
        if (!ControlAccessibilityService.isEnabled()) {
            pendingOn = true;
            Toast.makeText(this,
                    "Enable the Sanji Control accessibility service, then come back",
                    Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        requestCapturePermission();
    }

    /** Request the MediaProjection screen-capture permission. */
    private void requestCapturePermission() {
        Intent intent = ScreenCapture.captureIntent(this);
        if (intent == null) {
            // MediaProjection unavailable; start without capture.
            startControlService(0, null);
            return;
        }
        startActivityForResult(intent, RC_CAPTURE);
    }

    private void startControlService(int captureResultCode, Intent captureData) {
        captureGrantedThisSession =
                ScreenCapture.isCaptureResultOk(captureResultCode, captureData);
        // Remember ON so the boot receiver can restart the service after
        // a reboot (taps/typing keep working; screen needs "Start now").
        Prefs.setWasOn(this, true);
        ControlService.start(this, captureResultCode, captureData);
        AppState.get().setState(AppState.State.CONNECTING);
        refreshUi();
    }

    // ---- Activity results ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_CAPTURE) {
            // Start the service with (or without) the capture grant.
            startControlService(resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_NOTIFICATION) {
            // Continue the ON flow regardless of the notification grant.
            startOnFlow();
        }
    }

    // ---- Settings ----

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show();
        }
    }

    /** Open the system device-admin activation screen (for remote lock only). */
    private void openDeviceAdminActivation() {
        try {
            ComponentName admin = new ComponentName(this, ControlDeviceAdmin.class);
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_explanation));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Device Admin settings", Toast.LENGTH_SHORT).show();
        }
    }

    /** True when the user enabled Device Admin for this app. */
    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null
                && dpm.isAdminActive(new ComponentName(this, ControlDeviceAdmin.class));
    }

    /** True when the app is exempt from battery optimization (stays alive). */
    private boolean isBatteryExempt() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Open the system screen that exempts this app from battery optimization. */
    private void openBatteryExemption() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open battery settings",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Open this app's system settings page (on Oppo/ColorOS this is where
     * "Auto-start" and "Allow background running" live).
     */
    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open app settings",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Copy this phone's UID to the clipboard (paste it in the control panel). */
    private void copyUidToClipboard() {
        try {
            String uid = DeviceId.getUid(this);
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Phone UID", uid));
            Toast.makeText(this, "UID copied: " + uid, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not copy UID", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- UI ----

    private void refreshUi() {
        if (toggleButton == null) return;
        AppState.State state = AppState.get().getState();
        AppState appState = AppState.get();

        switch (state) {
            case OFF:
                toggleButton.setText("TURN ON");
                break;
            case CONNECTING:
                toggleButton.setText("CONNECTING… (tap to turn OFF)");
                break;
            case ON:
                toggleButton.setText("TURN OFF");
                break;
        }

        String stateLabel;
        switch (state) {
            case OFF:
                stateLabel = "Status: OFF — using zero mobile data";
                break;
            case CONNECTING:
                stateLabel = "Status: Connecting…";
                break;
            case ON:
                stateLabel = "Status: ON — remote control active";
                break;
            default:
                stateLabel = "Status: OFF — using zero mobile data";
        }
        statusText.setText(stateLabel);
        relayText.setText(Config.RELAY_URL);
        dataText.setText("Data used this session: Sent "
                + AppState.formatBytes(appState.getBytesSent())
                + " · Received " + AppState.formatBytes(appState.getBytesReceived()));

        // Permanent UID of this phone — enter it in the control panel to
        // pick this phone. Tap it to copy.
        uidText.setText("Phone UID: " + DeviceId.getUid(this));

        boolean a11y = ControlAccessibilityService.isEnabled();
        accessibilityStatus.setText("Accessibility Service: " + (a11y ? "ON" : "OFF"));
        String captureLabel;
        if (captureGrantedThisSession) {
            captureLabel = "Allowed";
        } else if (state != AppState.State.OFF) {
            // Service is running without a capture grant (auto-restart
            // after swipe-away / reboot): taps work, but screenshots
            // need a fresh grant — turn OFF then ON and tap "Start now"
            // in the system dialog.
            captureLabel = "Not allowed — turn OFF then ON, tap Start now for screen";
        } else {
            captureLabel = "Not allowed";
        }
        captureStatus.setText("Screen capture: " + captureLabel);
        deviceAdminStatus.setText("Device Admin (for remote lock): "
                + (isDeviceAdminActive() ? "ON" : "OFF"));
        batteryStatus.setText("Battery optimization: "
                + (isBatteryExempt()
                        ? "Exempt — app stays in the background"
                        : "ON — app may be killed in the background"));
    }
}
