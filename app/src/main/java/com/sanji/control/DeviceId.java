package com.sanji.control;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.UUID;

/**
 * The phone's permanent unique UID.
 *
 * Uses {@link Settings.Secure#ANDROID_ID} — stable per device, survives app
 * reinstalls (only a factory reset changes it). That is the most permanent
 * ID available to a normal app, and it is what the control panel uses to
 * pick which phone to control when the app is installed on many phones.
 *
 * If ANDROID_ID is unavailable (null/empty — rare, some devices), a random
 * UUID is generated once and persisted in SharedPreferences so the UID
 * still stays stable on that phone.
 *
 * The UID is read once and cached in a static field. It is also sent in
 * the hello message so the relay / control panel knows which phone
 * connected: {"type":"hello","role":"device","device":"sanji-phone",
 * "uid":"<android_id>"}.
 */
public final class DeviceId {

    private static volatile String cachedUid = null;

    /** Fallback device display name when the real brand/model cannot be read. */
    public static final String FALLBACK_DEVICE_NAME = "sanji-phone";

    private DeviceId() {}

    /**
     * Human display name for this phone, e.g. "Oppo CPH2349" — what the
     * control panel shows in the master-mode phone list.
     *
     * Pure (testable on the JVM): builds from a manufacturer + model pair.
     * The manufacturer is canonicalised ("oppo" -> "Oppo", "oneplus" ->
     * "OnePlus"); if the model already starts with the manufacturer
     * (case-insensitive) it is not repeated ("OPPO CPH2349" stays
     * "OPPO CPH2349"). Blank or "unknown" values are ignored; when nothing
     * usable is left the fallback "sanji-phone" is returned.
     */
    public static String deviceDisplayName(String manufacturer, String model) {
        String mfr = clean(manufacturer);
        String mdl = clean(model);
        if (mfr == null && mdl == null) return FALLBACK_DEVICE_NAME;
        if (mdl == null) return canonicalManufacturer(mfr);
        if (mfr == null) return mdl;
        if (mdl.toLowerCase(java.util.Locale.ROOT)
                .startsWith(mfr.toLowerCase(java.util.Locale.ROOT))) {
            return mdl;
        }
        return canonicalManufacturer(mfr) + " " + mdl;
    }

    /** Display name from the real device (Build.MANUFACTURER / Build.MODEL). */
    public static String deviceDisplayName() {
        String manufacturer = null;
        String model = null;
        try {
            manufacturer = Build.MANUFACTURER;
            model = Build.MODEL;
        } catch (Exception ignored) {
        }
        return deviceDisplayName(manufacturer, model);
    }

    /** Null for blank/"unknown"; otherwise trimmed. */
    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "unknown".equalsIgnoreCase(t)) return null;
        return t;
    }

    /** Canonical brand casing ("oppo" -> "Oppo", "oneplus" -> "OnePlus"). */
    private static String canonicalManufacturer(String manufacturer) {
        String lower = manufacturer.toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "oppo": return "Oppo";
            case "realme": return "Realme";
            case "samsung": return "Samsung";
            case "xiaomi": return "Xiaomi";
            case "vivo": return "Vivo";
            case "oneplus": return "OnePlus";
            case "google": return "Google";
            case "huawei": return "Huawei";
            case "honor": return "Honor";
            case "motorola": return "Motorola";
            case "nokia": return "Nokia";
            case "sony": return "Sony";
            case "asus": return "Asus";
            case "lenovo": return "Lenovo";
            case "infinix": return "Infinix";
            case "tecno": return "Tecno";
            case "lg": return "LG";
            case "htc": return "HTC";
            default:
                return manufacturer.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                        + manufacturer.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Pure resolution rule (testable on the JVM): prefer the real Android ID;
     * fall back to the persisted random UID only when the Android ID is
     * missing or blank.
     */
    public static String resolveUid(String androidId, String fallbackUid) {
        if (androidId != null && !androidId.trim().isEmpty()) {
            return androidId.trim();
        }
        return fallbackUid;
    }

    /** True for a usable UID (non-null, non-blank). */
    public static boolean isValidUid(String uid) {
        return uid != null && !uid.trim().isEmpty();
    }

    /** Read the raw ANDROID_ID from the system settings. May be null. */
    public static String readAndroidId(Context context) {
        try {
            return Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return null;
        }
    }

    /** The persisted random fallback UID (created once, then reused). */
    public static String getOrCreateFallbackUid(Context context) {
        Prefs.Store store = Prefs.androidStore(context);
        String existing = Prefs.getFallbackUid(store);
        if (isValidUid(existing)) return existing;
        String fresh = UUID.randomUUID().toString().replace("-", "");
        Prefs.setFallbackUid(store, fresh);
        return fresh;
    }

    /** The phone's permanent UID (cached after the first read). */
    public static String getUid(Context context) {
        String uid = cachedUid;
        if (uid == null) {
            uid = resolveUid(readAndroidId(context), getOrCreateFallbackUid(context));
            cachedUid = uid;
        }
        return uid;
    }

    /** Short form for the notification (first 8 chars, never null). */
    public static String shortUid(String uid) {
        if (uid == null) return "";
        return uid.length() > 8 ? uid.substring(0, 8) : uid;
    }

    /** Build the hello message (pure — testable on the JVM). */
    public static JSONObject buildHelloJson(String deviceName, String uid) {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("role", "device");
            hello.put("device", deviceName);
            hello.put("uid", uid);
            return hello;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
