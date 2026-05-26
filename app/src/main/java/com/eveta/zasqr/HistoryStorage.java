package com.eveta.zasqr;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class HistoryStorage {
    private static final String KEY = "history";
    private static final int   MAX = 100;

    static void add(Context ctx, HistoryEntry e) {
        try {
            JSONArray arr = load(ctx);
            JSONObject o = new JSONObject();
            o.put("id",      e.topupId);
            o.put("amount",  e.amount);
            o.put("ref",     e.reference);
            o.put("concept", e.concept);
            o.put("payload", e.qrPayload);
            o.put("ts",      e.timestampMs);
            o.put("ok",      e.submitted);
            JSONArray updated = new JSONArray();
            updated.put(o);
            for (int i = 0; i < arr.length() && i < MAX - 1; i++) updated.put(arr.get(i));
            BackendClient.prefs(ctx).edit().putString(KEY, updated.toString()).apply();
        } catch (Exception ignored) {}
    }

    static void clear(Context ctx) {
        BackendClient.prefs(ctx).edit().remove(KEY).apply();
    }

    static boolean alreadyProcessed(Context ctx, String topupId) {
        try {
            JSONArray arr = load(ctx);
            for (int i = 0; i < arr.length(); i++) {
                if (topupId.equals(arr.getJSONObject(i).optString("id"))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static List<HistoryEntry> getAll(Context ctx) {
        List<HistoryEntry> list = new ArrayList<>();
        try {
            JSONArray arr = load(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryEntry e = new HistoryEntry();
                e.topupId     = o.optString("id");
                e.amount      = o.optString("amount");
                e.reference   = o.optString("ref");
                e.concept     = o.optString("concept");
                e.qrPayload   = o.optString("payload");
                e.timestampMs = o.optLong("ts");
                e.submitted   = o.optBoolean("ok");
                list.add(e);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static JSONArray load(Context ctx) {
        try {
            return new JSONArray(BackendClient.prefs(ctx).getString(KEY, "[]"));
        } catch (Exception e) { return new JSONArray(); }
    }
}
