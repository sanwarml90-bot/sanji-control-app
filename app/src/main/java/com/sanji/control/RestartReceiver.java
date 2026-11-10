package com.sanji.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the control service when the app is swiped away from recents
 * (ControlService.onTaskRemoved sends this broadcast) — but ONLY if the
 * app was ON when it was swiped away (persisted "was_on" flag in
 * SharedPreferences, set by MainActivity on ON/OFF).
 *
 * The restart is in dataSync mode (no screen capture): the MediaProjection
 * grant is single-use and gone with the old process, so taps/typing keep
 * working, but the screen needs "Start now" again — the user turns the
 * app OFF then ON in MainActivity to re-grant it (the app's status line
 * says so).
 *
 * This is a second chance, not a guarantee: on Oppo/ColorOS a swipe can
 * force-stop the app, and a force-stopped app receives no broadcasts
 * until the user launches it again. That case is covered by
 * MainActivity.onCreate, which auto-starts the service when the user
 * reopens the app and wasOn is true.
 *
 * The start is wrapped in try/catch so a
 * ForegroundServiceStartNotAllowedException (Android 12+ background-start
 * restrictions) cannot crash the receiver.
 */
public class RestartReceiver extends BroadcastReceiver {

    private static final String TAG = "SanjiRestart";

    /** Action for the restart broadcast (explicit intents only). */
    public static final String ACTION_RESTART = "com.sanji.control.ACTION_RESTART_SERVICE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_RESTART.equals(intent.getAction())) {
            return;
        }
        if (!shouldRestart(Prefs.wasOn(context))) return;
        try {
            // No capture grant (0, null) -> dataSync mode: control works,
            // screenshots wait for the user to re-grant in MainActivity.
            ControlService.start(context, 0, null);
            Log.i(TAG, "restarted ControlService after task removed (was ON)");
        } catch (Exception e) {
            Log.w(TAG, "task-removed restart failed: " + e.getMessage());
        }
    }

    /** Pure decision (testable on the JVM): restart only when was ON. */
    public static boolean shouldRestart(boolean wasOn) {
        return wasOn;
    }

    /** Explicit restart intent for this receiver. */
    public static Intent restartIntent(Context context) {
        Intent intent = new Intent(context, RestartReceiver.class);
        intent.setAction(ACTION_RESTART);
        return intent;
    }
}
