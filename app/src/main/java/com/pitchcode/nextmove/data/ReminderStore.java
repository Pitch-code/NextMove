package com.pitchcode.nextmove.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User-created reminders (bill, appointment, return, other). Stored only on this
 * phone in private app preferences.
 */
public final class ReminderStore {
    public static final int KIND_BILL = 0;
    public static final int KIND_APPOINTMENT = 1;
    public static final int KIND_RETURN = 2;
    public static final int KIND_OTHER = 3;

    private static final String KEY_REMINDERS = "custom_reminders";
    private static final int MAX_ITEMS = 40;
    private static final long KEEP_PAST_MS = 24L * 60L * 60L * 1000L;

    private ReminderStore() {}

    public static final class Item {
        public final long id;
        public final int kind;
        public final String title;
        public final String detail1;
        public final String detail2;
        public final long timeMillis;

        public Item(long id, int kind, String title, String detail1, String detail2,
                    long timeMillis) {
            this.id = id;
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.detail1 = detail1 == null ? "" : detail1;
            this.detail2 = detail2 == null ? "" : detail2;
            this.timeMillis = timeMillis;
        }
    }

    public static Item add(Context context, int kind, String title, String detail1,
                           String detail2, long timeMillis) {
        Item item = new Item(System.currentTimeMillis(), kind, title, detail1, detail2,
                timeMillis);
        List<Item> current = readAll(context);
        current.add(item);
        write(context, current);
        return item;
    }

    public static Item find(Context context, long id) {
        for (Item item : readAll(context)) {
            if (item.id == id) return item;
        }
        return null;
    }

    public static void remove(Context context, long id) {
        List<Item> current = readAll(context);
        List<Item> kept = new ArrayList<>();
        for (Item item : current) {
            if (item.id != id) kept.add(item);
        }
        write(context, kept);
    }

    /** Reminders that have not fired yet, soonest first. */
    public static List<Item> upcoming(Context context) {
        long now = System.currentTimeMillis();
        List<Item> result = new ArrayList<>();
        for (Item item : readAll(context)) {
            if (item.timeMillis > now) result.add(item);
        }
        Collections.sort(result, (a, b) -> Long.compare(a.timeMillis, b.timeMillis));
        return result;
    }

    /** All stored reminders; entries more than a day in the past are pruned. */
    public static List<Item> readAll(Context context) {
        List<Item> results = new ArrayList<>();
        String stored = HistoryStore.prefs(context).getString(KEY_REMINDERS, "[]");
        long cutoff = System.currentTimeMillis() - KEEP_PAST_MS;
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) continue;
                long time = object.optLong("time");
                if (time < cutoff) continue;
                results.add(new Item(
                        object.optLong("id"),
                        object.optInt("kind", KIND_OTHER),
                        object.optString("title"),
                        object.optString("d1"),
                        object.optString("d2"),
                        time));
            }
        } catch (JSONException ignored) {
            // Treat malformed local data as empty.
        }
        return results;
    }

    private static void write(Context context, List<Item> items) {
        JSONArray array = new JSONArray();
        int start = Math.max(0, items.size() - MAX_ITEMS);
        for (int index = start; index < items.size(); index++) {
            Item item = items.get(index);
            try {
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("kind", item.kind);
                object.put("title", item.title);
                object.put("d1", item.detail1);
                object.put("d2", item.detail2);
                object.put("time", item.timeMillis);
                array.put(object);
            } catch (JSONException ignored) {
                // Skip an entry that cannot be serialised.
            }
        }
        HistoryStore.prefs(context).edit().putString(KEY_REMINDERS, array.toString()).apply();
    }
}
