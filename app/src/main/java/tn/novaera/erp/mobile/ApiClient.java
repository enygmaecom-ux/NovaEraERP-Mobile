package tn.novaera.erp.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    public static final String PREFS = "nova_mobile";
    private final Context context;

    public ApiClient(Context context) { this.context = context.getApplicationContext(); }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public String baseUrl() { return prefs().getString("server_url", "").replaceAll("/+$", ""); }
    public String key() { return prefs().getString("mobile_key", ""); }
    public boolean configured() { return !baseUrl().isEmpty() && !key().isEmpty(); }

    public void saveConfig(String url, String key) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        u = u.replaceAll("/+$", "");
        prefs().edit().putString("server_url", u).putString("mobile_key", key == null ? "" : key.trim()).apply();
    }

    public JSONObject get(String path) throws Exception { return request("GET", path, null); }
    public JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body); }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        URL url = new URL(baseUrl() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(12000); c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Mobile-Key", key());
        c.setRequestProperty("X-Mobile-Device", android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        c.disconnect();
        JSONObject out;
        try { out = sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString()); }
        catch (Exception e) { out = new JSONObject().put("error", sb.toString()); }
        if (code < 200 || code >= 300) throw new ApiException(code, out.optString("error", "HTTP " + code));
        return out;
    }

    public JSONObject health() throws Exception {
        URL url = new URL(baseUrl() + "/mobile-api/health");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000);
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { String line; while ((line = br.readLine()) != null) sb.append(line); }
        c.disconnect();
        if (code < 200 || code >= 300) throw new ApiException(code, "Server unavailable");
        return new JSONObject(sb.toString());
    }

    public JSONObject inventory(String search) throws Exception {
        String q = search == null || search.trim().isEmpty() ? "" : "?search=" + URLEncoder.encode(search.trim(), "UTF-8");
        return get("/mobile-api/inventory" + q);
    }

    public JSONObject orders(String search) throws Exception {
        String q = search == null || search.trim().isEmpty() ? "" : "?search=" + URLEncoder.encode(search.trim(), "UTF-8");
        return get("/mobile-api/orders" + q);
    }

    public JSONObject submitManualReturn(long orderId, long itemId, int qty, String condition, String notes, String idempotencyKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("idempotency_key", idempotencyKey);
        body.put("order_id", orderId);
        body.put("notes", notes == null ? "" : notes);
        JSONArray lines = new JSONArray();
        lines.put(new JSONObject().put("order_item_id", itemId).put("quantity", qty).put("condition", condition));
        body.put("lines", lines);
        return post("/mobile-api/manual-return", body);
    }

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
}
