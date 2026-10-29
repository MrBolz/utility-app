package io.github.mrbolz.utilityapp.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** The notification while the bridge runs (foreground service) and the one with its result. */
final class BridgeNotifications {
    static final int ID_RUNNING = 1;
    private static final int ID_RESULT = 2;
    private static final String CHANNEL = "bridges";

    private BridgeNotifications() {}

    static Notification running(Context context) {
        return builder(context).setContentTitle("Alarm bridge").setContentText("Switching on your plugs…").setOngoing(true).build();
    }

    /** Posts the result of an alarm run, e.g. "2 of 3 plugs switched on" with the reasons. */
    static void result(Context context, JSONObject run) {
        JSONArray results = run.optJSONArray("results");
        if (results == null || results.length() == 0) return;
        int ok = 0;
        StringBuilder switched = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);
            if (result == null) continue;
            String name = result.optString("name");
            if (result.optBoolean("ok")) {
                ok++;
                switched.append(switched.length() > 0 ? ", " : "").append(name);
            } else {
                failed.append(failed.length() > 0 ? "\n" : "").append(name).append(": ").append(result.optString("message", "failed"));
            }
        }
        String title = ok == results.length() ? "Alarm bridge: switched on" : "Alarm bridge: " + ok + " of " + results.length() + " plugs switched on";
        String text = failed.length() > 0 ? failed.toString() : switched.toString();
        Notification notification = builder(context)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        context.getSystemService(NotificationManager.class).notify(ID_RESULT, notification);
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context) and priorities before channels (Android 8).
    private static Notification.Builder builder(Context context) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Alarm bridge", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shows when the alarm bridge switches your plugs on");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_LOW);
        }
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setShowWhen(true);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return builder;
    }
}
