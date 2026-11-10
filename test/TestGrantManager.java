import com.sanji.control.CaptureGrantManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JVM tests for the MediaProjection grant fixes in ControlService.
 * No device, no Android runtime.
 *
 * Part A — CaptureGrantManager decisions (pure Java):
 *  1. First start without a grant -> firstStart, no install.
 *  2. First start with a grant -> firstStart + install.
 *  3. KEY FIX: second start with a valid grant while already running and
 *     no capture installed -> install (the old code early-returned and
 *     silently dropped the new grant).
 *  4. Grant while a capture is already installed -> no reinstall.
 *  5. Restart without a grant -> nothing to do.
 *  6. reset() (onDestroy) -> fresh start again.
 *
 * Part B — source-order verification of ControlService.java:
 *  startForeground() must be called BEFORE any ScreenCapture is created
 *  (API 34 rule: getMediaProjection() throws unless the service is already
 *  foreground with the mediaProjection type), and no early-return on
 *  already-running may remain.
 */
public class TestGrantManager {

    private static int passed = 0;
    private static int failed = 0;

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
        // --- Part A: CaptureGrantManager decisions ---

        // 1. Fresh manager, no grant.
        CaptureGrantManager g = new CaptureGrantManager();
        CaptureGrantManager.Decision d = g.onStart(false);
        check("first start without grant -> firstStart, no install",
                d.firstStart && !d.installCapture);
        check("manager reports running after first start", g.isRunning());
        check("manager reports no capture", !g.hasCapture());

        // 2. Fresh manager, valid grant.
        CaptureGrantManager g2 = new CaptureGrantManager();
        d = g2.onStart(true);
        check("first start with grant -> firstStart + install",
                d.firstStart && d.installCapture);

        // 3. KEY FIX: install failed / not installed yet, second start with a
        // valid grant while already running must still install it.
        g2.noteCaptureInstalled(false);
        d = g2.onStart(true);
        check("second start with grant while running -> install (not ignored)",
                !d.firstStart && d.installCapture);

        // 4. v11: a valid grant ALWAYS reinstalls, even when a capture is
        // already installed — the old capture may be broken (no frames) and
        // a re-grant must replace it, not be dropped.
        g2.noteCaptureInstalled(true);
        d = g2.onStart(true);
        check("grant while capture installed -> reinstall (replace broken capture)",
                d.installCapture);
        check("manager reports capture installed", g2.hasCapture());

        // 5. Restart without a grant -> nothing to do.
        d = g2.onStart(false);
        check("restart without grant -> no first start, no install",
                !d.firstStart && !d.installCapture);

        // 6. reset() (onDestroy) -> fresh start again.
        g2.reset();
        check("reset clears running + capture",
                !g2.isRunning() && !g2.hasCapture());
        d = g2.onStart(true);
        check("start after reset -> firstStart + install",
                d.firstStart && d.installCapture);

        // --- Part B: source-order verification of ControlService.java ---
        String src = readControlServiceSource();
        check("ControlService.java found", src != null);
        if (src != null) {
            int start = src.indexOf("int onStartCommand");
            int end = src.indexOf("public void onDestroy", start);
            String body = (start >= 0 && end > start)
                    ? src.substring(start, end) : "";
            check("onStartCommand body found", !body.isEmpty());
            int fg = body.indexOf("startForegroundWithType");
            int cap = body.indexOf("new ScreenCapture");
            check("startForeground called before ScreenCapture is created (API 34 order)",
                    fg >= 0 && cap >= 0 && fg < cap);
            check("no early-return on already-running (stale-grant race gone)",
                    !body.contains("running.getAndSet(true)"));
            check("every start processes the grant via grantManager.onStart",
                    body.contains("grantManager.onStart("));
            check("grant judged from intent shape before foreground (isCaptureResultOk before startForeground)",
                    body.indexOf("isCaptureResultOk") >= 0
                            && body.indexOf("isCaptureResultOk") < fg);
        }

        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    private static String readControlServiceSource() {
        String prop = System.getProperty("sanji.control.service.src");
        String[] candidates = {
            prop,
            "app/src/main/java/com/sanji/control/ControlService.java",
            "sanji-control-app/app/src/main/java/com/sanji/control/ControlService.java"
        };
        for (String c : candidates) {
            if (c == null) continue;
            try {
                if (Files.exists(Paths.get(c))) {
                    return new String(Files.readAllBytes(Paths.get(c)), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
