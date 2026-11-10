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
 *     dropped the new grant. Every onStart() call is therefore processed: a
 *     valid grant installs (or re-installs) the capture whenever no working
 *     capture exists yet.
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
        // Install whenever a valid grant arrives and no working capture
        // exists yet — including on a restart while already running.
        boolean install = grantOk && !captureInstalled;
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
