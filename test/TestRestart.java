import com.sanji.control.BootReceiver;
import com.sanji.control.ControlService;
import com.sanji.control.RestartReceiver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JVM smoke test for the swipe-away survival + reopen auto-start work
 * (v5). No device, no Android runtime: pure-Java logic + source checks.
 *
 * Checks:
 *  1. RestartReceiver restarts the service only when wasOn is true
 *     (shouldRestart + source check that onReceive reads Prefs.wasOn
 *     and calls ControlService.start in dataSync mode).
 *  2. ControlService.shouldAutoStart: true only when was ON and the
 *     service is not already running; false in the other three cases.
 *     A fresh JVM has the service not running (isRunning() false).
 *  3. Source checks: ControlService overrides onTaskRemoved (checks
 *     wasOn, tries a direct restart, then sends the restart broadcast),
 *     MainActivity auto-starts in onCreate when wasOn, and shows the
 *     "tap Start now for screen" hint when running without a capture
 *     grant; the manifest declares RestartReceiver.
 */
public class TestRestart {

    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS: " + name);
        } else {
            failed++;
            System.out.println("  FAIL: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. RestartReceiver decision ----
        check("restart restarts when was ON", RestartReceiver.shouldRestart(true));
        check("restart does NOT restart when was OFF", !RestartReceiver.shouldRestart(false));

        // ---- 2. MainActivity auto-start decision ----
        check("auto-start when was ON + service not running",
                ControlService.shouldAutoStart(true, false));
        check("no auto-start when was ON + service already running",
                !ControlService.shouldAutoStart(true, true));
        check("no auto-start when was OFF + service not running",
                !ControlService.shouldAutoStart(false, false));
        check("no auto-start when was OFF + service running",
                !ControlService.shouldAutoStart(false, true));
        check("service not running in a fresh process",
                !ControlService.isRunning());

        // ---- 3. source checks ----
        String main = readSource("MainActivity.java");
        String service = readSource("ControlService.java");
        String restart = readSource("RestartReceiver.java");
        String manifest = readManifest();
        check("MainActivity.java found", main != null);
        check("ControlService.java found", service != null);
        check("RestartReceiver.java found", restart != null);
        check("AndroidManifest.xml found", manifest != null);

        if (service != null) {
            check("ControlService overrides onTaskRemoved",
                    service.contains("public void onTaskRemoved("));
            check("onTaskRemoved checks wasOn before restarting",
                    service.contains("Prefs.wasOn(this)"));
            check("onTaskRemoved tries a direct restart first",
                    service.contains("ControlService.start(this, 0, null)"));
            check("onTaskRemoved sends the restart broadcast",
                    service.contains("RestartReceiver.restartIntent(this)"));
            check("onTaskRemoved restart attempts are guarded (try/catch)",
                    service.contains("onTaskRemoved direct restart failed"));
            check("ControlService tracks running state",
                    service.contains("serviceRunning")
                            && service.contains("public static boolean isRunning()"));
            check("ControlService exposes the auto-start decision",
                    service.contains("shouldAutoStart(boolean wasOn"));
        }
        if (restart != null) {
            check("RestartReceiver defines the restart action",
                    restart.contains("ACTION_RESTART_SERVICE"));
            check("RestartReceiver reads wasOn",
                    restart.contains("Prefs.wasOn(context)"));
            check("RestartReceiver restarts ControlService in dataSync mode",
                    restart.contains("ControlService.start(context, 0, null)"));
            check("RestartReceiver start is guarded (try/catch)",
                    restart.contains("task-removed restart failed"));
            check("RestartReceiver builds an explicit restart intent",
                    restart.contains("restartIntent(Context context)"));
        }
        if (main != null) {
            check("MainActivity auto-starts in onCreate when wasOn",
                    main.contains("ControlService.shouldAutoStart(Prefs.wasOn(this)"));
            check("MainActivity auto-start does not double-start a live service",
                    main.contains("ControlService.isRunning()"));
            check("MainActivity auto-start uses dataSync mode (no grant)",
                    main.contains("ControlService.start(this, 0, null)"));
            check("capture status tells the user to re-grant for screen",
                    main.contains("tap Start now for screen"));
        }
        if (manifest != null) {
            check("manifest declares RestartReceiver",
                    manifest.contains("android:name=\".RestartReceiver\"")
                            && manifest.contains("com.sanji.control.ACTION_RESTART_SERVICE"));
        }

        System.out.println("TestRestart: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String readSource(String name) {
        String[] bases = {
            System.getProperty("sanji.control.app.src", ""),
            "app/src/main/java/com/sanji/control",
            "sanji-control-app/app/src/main/java/com/sanji/control",
            "/home/hatch/workspace/sanji-control-app/app/src/main/java/com/sanji/control",
        };
        for (String base : bases) {
            if (base == null || base.isEmpty()) continue;
            String path = base.endsWith("/") ? base + name
                    : base + "/app/src/main/java/com/sanji/control/" + name;
            // If base already ends with the java dir, use it directly.
            if (base.endsWith("com/sanji/control")) path = base + "/" + name;
            try {
                File f = new File(path);
                if (f.exists()) {
                    return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String readManifest() {
        String[] candidates = {
            System.getProperty("sanji.control.app.src", "") + "/app/src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "sanji-control-app/app/src/main/AndroidManifest.xml",
            "/home/hatch/workspace/sanji-control-app/app/src/main/AndroidManifest.xml",
        };
        for (String path : candidates) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
