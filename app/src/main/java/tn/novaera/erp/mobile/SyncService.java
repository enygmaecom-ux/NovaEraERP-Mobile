package tn.novaera.erp.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncService extends Service {
    public static final String CHANNEL = "nova_live_sync";
    public static final String ACTION_DATA_CHANGED = "tn.novaera.erp.mobile.DATA_CHANGED";
    private volatile boolean running = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate(); createChannel();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("NovaEraERP").setContentText("Live sync active").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setContentIntent(pi);
        startForeground(72312, b.build());
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaEraERP:MobileSync"); wakeLock.setReferenceCounted(false); wakeLock.acquire(10 * 60 * 1000L); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) { running = true; executor.submit(this::loop); }
        return START_STICKY;
    }

    private void loop() {
        ApiClient api = new ApiClient(this);
        SharedPreferences p = getSharedPreferences(ApiClient.PREFS, MODE_PRIVATE);
        int failures = 0;
        while (running) {
            long delay = 30000L;
            try {
                if (api.configured()) {
                    JSONObject state = api.get("/mobile-api/state");
                    String token = state.optString("state_token", "");
                    String old = p.getString("state_token", "");
                    if (!token.isEmpty() && !token.equals(old)) {
                        JSONObject inv = api.inventory("");
                        p.edit().putString("inventory_cache", inv.toString()).putString("state_token", token).putString("last_sync", state.optString("server_time", "")).apply();
                        sendBroadcast(new Intent(ACTION_DATA_CHANGED).setPackage(getPackageName()));
                    } else {
                        p.edit().putString("last_sync", state.optString("server_time", "")).apply();
                    }
                    failures = 0;
                }
            } catch (Exception e) {
                failures++;
                p.edit().putString("last_sync_error", e.getMessage() == null ? "Connection error" : e.getMessage()).apply();
                delay = Math.min(300000L, 30000L * (1L << Math.min(failures, 3)));
            }
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "NovaEraERP Live Sync", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Keeps NovaEraERP inventory and order data synchronized while you use other apps.");
            NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        running = false; executor.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
