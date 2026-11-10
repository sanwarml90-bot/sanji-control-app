import com.sanji.control.AppState;
import com.sanji.control.CommandDispatcher;
import com.sanji.control.ControlAccessibilityService;
import com.sanji.control.ScreenCapture;
import org.json.JSONObject;

/**
 * JVM smoke test for the Sanji Control command dispatcher + app state.
 * No device, no Android runtime: uses the real org.json library
 * (json-20240303.jar, before android.jar on the classpath so the real
 * JSONObject wins over android.jar's stub) and mock action handlers.
 *
 * Checks:
 *  1. Every action name routes to the right handler.
 *  2. Unknown action -> ok false "unknown action".
 *  3. Screenshot returns ok false when MediaProjection is not granted
 *     (ScreenshotHandler returns null).
 *  4. Screenshot returns the base64 data when capture is available.
 *  5. AppState ON/OFF transitions + byte formatting.
 *  6. ScreenCapture constants (max width 540, JPEG quality 50).
 *  7. Lock routes to the LockHandler; the default handler (no device
 *     admin) replies ok false "device admin not enabled".
 *  8. ping -> ok true "pong" (keepalive for the live stream).
 *  9. Screenshot messages carry "sw"/"sh" — the real screen size in
 *     pixels (e.g. 720x1456) so the panel can map taps from image
 *     space back to real screen pixels; handlers that do not report a
 *     size send no sw/sh (backward compatible).
 */
public class TestDispatcher {

    private static int passed = 0;
    private static int failed = 0;

    /** Mock action handler that records the last call. */
    static class MockActions implements CommandDispatcher.ActionHandler {
        String lastCall = "";
        String lastArg = "";

        private ControlAccessibilityService.ActionResult rec(String call, String arg) {
            lastCall = call;
            lastArg = arg;
            return ControlAccessibilityService.ActionResult.ok(call + " " + arg);
        }

        @Override
        public ControlAccessibilityService.ActionResult tap(int x, int y) {
            return rec("tap", x + "," + y);
        }

        @Override
        public ControlAccessibilityService.ActionResult swipe(int x1, int y1, int x2, int y2, long d) {
            return rec("swipe", x1 + "," + y1 + "->" + x2 + "," + y2 + "@" + d);
        }

        @Override
        public ControlAccessibilityService.ActionResult typeText(String text) {
            return rec("type", text);
        }

        @Override
        public ControlAccessibilityService.ActionResult pressBack() {
            return rec("back", "");
        }

        @Override
        public ControlAccessibilityService.ActionResult pressHome() {
            return rec("home", "");
        }

        @Override
        public ControlAccessibilityService.ActionResult pressRecents() {
            return rec("recents", "");
        }

        @Override
        public ControlAccessibilityService.ActionResult openApp(String pkg) {
            return rec("open_app", pkg);
        }
    }

    /** Mock screenshot handler reporting a real screen size (720x1456,
     * the phone's real display) alongside the base64 data. */
    static class MockShot implements CommandDispatcher.ScreenshotHandler {
        @Override
        public String captureScreenshot() {
            return "FAKEBASE64DATA";
        }

        @Override
        public int[] screenSize() {
            return new int[]{720, 1456};
        }
    }

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        MockActions actions = new MockActions();

        // --- Screenshot NOT granted (handler returns null) ---
        CommandDispatcher dNoShot = new CommandDispatcher(actions, () -> null);

        // 1. tap routes to tap
        CommandDispatcher.DispatchResult r = dNoShot.dispatch(
                "{\"id\":\"1\",\"action\":\"tap\",\"x\":100,\"y\":200}");
        check("tap routes to tap handler",
                r.reply != null && "tap".equals(actions.lastCall) && "100,200".equals(actions.lastArg)
                        && r.reply.getBoolean("ok") && "1".equals(r.reply.getString("id")));

