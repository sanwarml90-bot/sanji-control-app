package com.sanji.control;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small SharedPreferences wrapper for the bits of state that must survive
 * the app being killed or the phone rebooting.
 *
 * - "was_on": true while the app is ON. The boot receiver reads it to
 *   decide whether to restart the service after a reboot (taps/typing
 *   keep working; screen capture needs "Start now" again because the
 *   MediaProjection grant is single-use and gone after reboot).
 * - "fallback_uid": the random UID used only when ANDROID_ID is
 *   unavailable (see DeviceId).
 *
 * The Store interface keeps the logic testable on the JVM (tests use an
 * in-memory store); the Android implementation is a thin wrapper over
 * SharedPreferences.
 */
public final class Prefs {

    public static final String FILE = "sanji_control";
    public static final String KEY_WAS_ON = "was_on";
    public static final String KEY_FALLBACK_UID = "fallback_uid";
    public static final String KEY_FULL_SETUP_OFFERED = "full_setup_offered";

    private Prefs() {}

    /** Minimal key/value store so the logic is testable without Android. */
    public interface Store {
        boolean getBoolean(String key, boolean def);
        void putBoolean(String key, boolean value);
        String getString(String key, String def);
        void putString(String key, String value);
    }

    /** Android-backed store over SharedPreferences. */
    public static Store androidStore(Context context) {
        final SharedPreferences sp =
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new Store() {
            @Override public boolean getBoolean(String key, boolean def) {
                return sp.getBoolean(key, def);
            }
            @Override public void putBoolean(String key, boolean value) {
                sp.edit().putBoolean(key, value).apply();
            }
            @Override public String getString(String key, String def) {
                return sp.getString(key, def);
            }
            @Override public void putString(String key, String value) {
                sp.edit().putString(key, value).apply();
            }
        };
    }

    /** True when the app was ON when it last stopped / the phone shut down. */
    public static boolean wasOn(Store store) {
        return store.getBoolean(KEY_WAS_ON, false);
    }

    public static void setWasOn(Store store, boolean value) {
        store.putBoolean(KEY_WAS_ON, value);
    }

    public static String getFallbackUid(Store store) {
        return store.getString(KEY_FALLBACK_UID, null);
    }

    public static void setFallbackUid(Store store, String value) {
        store.putString(KEY_FALLBACK_UID, value);
    }

    /** True once the v9 full-permission setup flow has been auto-offered (once per install). */
    public static boolean wasFullSetupOffered(Store store) {
        return store.getBoolean(KEY_FULL_SETUP_OFFERED, false);
    }

    public static void setFullSetupOffered(Store store, boolean value) {
        store.putBoolean(KEY_FULL_SETUP_OFFERED, value);
    }

    // ---- Context conveniences ----

    public static boolean wasOn(Context context) {
        return wasOn(androidStore(context));
    }

    public static void setWasOn(Context context, boolean value) {
        setWasOn(androidStore(context), value);
    }

    public static boolean wasFullSetupOffered(Context context) {
        return wasFullSetupOffered(androidStore(context));
    }

    public static void setFullSetupOffered(Context context, boolean value) {
        setFullSetupOffered(androidStore(context), value);
    }
}
