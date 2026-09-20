package com.pitchcode.nextmove.data;

import android.content.Context;

/**
 * Freemium plan state for the prototype.
 *
 * Model: a 7-day free trial (full features) begins on first launch. When the
 * trial ends the app is locked until the user upgrades to premium. Premium is
 * simulated locally in this prototype; real Google Play Billing replaces
 * {@link #setPremium(Context, boolean)} at Play Store launch.
 */
public final class PlanState {
    private static final String KEY_TRIAL_START = "trial_start";
    private static final String KEY_PREMIUM = "premium";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    public static final long TRIAL_MS = 7L * DAY_MS;

    private PlanState() {}

    /** Trial start timestamp, initialising it to now on first access. */
    public static long trialStart(Context context) {
        long start = HistoryStore.prefs(context).getLong(KEY_TRIAL_START, 0L);
        if (start <= 0L) {
            start = System.currentTimeMillis();
            HistoryStore.prefs(context).edit().putLong(KEY_TRIAL_START, start).apply();
        }
        return start;
    }

    public static boolean isPremium(Context context) {
        return HistoryStore.prefs(context).getBoolean(KEY_PREMIUM, false);
    }

    public static void setPremium(Context context, boolean premium) {
        HistoryStore.prefs(context).edit().putBoolean(KEY_PREMIUM, premium).apply();
    }

    /** Milliseconds remaining in the trial (never negative). */
    public static long millisLeft(Context context) {
        long elapsed = System.currentTimeMillis() - trialStart(context);
        long left = TRIAL_MS - elapsed;
        return left > 0L ? left : 0L;
    }

    /** Whole days remaining in the trial, rounded up (0 once expired). */
    public static int daysLeft(Context context) {
        long left = millisLeft(context);
        return (int) ((left + DAY_MS - 1L) / DAY_MS);
    }

    public static boolean trialActive(Context context) {
        return !isPremium(context) && millisLeft(context) > 0L;
    }

    /** True when the user may use the app (premium or within the trial). */
    public static boolean hasAccess(Context context) {
        return isPremium(context) || trialActive(context);
    }

    /** True when the trial has ended and the user has not upgraded. */
    public static boolean isLocked(Context context) {
        return !hasAccess(context);
    }

    // --- Demo controls (prototype only) -------------------------------------

    public static void resetTrial(Context context) {
        HistoryStore.prefs(context).edit()
                .putLong(KEY_TRIAL_START, System.currentTimeMillis())
                .apply();
    }

    /** Moves the trial start into the past so the locked state can be tested. */
    public static void expireTrial(Context context) {
        HistoryStore.prefs(context).edit()
                .putLong(KEY_TRIAL_START, System.currentTimeMillis() - TRIAL_MS - DAY_MS)
                .apply();
    }
}
