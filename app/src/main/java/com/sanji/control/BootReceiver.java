package com.sanji.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the control service after the phone reboots — but ONLY if the
 * app was ON when the phone shut down (persisted "was_on" flag in
 * SharedPreferences, set by MainActivity on ON/OFF).
 *
 * The restart is in dataSync mode (no screen capture): the MediaProjection
 * grant is single-use and gone after a reboot, so taps/typing keep
 * working after boot, but the screen needs "Start now" again — the user
 * turns the app ON in MainActivity to re-grant it (the app's status
 * line says so).
 *
 * BOOT_COMPLETED is one of the broadcasts that lets an app start a
 * foreground service from the background; the start is still wrapped in
 * try/catch so a ForegroundServiceStartNotAllowedException on some
 * devices cannot crash the receiver.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SanjiBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!shouldRestart(Prefs.wasOn(context))) return;
        try {
            // No capture grant (0, null) -> dataSync mode: control works,
            // screenshots wait for the user to re-grant in MainActivity.
            ControlService.start(context, 0, null);
            Log.i(TAG, "restarted ControlService after boot (was ON)");
        } catch (Exception e) {
            Log.w(TAG, "boot restart failed: " + e.getMessage());
        }
    }

    /** Pure decision (testable on the JVM): restart only when was ON. */
    public static boolean shouldRestart(boolean wasOn) {
        return wasOn;
    }
}
