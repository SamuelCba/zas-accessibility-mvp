package com.example.zasmvp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

class BackendClient {
    static final String PREFS      = "zas_prefs";
    static final String KEY_TOKEN  = "qrgen_token";
    static final String KEY_WORKER = "worker_id";
    static final String KEY_PROV   = "provider";

    private static final String BASE = "https://eveta-core.vercel.app/api/v1";

    static String token(Context c)    { return prefs(c).getString(KEY_TOKEN,  ""); }
    static String workerId(Context c) { return prefs(c).getString(KEY_WORKER, "bnb"); }
    static String provider(Context c) { return prefs(c).getString(KEY_PROV,   "bnb"); }
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static TopupData getNextTopup(Context ctx) throws Exception {
        String body = new JSONObject()
                .put("worker_id", workerId(ctx))
                .toString();
        JSONObject resp = post(ctx, "/qrgen/next-topup", body);
        if (resp == null || !resp.optBoolean("has_topup", false)) return null;
        JSONObject t = resp.optJSONObject("topup");
        if (t == null) return null;
        TopupData d = new TopupData();
        d.id        = t.optString("id", "");
        d.amount    = t.optString("amount", "");
        d.reference = t.optString("reference", "");
        d.concept   = t.optString("concept", "");
        if (d.concept.isEmpty() || "null".equals(d.concept)) d.concept = d.reference;
        return (d.id.isEmpty() || d.amount.isEmpty()) ? null : d;
    }

    static boolean submitQrPayload(Context ctx, String topupId, String payload) throws Exception {
        String body = new JSONObject()
                .put("topup_id", topupId)
                .put("provider", provider(ctx))
                .put("qr_payload", payload)
                .toString();
        JSONObject resp = post(ctx, "/qrgen/submit-qr-payload", body);
        return resp != null && resp.optBoolean("ok", false);
    }

    private static JSONObject post(Context ctx, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + token(ctx));
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        if (is == null) return null;
        byte[] bytes = is.readAllBytes();
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static class TopupData {
        String id, amount, reference, concept;
    }
}