        // 2. swipe routes to swipe (with durationMs)
        r = dNoShot.dispatch(
                "{\"id\":\"2\",\"action\":\"swipe\",\"x1\":10,\"y1\":20,\"x2\":30,\"y2\":40,\"durationMs\":500}");
        check("swipe routes to swipe handler",
                r.reply != null && "swipe".equals(actions.lastCall)
                        && "10,20->30,40@500".equals(actions.lastArg));

        // 3. swipe defaults durationMs to 300 when omitted
        r = dNoShot.dispatch(
                "{\"id\":\"3\",\"action\":\"swipe\",\"x1\":0,\"y1\":0,\"x2\":9,\"y2\":9}");
        check("swipe defaults durationMs=300",
                r.reply != null && "swipe".equals(actions.lastCall)
                        && actions.lastArg.endsWith("@300"));

        // 4. type routes to type
        r = dNoShot.dispatch("{\"id\":\"4\",\"action\":\"type\",\"text\":\"hello\"}");
        check("type routes to type handler",
                r.reply != null && "type".equals(actions.lastCall) && "hello".equals(actions.lastArg));

        // 5. back / home / recents
        r = dNoShot.dispatch("{\"id\":\"5\",\"action\":\"back\"}");
        check("back routes to pressBack",
                r.reply != null && "back".equals(actions.lastCall));
        r = dNoShot.dispatch("{\"id\":\"6\",\"action\":\"home\"}");
        check("home routes to pressHome",
                r.reply != null && "home".equals(actions.lastCall));
        r = dNoShot.dispatch("{\"id\":\"7\",\"action\":\"recents\"}");
        check("recents routes to pressRecents",
                r.reply != null && "recents".equals(actions.lastCall));

        // 6. open_app routes to openApp
        r = dNoShot.dispatch(
                "{\"id\":\"8\",\"action\":\"open_app\",\"package\":\"com.whatsapp\"}");
        check("open_app routes to openApp",
                r.reply != null && "open_app".equals(actions.lastCall)
                        && "com.whatsapp".equals(actions.lastArg));

        // 7. unknown action -> ok false "unknown action"
        r = dNoShot.dispatch("{\"id\":\"9\",\"action\":\"explode\"}");
        check("unknown action -> ok false 'unknown action'",
                r.reply != null && !r.reply.getBoolean("ok")
                        && "unknown action".equals(r.reply.getString("info")));

        // 8. bad json -> ok false
        r = dNoShot.dispatch("not json at all");
        check("bad json -> ok false",
                r.reply != null && !r.reply.getBoolean("ok"));

        // 9. tap missing x/y -> ok false
        r = dNoShot.dispatch("{\"id\":\"10\",\"action\":\"tap\"}");
        check("tap without x/y -> ok false",
                r.reply != null && !r.reply.getBoolean("ok"));

        // 10. screenshot flagged as screenshot (not a plain result)
        r = dNoShot.dispatch("{\"id\":\"11\",\"action\":\"screenshot\"}");
        check("screenshot is flagged isScreenshot",
                r.isScreenshot && r.reply == null);

        // 11. screenshot when NOT granted -> ok false "screen capture not allowed"
        JSONObject shotReply = dNoShot.screenshotReply("11");
        check("screenshot not granted -> ok false 'screen capture not allowed'",
                shotReply != null && "result".equals(shotReply.getString("type"))
                        && !shotReply.getBoolean("ok")
                        && "screen capture not allowed".equals(shotReply.getString("info")));

        // 12. screenshot when granted -> returns base64 data
        CommandDispatcher dShot = new CommandDispatcher(actions, new MockShot());
        JSONObject shotOk = dShot.screenshotReply("12");
        check("screenshot granted -> type screenshot with data",
                shotOk != null && "screenshot".equals(shotOk.getString("type"))
                        && "12".equals(shotOk.getString("id"))
                        && "FAKEBASE64DATA".equals(shotOk.getString("data")));

        // 12b. screenshot message carries sw/sh = the real screen size
        check("screenshot granted -> sw/sh equal real screen size (720x1456)",
                shotOk != null && shotOk.getInt("sw") == 720 && shotOk.getInt("sh") == 1456);
        check("screenshot sw/sh are positive integers",
                shotOk != null && shotOk.getInt("sw") > 0 && shotOk.getInt("sh") > 0);

