import com.sanji.control.BootReceiver;
import com.sanji.control.DeviceId;
import com.sanji.control.Prefs;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * JVM smoke test for the permanent per-phone UID + background stay-alive
 * work. No device, no Android runtime: pure-Java logic + source checks.
 *
 * Checks:
 *  1. The hello message carries a non-empty "uid" (and keeps type/role/device).
 *  2. resolveUid prefers the real ANDROID_ID; falls back only when it is
 *     null/empty/blank; isValidUid / shortUid behave.
 *  3. wasOn persistence round-trips through Prefs (in-memory store).
 *  4. BootReceiver restarts the service only when wasOn is true
 *     (shouldRestart + source check that onReceive reads Prefs.wasOn and
 *     calls ControlService.start).
 *  5. Source checks: MainActivity persists wasOn on ON/OFF, shows the UID,
 *     and has the battery-exemption + app-settings buttons; the manifest
 *     declares the boot receiver and the two new permissions.
 *  6. Device display name (v6): deviceDisplayName(manufacturer, model)
 *     builds the real brand + model ("Oppo CPH2349"), never doubles a
 *     brand already in the model, and falls back to "sanji-phone" when
 *     nothing usable is given; ControlService.sendHello uses it and
 *     Config.DEVICE_NAME is gone.
 */
public class TestDeviceId {

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

    /** In-memory Prefs.Store for the round-trip test. */
    static class MemoryStore implements Prefs.Store {
        Map<String, Object> map = new HashMap<>();
        @Override public boolean getBoolean(String k, boolean def) {
            Object v = map.get(k);
            return v instanceof Boolean ? (Boolean) v : def;
        }
        @Override public void putBoolean(String k, boolean v) { map.put(k, v); }
        @Override public String getString(String k, String def) {
            Object v = map.get(k);
            return v instanceof String ? (String) v : def;
        }
        @Override public void putString(String k, String v) { map.put(k, v); }
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. hello message carries the UID ----
        JSONObject hello = DeviceId.buildHelloJson("sanji-phone", "a1b2c3d4e5f6a7b8");
        check("hello type is hello", "hello".equals(hello.optString("type")));
        check("hello role is device", "device".equals(hello.optString("role")));
        check("hello keeps device name", "sanji-phone".equals(hello.optString("device")));
        check("hello carries uid", "a1b2c3d4e5f6a7b8".equals(hello.optString("uid")));
        check("hello uid is non-empty", DeviceId.isValidUid(hello.optString("uid")));

        // ---- 2. UID resolution rules ----
        check("resolveUid prefers ANDROID_ID",
                "android-id-123".equals(DeviceId.resolveUid("android-id-123", "fallback-xyz")));
        check("resolveUid falls back when ANDROID_ID is null",
                "fallback-xyz".equals(DeviceId.resolveUid(null, "fallback-xyz")));
        check("resolveUid falls back when ANDROID_ID is empty",
                "fallback-xyz".equals(DeviceId.resolveUid("", "fallback-xyz")));
        check("resolveUid falls back when ANDROID_ID is blank",
                "fallback-xyz".equals(DeviceId.resolveUid("   ", "fallback-xyz")));
        check("resolveUid trims the Android ID",
                "abc".equals(DeviceId.resolveUid("  abc  ", "fallback-xyz")));
        check("isValidUid rejects null", !DeviceId.isValidUid(null));
        check("isValidUid rejects blank", !DeviceId.isValidUid("  "));
        check("isValidUid accepts a real id", DeviceId.isValidUid("a1b2c3d4"));
        check("shortUid takes first 8 chars",
                "a1b2c3d4".equals(DeviceId.shortUid("a1b2c3d4e5f6a7b8")));
        check("shortUid keeps a short id whole",
                "abc".equals(DeviceId.shortUid("abc")));

        // ---- 3. wasOn persistence round-trips ----
        MemoryStore store = new MemoryStore();
        check("wasOn defaults to false", !Prefs.wasOn(store));
        Prefs.setWasOn(store, true);
        check("wasOn round-trips true", Prefs.wasOn(store));
        Prefs.setWasOn(store, false);
        check("wasOn round-trips false", !Prefs.wasOn(store));
        check("fallback uid defaults to null", Prefs.getFallbackUid(store) == null);
        Prefs.setFallbackUid(store, "fallback-xyz");
        check("fallback uid round-trips",
                "fallback-xyz".equals(Prefs.getFallbackUid(store)));

        // ---- 4. boot restart decision ----
        check("boot restarts when was ON", BootReceiver.shouldRestart(true));
        check("boot does NOT restart when was OFF", !BootReceiver.shouldRestart(false));

