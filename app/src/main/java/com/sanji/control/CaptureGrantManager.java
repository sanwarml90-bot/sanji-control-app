package com.sanji.control;

/**
 * Pure-Java state machine for MediaProjection grants.
 *
 * Two bugs it prevents (both bit the live phone — every screenshot came
 * back "screen capture not allowed" even after the user tapped "Start now"):
 *
 *  1. ORDER: on Android 14+ (API 34) MediaProjectionManager.getMediaProjection()
 *     throws SecurityException unless the service is ALREADY in the foreground
 *     with the mediaProjection type. So the grant must be judged from the
 *     intent's *shape* (result OK + data non-null) first, startForeground()
 *     called with the matching type, and only then may a ScreenCapture be
 *     created. This class never touches Android APIs; ControlService enforces
 *     that order and it is verified by TestGrantManager.
 *
 *  2. STALE GRANT: if the user toggles OFF then ON quickly, the old service
 *     may still be alive when the new start intent (with a fresh grant)
 *     arrives. The old code early-returned when already running and silently
 *     dropped the new grant. Every onStart() call is therefore processed.
 *     v11: a valid grant ALWAYS (re)installs the capture — the old rule
 *     ("only when none installed") dropped re-grants once a capture was
 *     marked installed, even when that capture was broken (no frames), so
 *     screenshots failed forever no matter how often the user re-granted.
 *     onStartCommand releases the old ScreenCapture before creating the
 *     new one, so reinstalling is safe.
 *
 * Main-thread only (service lifecycle callbacks run on the main thread),
 * so plain booleans are enough — no atomics.
 */
public final class CaptureGrantManager {

    /** What one onStartCommand call should do. */
    public static final class Decision {
        /** True when the service was not running before this start. */
        public final boolean firstStart;
        /** True when a ScreenCapture must be (re)created now. */
        public final boolean installCapture;

        Decision(boolean firstStart, boolean installCapture) {
            this.firstStart = firstStart;
            this.installCapture = installCapture;
        }
    }

    private boolean running;
    private boolean captureInstalled;

    /**
     * Process one start intent. grantOk = the intent carries a valid
     * MediaProjection result (resultCode OK + data non-null) — judged from
     * the intent alone, WITHOUT calling getMediaProjection().
     */
    public Decision onStart(boolean grantOk) {
        boolean firstStart = !running;
        running = true;
        // v11: a valid grant ALWAYS (re)installs the capture. The old rule
        // ("only install when none exists") dropped re-grants: once a
        // capture was marked installed, a fresh "Start now" was ignored even
        // when the installed capture was broken (VirtualDisplay dead / no
        // frames), so screenshots failed forever no matter how often the
        // user re-granted. Re-installing is safe — onStartCommand releases
        // the old ScreenCapture first — and grants are rare (only when the
        // user taps ALLOW SCREEN CAPTURE). A start WITHOUT a grant never
        // installs, so auto-restarts (boot / swipe-away) are unaffected.
        boolean install = grantOk;
        return new Decision(firstStart, install);
    }

    /** Record whether the ScreenCapture creation succeeded. */
    public void noteCaptureInstalled(boolean ok) {
        captureInstalled = ok;
    }

    /** Called from onDestroy: the service is fully stopped. */
    public void reset() {
        running = false;
        captureInstalled = false;
    }

    /** True while the service is running. */
    public boolean isRunning() {
        return running;
    }

    /** True when a working capture is installed. */
    public boolean hasCapture() {
        return captureInstalled;
    }
}
