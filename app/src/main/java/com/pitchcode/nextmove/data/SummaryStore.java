package com.pitchcode.nextmove.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Local counters for the weekly safety summary ("checked N messages, flagged M").
 * Weekly counts roll over automatically at the start of each calendar week.
 * Everything is stored on-device.
 */
public final class SummaryStore {
    private static final String KEY_CHECKED_TOTAL = "sum_checked_total";
    private static final String KEY_FLAGGED_TOTAL = "sum_flagged_total";
    private static final String KEY_WEEK = "sum_week";
    private static final String KEY_CHECKED_WEEK = "sum_checked_week";
    private static final String KEY_FLAGGED_WEEK = "sum_flagged_week";

    private SummaryStore() {}

    private static int currentWeek() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.YEAR) * 53 + now.get(Calendar.WEEK_OF_YEAR);
    }

    private static void rollIfNeeded(Context context) {
        SharedPreferences prefs = HistoryStore.prefs(context);
        int week = currentWeek();
        if (prefs.getInt(KEY_WEEK, -1) != week) {
            prefs.edit()
                    .putInt(KEY_WEEK, week)
                    .putInt(KEY_CHECKED_WEEK, 0)
                    .putInt(KEY_FLAGGED_WEEK, 0)
                    .apply();
        }
    }

    public static void recordChecked(Context context) {
        rollIfNeeded(context);
        SharedPreferences prefs = HistoryStore.prefs(context);
        prefs.edit()
                .putInt(KEY_CHECKED_TOTAL, prefs.getInt(KEY_CHECKED_TOTAL, 0) + 1)
                .putInt(KEY_CHECKED_WEEK, prefs.getInt(KEY_CHECKED_WEEK, 0) + 1)
                .apply();
    }

    public static void recordFlagged(Context context) {
        rollIfNeeded(context);
        SharedPreferences prefs = HistoryStore.prefs(context);
        prefs.edit()
                .putInt(KEY_FLAGGED_TOTAL, prefs.getInt(KEY_FLAGGED_TOTAL, 0) + 1)
                .putInt(KEY_FLAGGED_WEEK, prefs.getInt(KEY_FLAGGED_WEEK, 0) + 1)
                .apply();
    }

    public static int checkedThisWeek(Context context) {
        rollIfNeeded(context);
        return HistoryStore.prefs(context).getInt(KEY_CHECKED_WEEK, 0);
    }

    public static int flaggedThisWeek(Context context) {
        rollIfNeeded(context);
        return HistoryStore.prefs(context).getInt(KEY_FLAGGED_WEEK, 0);
    }

    public static int flaggedTotal(Context context) {
        return HistoryStore.prefs(context).getInt(KEY_FLAGGED_TOTAL, 0);
    }
}
