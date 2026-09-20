package com.pitchcode.nextmove.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores scam alerts raised by the on-device message scanner.
 * Everything is kept in local SharedPreferences on this phone only —
 * no message content leaves the device.
 */
public final class FlaggedStore {
    private static final String KEY_FLAGGED = "flagged_alerts";
    private static final int MAX_ITEMS = 20;
    private static final int MAX_SNIPPET = 240;

    private FlaggedStore() {}

    public static final class Item {
        public final long id;
        public final String source;
        public final String snippet;
        public final int signals;
        public final boolean highRisk;
        public final String matched;
        public final long timeMillis;

        public Item(long id, String source, String snippet, int signals,
                    boolean highRisk, String matched, long timeMillis) {
            this.id = id;
            this.source = source;
            this.snippet = snippet;
            this.signals = signals;
            this.highRisk = highRisk;
            this.matched = matched;
            this.timeMillis = timeMillis;
        }
    }

    public static Item add(Context context, String source, String snippet,
                           int signals, boolean highRisk, String matched) {
        String safeSnippet = snippet == null ? "" : snippet.trim();
        if (safeSnippet.length() > MAX_SNIPPET) {
            safeSnippet = safeSnippet.substring(0, MAX_SNIPPET) + "…";
        }
        Item item = new Item(System.currentTimeMillis(),
                source == null ? "" : source, safeSnippet,
                signals, highRisk, matched == null ? "" : matched,
                System.currentTimeMillis());

        List<Item> current = read(context);
        current.add(0, item);
        JSONArray array = new JSONArray();
        for (int index = 0; index < Math.min(current.size(), MAX_ITEMS); index++) {
            Item stored = current.get(index);
            try {
                JSONObject object = new JSONObject();
                object.put("id", stored.id);
                object.put("source", stored.source);
                object.put("snippet", stored.snippet);
                object.put("signals", stored.signals);
                object.put("highRisk", stored.highRisk);
                object.put("matched", stored.matched);
                object.put("time", stored.timeMillis);
                array.put(object);
            } catch (JSONException ignored) {
                // Skip an entry that cannot be serialised.
            }
        }
        HistoryStore.prefs(context).edit().putString(KEY_FLAGGED, array.toString()).apply();
        return item;
    }

    public static List<Item> read(Context context) {
        List<Item> results = new ArrayList<>();
        String stored = HistoryStore.prefs(context).getString(KEY_FLAGGED, "[]");
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) continue;
                results.add(new Item(
                        object.optLong("id"),
                        object.optString("source"),
                        object.optString("snippet"),
                        object.optInt("signals"),
                        object.optBoolean("highRisk"),
                        object.optString("matched"),
                        object.optLong("time")));
            }
        } catch (JSONException ignored) {
            // Treat malformed local prototype data as empty.
        }
        return results;
    }

    public static Item find(Context context, long id) {
        for (Item item : read(context)) {
            if (item.id == id) return item;
        }
        return null;
    }

    public static void clear(Context context) {
        HistoryStore.prefs(context).edit().remove(KEY_FLAGGED).apply();
    }
}
