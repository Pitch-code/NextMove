package com.pitchcode.nextmove.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "nextmove_local";
    private static final String KEY_HISTORY = "handled_history";

    private HistoryStore() {}

    public static void add(Context context, SampleAnalysis.Kind kind) {
        List<SampleAnalysis.Kind> current = read(context);
        current.remove(kind);
        current.add(0, kind);
        JSONArray array = new JSONArray();
        for (int index = 0; index < Math.min(current.size(), 8); index++) {
            array.put(current.get(index).name());
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    public static List<SampleAnalysis.Kind> read(Context context) {
        List<SampleAnalysis.Kind> results = new ArrayList<>();
        String stored = prefs(context).getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length(); index++) {
                try {
                    results.add(SampleAnalysis.Kind.valueOf(array.getString(index)));
                } catch (IllegalArgumentException ignored) {
                    // Ignore values from an incompatible future/old version.
                }
            }
        } catch (JSONException ignored) {
            // Treat malformed local prototype data as empty.
        }
        return results;
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_HISTORY).apply();
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