        // 12c. handler without a screenSize() override (plain lambda) ->
        // no sw/sh keys (backward compatible: old handlers keep working)
        CommandDispatcher dShotNoSize =
                new CommandDispatcher(actions, () -> "FAKEBASE64DATA");
        JSONObject shotNoSize = dShotNoSize.screenshotReply("13");
        check("screenshot without screenSize override -> no sw/sh keys",
                shotNoSize != null && "screenshot".equals(shotNoSize.getString("type"))
                        && "FAKEBASE64DATA".equals(shotNoSize.getString("data"))
                        && !shotNoSize.has("sw") && !shotNoSize.has("sh"));

        // 13. AppState OFF -> CONNECTING -> ON -> OFF transitions
        AppState st = AppState.get();
        st.setState(AppState.State.OFF);
        check("AppState starts OFF", st.getState() == AppState.State.OFF);
        st.setState(AppState.State.CONNECTING);
        check("AppState CONNECTING", st.getState() == AppState.State.CONNECTING);
        st.setState(AppState.State.ON);
        check("AppState ON", st.getState() == AppState.State.ON);
        st.setState(AppState.State.OFF);
        check("AppState back to OFF", st.getState() == AppState.State.OFF);

        // 14. Byte counters + formatting
        st.resetCounters();
        st.addSent(500);
        st.addReceived(2048);
        check("byte counters accumulate",
                st.getBytesSent() == 500 && st.getBytesReceived() == 2048);
        check("formatBytes 500 -> 500 B", "500 B".equals(AppState.formatBytes(500)));
        check("formatBytes 2048 -> 2.0 KB", "2.0 KB".equals(AppState.formatBytes(2048)));
        check("formatBytes 3MB -> 3.00 MB",
                "3.00 MB".equals(AppState.formatBytes(3L * 1024 * 1024)));

        // 15. ScreenCapture data-saving constants
        check("ScreenCapture max width 540", ScreenCapture.maxWidth() == 540);
        check("ScreenCapture JPEG quality 50", ScreenCapture.jpegQuality() == 50);

        // 16. Relay URL is wss (TLS) and points at the real relay
        check("Config.RELAY_URL is wss://sanji-control-relay-production.up.railway.app",
                "wss://sanji-control-relay-production.up.railway.app"
                        .equals(com.sanji.control.Config.RELAY_URL));

        // 17. Accessibility guard: real service not enabled on JVM -> ok false
        ControlAccessibilityService.ActionResult ar =
                ControlAccessibilityService.tap(1, 1);
        check("tap with accessibility off -> ok false 'accessibility off'",
                !ar.ok && "accessibility off".equals(ar.info));

        // 18. lock routes to the injected LockHandler (ok true)
        CommandDispatcher.LockHandler mockLockOk =
                () -> new CommandDispatcher.LockResult(true, "device locked");
        CommandDispatcher dLock = new CommandDispatcher(actions, () -> null, mockLockOk);
        r = dLock.dispatch("{\"id\":\"20\",\"action\":\"lock\"}");
        check("lock routes to lock handler (ok true)",
                r.reply != null && r.reply.getBoolean("ok")
                        && "device locked".equals(r.reply.getString("info")));

        // 19. lock with the default handler (no device admin) -> ok false
        r = dNoShot.dispatch("{\"id\":\"21\",\"action\":\"lock\"}");
        check("lock with default handler -> ok false 'device admin not enabled'",
                r.reply != null && !r.reply.getBoolean("ok")
                        && "device admin not enabled".equals(r.reply.getString("info")));

        // 20. ping -> ok true "pong" (keepalive for the live stream)
        r = dNoShot.dispatch("{\"id\":\"22\",\"action\":\"ping\"}");
        check("ping -> ok true 'pong'",
                r.reply != null && r.reply.getBoolean("ok")
                        && "pong".equals(r.reply.getString("info")));

        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }
}
