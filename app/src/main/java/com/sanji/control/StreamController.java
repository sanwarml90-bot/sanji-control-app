package com.sanji.control;

/**
 * Pure-Java timing for the live-screen stream.
 *
 * The relay does not tell the device when a controller connects or
 * disconnects, so "a controller is connected" is inferred from activity:
 * any message relayed from a controller (a command) proves one is present.
 * Frames are sent only while a controller was seen recently, and paced to
 * ~4 frames per second.
 *
 *  - onControllerMessage(): call whenever a message arrives from the relay
 *    (every relayed message comes from a controller).
 *  - shouldSendFrame(now): true only when a controller is present AND at
 *    least FRAME_INTERVAL_MS has passed since the last frame.
 *  - noteFrameSent(now): call after a frame was actually sent.
 *  - reset(): stop streaming (service stopping).
 *
 * The controller should send a lightweight keepalive (the "ping" command)
 * at least every CONTROLLER_TIMEOUT_MS while it only watches, otherwise
 * the stream pauses after the timeout — this is what keeps mobile data
 * at zero when nobody is watching. No Android APIs here, so this is
 * unit-tested on the JVM (TestStream).
 */
public final class StreamController {

    /** One frame every 250 ms = ~4 frames per second. */
    public static final long FRAME_INTERVAL_MS = 250;

    /** A controller counts as connected for this long after its last message. */
    public static final long CONTROLLER_TIMEOUT_MS = 30_000;

    private long lastControllerActivityMs = -1;
    private long lastFrameSentMs = -1;

    /** Call whenever a message arrives from a controller. */
    public synchronized void onControllerMessage(long nowMs) {
        lastControllerActivityMs = nowMs;
    }

    /** True when a controller has been seen within the timeout window. */
    public synchronized boolean isControllerPresent(long nowMs) {
        return lastControllerActivityMs >= 0
                && (nowMs - lastControllerActivityMs) < CONTROLLER_TIMEOUT_MS;
    }

    /** True when a frame may be sent now: controller present + interval elapsed. */
    public synchronized boolean shouldSendFrame(long nowMs) {
        if (!isControllerPresent(nowMs)) return false;
        return lastFrameSentMs < 0 || (nowMs - lastFrameSentMs) >= FRAME_INTERVAL_MS;
    }

    /** Record that a frame was sent (call after a successful send). */
    public synchronized void noteFrameSent(long nowMs) {
        lastFrameSentMs = nowMs;
    }

    /** Stop streaming (service stopping). */
    public synchronized void reset() {
        lastControllerActivityMs = -1;
        lastFrameSentMs = -1;
    }
}
