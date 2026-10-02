package com.pitchcode.nextmove.data;

import android.content.Context;

/**
 * A single trusted person the user can quickly alert about a suspicious message.
 * Stored locally only (entered by hand — NextMove never reads the address book).
 */
public final class TrustedContact {
    private static final String KEY_NAME = "trusted_name";
    private static final String KEY_NUMBER = "trusted_number";
    private static final String KEY_ALERT = "trusted_alert_enabled";

    private TrustedContact() {}

    public static boolean isSet(Context context) {
        return !getNumber(context).isEmpty();
    }

    public static String getName(Context context) {
        return HistoryStore.prefs(context).getString(KEY_NAME, "");
    }

    public static String getNumber(Context context) {
        return HistoryStore.prefs(context).getString(KEY_NUMBER, "");
    }

    public static void set(Context context, String name, String number) {
        HistoryStore.prefs(context).edit()
                .putString(KEY_NAME, name == null ? "" : name.trim())
                .putString(KEY_NUMBER, number == null ? "" : number.trim())
                .apply();
    }

    public static void clear(Context context) {
        HistoryStore.prefs(context).edit()
                .remove(KEY_NAME).remove(KEY_NUMBER).apply();
    }

    public static boolean isAlertEnabled(Context context) {
        return HistoryStore.prefs(context).getBoolean(KEY_ALERT, true);
    }

    public static void setAlertEnabled(Context context, boolean enabled) {
        HistoryStore.prefs(context).edit().putBoolean(KEY_ALERT, enabled).apply();
    }
}
