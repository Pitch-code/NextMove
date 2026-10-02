package com.pitchcode.nextmove.data;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * On-device scam number/URL reputation store.
 *
 * It combines a small bundled seed list of commonly-reported scam domains with a
 * local, user-built report count ("reported N times"). Everything is stored on
 * the device. A live, server-backed feed can replace {@link #seedCount} later
 * without changing callers.
 */
public final class ScamDatabase {
    private static final String KEY_REPORTS = "scam_reports";

    // Illustrative offline seed of frequently-reported scam domain fragments.
    private static final Map<String, Integer> SEED = new HashMap<>();
    static {
        SEED.put("kyc-update", 214);
        SEED.put("kyc-verify", 188);
        SEED.put("sbi-reward", 173);
        SEED.put("hdfc-points", 141);
        SEED.put("electricity-bill", 132);
        SEED.put("gift-claim", 126);
        SEED.put("lucky-winner", 119);
        SEED.put("parcel-redelivery", 108);
        SEED.put("refund-portal", 97);
        SEED.put("work-from-home", 88);
    }

    private ScamDatabase() {}

    /** Total reports for the given number/URL/sender (seed + local user reports). */
    public static int reports(Context context, String raw) {
        String key = key(raw);
        return seedCount(key) + userCount(context, key);
    }

    /** Records one local "reported as scam" for this number/URL/sender. */
    public static void report(Context context, String raw) {
        String key = key(raw);
        Map<String, Integer> map = readUser(context);
        map.put(key, map.containsKey(key) ? map.get(key) + 1 : 1);
        JSONObject object = new JSONObject();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            try {
                object.put(entry.getKey(), entry.getValue());
            } catch (JSONException ignored) {
                // Skip an entry that cannot be serialised.
            }
        }
        HistoryStore.prefs(context).edit().putString(KEY_REPORTS, object.toString()).apply();
    }

    private static int seedCount(String key) {
        if (!key.startsWith("txt:")) return 0;
        String host = key.substring(4);
        int best = 0;
        for (Map.Entry<String, Integer> entry : SEED.entrySet()) {
            if (host.contains(entry.getKey())) best = Math.max(best, entry.getValue());
        }
        return best;
    }

    private static int userCount(Context context, String key) {
        Map<String, Integer> map = readUser(context);
        return map.containsKey(key) ? map.get(key) : 0;
    }

    private static Map<String, Integer> readUser(Context context) {
        Map<String, Integer> map = new HashMap<>();
        String stored = HistoryStore.prefs(context).getString(KEY_REPORTS, "{}");
        try {
            JSONObject object = new JSONObject(stored);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, object.optInt(k));
            }
        } catch (JSONException ignored) {
            // Treat malformed local data as empty.
        }
        return map;
    }

    /** Normalises a raw number/URL/sender into a stable lookup key. */
    private static String key(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String digits = s.replaceAll("[^0-9]", "");
        boolean urlish = s.contains("http") || s.contains("www.")
                || s.matches(".*[a-z0-9-]+\\.[a-z]{2,}.*");
        if (urlish) {
            return "txt:" + host(s);
        }
        if (digits.length() >= 7) {
            String last = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
            return "num:" + last;
        }
        return "txt:" + s;
    }

    private static String host(String s) {
        String h = s;
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        if (h.startsWith("www.")) h = h.substring(4);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int space = h.indexOf(' ');
        if (space >= 0) h = h.substring(0, space);
        return h;
    }
}