        // ---- 5. source checks ----
        String main = readSource("MainActivity.java");
        String service = readSource("ControlService.java");
        String boot = readSource("BootReceiver.java");
        String manifest = readManifest();
        check("MainActivity.java found", main != null);
        check("ControlService.java found", service != null);
        check("BootReceiver.java found", boot != null);
        check("AndroidManifest.xml found", manifest != null);
        if (main != null) {
            check("MainActivity persists wasOn=true on ON",
                    main.contains("Prefs.setWasOn(this, true)"));
            check("v8 always-on: MainActivity never persists wasOn=false (no OFF)",
                    !main.contains("Prefs.setWasOn(this, false)"));
            check("MainActivity shows the UID",
                    main.contains("DeviceId.getUid(this)")
                            && main.contains("uidText"));
            check("MainActivity has battery-exemption button",
                    main.contains("openBatteryExemption")
                            && main.contains("isIgnoringBatteryOptimizations"));
            check("MainActivity has app-settings button",
                    main.contains("openAppSettings")
                            && main.contains("ACTION_APPLICATION_DETAILS_SETTINGS"));
            check("MainActivity prompts for battery exemption on ON (non-blocking)",
                    main.contains("isBatteryExempt()"));
        }
        if (service != null) {
            check("ControlService hello includes uid",
                    service.contains("DeviceId.buildHelloJson")
                            && service.contains("DeviceId.getUid(this)"));
            check("ControlService notification shows short UID",
                    service.contains("DeviceId.shortUid"));
        }
        if (boot != null) {
            check("BootReceiver reads wasOn",
                    boot.contains("Prefs.wasOn(context)"));
            check("BootReceiver restarts ControlService on boot",
                    boot.contains("ControlService.start(context, 0, null)"));
            check("BootReceiver only handles BOOT_COMPLETED",
                    boot.contains("ACTION_BOOT_COMPLETED"));
        }
        if (manifest != null) {
            check("manifest declares BootReceiver",
                    manifest.contains("android:name=\".BootReceiver\"")
                            && manifest.contains("android.intent.action.BOOT_COMPLETED"));
            check("manifest requests battery-optimization exemption",
                    manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
            check("manifest requests RECEIVE_BOOT_COMPLETED",
                    manifest.contains("RECEIVE_BOOT_COMPLETED"));
        }

        // ---- 6. device display name (real brand + model) ----
        check("display name: Oppo CPH2349",
                "Oppo CPH2349".equals(DeviceId.deviceDisplayName("oppo", "CPH2349")));
        check("display name: Realme RMX3761",
                "Realme RMX3761".equals(DeviceId.deviceDisplayName("realme", "RMX3761")));
        check("display name: Samsung Galaxy S21",
                "Samsung Galaxy S21".equals(DeviceId.deviceDisplayName("samsung", "Galaxy S21")));
        check("display name: Xiaomi Redmi Note 12 (brand already cased)",
                "Xiaomi Redmi Note 12".equals(DeviceId.deviceDisplayName("Xiaomi", "Redmi Note 12")));
        check("display name: OnePlus 11",
                "OnePlus 11".equals(DeviceId.deviceDisplayName("oneplus", "11")));
        check("display name: Vivo V2312",
                "Vivo V2312".equals(DeviceId.deviceDisplayName("vivo", "V2312")));
        check("display name: brand already in model is not doubled (OPPO)",
                "OPPO CPH2349".equals(DeviceId.deviceDisplayName("oppo", "OPPO CPH2349")));
        check("display name: brand already in model is not doubled (Samsung)",
                "Samsung Galaxy S21".equals(DeviceId.deviceDisplayName("samsung", "Samsung Galaxy S21")));
        check("display name: brand already in model is not doubled (OnePlus)",
                "OnePlus 11".equals(DeviceId.deviceDisplayName("oneplus", "OnePlus 11")));
        check("display name: null/null falls back to sanji-phone",
                "sanji-phone".equals(DeviceId.deviceDisplayName(null, null)));
        check("display name: empty/empty falls back to sanji-phone",
                "sanji-phone".equals(DeviceId.deviceDisplayName("", "")));
        check("display name: blank/blank falls back to sanji-phone",
                "sanji-phone".equals(DeviceId.deviceDisplayName("   ", "  ")));
        check("display name: unknown/unknown falls back to sanji-phone",
                "sanji-phone".equals(DeviceId.deviceDisplayName("unknown", "unknown")));
        check("display name: manufacturer only",
                "Oppo".equals(DeviceId.deviceDisplayName("oppo", "")));
        check("display name: model only, no manufacturer",
                "CPH2349".equals(DeviceId.deviceDisplayName(null, "CPH2349")));
        check("display name: whitespace is trimmed",
                "Oppo CPH2349".equals(DeviceId.deviceDisplayName("  oppo  ", "  CPH2349  ")));
        check("display name: unknown manufacturer is capitalised",
                "Nokia 3310".equals(DeviceId.deviceDisplayName("nokia", "3310")));
        check("display name: no-arg reads Build (null on JVM) -> fallback",
                "sanji-phone".equals(DeviceId.deviceDisplayName()));
        check("display name: hello keeps the real device name",
                "Oppo CPH2349".equals(
                        DeviceId.buildHelloJson(
                                DeviceId.deviceDisplayName("oppo", "CPH2349"),
                                "a1b2c3d4e5f6a7b8").optString("device")));
        if (service != null) {
            check("ControlService hello uses DeviceId.deviceDisplayName()",
                    service.contains("DeviceId.deviceDisplayName()"));
            check("ControlService no longer uses Config.DEVICE_NAME",
                    !service.contains("Config.DEVICE_NAME"));
        }
        String config = readSource("Config.java");
        check("Config.java found", config != null);
        if (config != null) {
            check("Config no longer declares DEVICE_NAME",
                    !config.contains("DEVICE_NAME"));
            check("Config still has the relay URL",
                    config.contains("RELAY_URL"));
        }

        System.out.println("TestDeviceId: " + passed + " passed, " + failed + " failed");
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
