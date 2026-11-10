package com.sanji.control;

import org.json.JSONObject;

/**
 * Routes incoming JSON commands to the right handler.
 * Protocol: every command has an "id" string and an "action" string.
 * The dispatcher returns a {@link JSONObject} reply:
 *   {"type":"result","id":...,"ok":true/false,"info":"..."}
 * For "screenshot" it returns a special result the service handles
 * (the screenshot itself is sent as
 * {"type":"screenshot","id":...,"data":"...","sw":...,"sh":...}, where
 * sw/sh are the real screen pixels for tap-coordinate mapping).
 *
 * Actions: tap {x,y}, swipe {x1,y1,x2,y2,durationMs?}, type {text},
 * back {}, home {}, recents {}, open_app {package}, screenshot {},
 * lock {} (remote lock via Device Admin), ping {} (keepalive, returns "pong").
 * Unknown action -> ok false "unknown action".
 *
 * The accessibility actions, the screenshot, and the lock are injected as
 * interfaces so the dispatcher can be unit-tested on the JVM with mocks.
 */
public class CommandDispatcher {

    /** Performs accessibility actions. Implemented by ControlService. */
    public interface ActionHandler {
        ControlAccessibilityService.ActionResult tap(int x, int y);
        ControlAccessibilityService.ActionResult swipe(int x1, int y1, int x2, int y2, long durationMs);
        ControlAccessibilityService.ActionResult typeText(String text);
        ControlAccessibilityService.ActionResult pressBack();
        ControlAccessibilityService.ActionResult pressHome();
        ControlAccessibilityService.ActionResult pressRecents();
        ControlAccessibilityService.ActionResult openApp(String packageName);
    }

    /** Captures a screenshot. Implemented by ControlService (via ScreenCapture). */
    public interface ScreenshotHandler {
        /** Returns base64 JPEG, or null when capture is not available. */
        String captureScreenshot();

        /** Real screen size in pixels {width, height} — the values the
         * panel needs to map tap coordinates from image space back to
         * real screen pixels. Default {0,0} (unknown): older handlers
         * that do not override this simply send no sw/sh. */
        default int[] screenSize() {
            return new int[]{0, 0};
        }
    }

    /** Locks the device (DevicePolicyManager.lockNow). Implemented by ControlService. */
    public interface LockHandler {
        /** Attempt to lock the device now. */
        LockResult lockNow();
    }

    /** Result of a lock attempt: ok + info. */
    public static final class LockResult {
        public final boolean ok;
        public final String info;

        public LockResult(boolean ok, String info) {
            this.ok = ok;
            this.info = info;
        }
    }

    /** Result of dispatching one command. */
    public static final class DispatchResult {
        /** The JSON reply to send (a "result" message), or null when the
         *  handler produces its own reply (screenshot). */
        public final JSONObject reply;
        /** True when this command is a screenshot request. */
        public final boolean isScreenshot;

        public DispatchResult(JSONObject reply, boolean isScreenshot) {
            this.reply = reply;
            this.isScreenshot = isScreenshot;
        }
    }

    private final ActionHandler actions;
    private final ScreenshotHandler screenshots;
    private final LockHandler lock;

    public CommandDispatcher(ActionHandler actions, ScreenshotHandler screenshots) {
        this(actions, screenshots,
                () -> new LockResult(false, "device admin not enabled"));
    }

    public CommandDispatcher(ActionHandler actions, ScreenshotHandler screenshots,
                             LockHandler lock) {
        this.actions = actions;
        this.screenshots = screenshots;
        this.lock = lock;
    }

    /** Dispatch one raw JSON command string. Never throws. */
    public DispatchResult dispatch(String rawJson) {
        String id = "";
        try {
            JSONObject cmd = new JSONObject(rawJson);
            id = cmd.optString("id", "");
            String action = cmd.optString("action", "");
            switch (action) {
                case "tap": {
                    int x = cmd.optInt("x", -1);
                    int y = cmd.optInt("y", -1);
                    if (x < 0 || y < 0) {
                        return result(id, false, "tap needs x and y");
                    }
                    return fromAction(id, actions.tap(x, y));
                }
                case "swipe": {
                    int x1 = cmd.optInt("x1", -1);
                    int y1 = cmd.optInt("y1", -1);
                    int x2 = cmd.optInt("x2", -1);
                    int y2 = cmd.optInt("y2", -1);
                    long dur = cmd.optLong("durationMs", 300);
                    if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                        return result(id, false, "swipe needs x1,y1,x2,y2");
                    }
                    return fromAction(id, actions.swipe(x1, y1, x2, y2, dur));
                }
                case "type": {
                    String text = cmd.optString("text", "");
                    if (text.isEmpty()) {
                        return result(id, false, "type needs text");
                    }
                    return fromAction(id, actions.typeText(text));
                }
                case "back":
                    return fromAction(id, actions.pressBack());
                case "home":
                    return fromAction(id, actions.pressHome());
                case "recents":
                    return fromAction(id, actions.pressRecents());
                case "open_app": {
                    String pkg = cmd.optString("package", "");
                    if (pkg.isEmpty()) {
                        return result(id, false, "open_app needs package");
                    }
                    return fromAction(id, actions.openApp(pkg));
                }
                case "screenshot":
                    // The service sends the screenshot (or an error result)
                    // itself; the dispatcher only flags the request.
                    return new DispatchResult(null, true);
                case "lock": {
                    LockResult r = lock.lockNow();
                    return result(id, r.ok, r.info);
                }
                case "ping":
                    // Keepalive: proves a controller is present (keeps the
                    // live-screen stream going) and checks the round trip.
                    return result(id, true, "pong");
                default:
                    return result(id, false, "unknown action");
            }
        } catch (Exception e) {
            return result(id, false, "bad json: " + e.getMessage());
        }
    }

    /** Build the screenshot reply: {"type":"screenshot","id":...,"data":"...","sw":...,"sh":...} or an error result. */
    public JSONObject screenshotReply(String id) {
        try {
            String data = screenshots.captureScreenshot();
            if (data == null) {
                return resultJson(id, false, "screen capture not allowed");
            }
            JSONObject reply = new JSONObject();
            reply.put("type", "screenshot");
            reply.put("id", id);
            reply.put("data", data);
            // Real screen size so the panel can scale tap coordinates
            // from image space to real screen pixels. Only when known
            // (positive); handlers that do not report a size send none.
            int[] size = screenshots.screenSize();
            if (size != null && size.length >= 2 && size[0] > 0 && size[1] > 0) {
                reply.put("sw", size[0]);
                reply.put("sh", size[1]);
            }
            return reply;
        } catch (Exception e) {
            return resultJson(id, false, "screenshot failed: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    private DispatchResult fromAction(String id, ControlAccessibilityService.ActionResult r) {
        return result(id, r.ok, r.info);
    }

    private DispatchResult result(String id, boolean ok, String info) {
        return new DispatchResult(resultJson(id, ok, info), false);
    }

    private JSONObject resultJson(String id, boolean ok, String info) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("type", "result");
            reply.put("id", id);
            reply.put("ok", ok);
            reply.put("info", info);
            return reply;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
