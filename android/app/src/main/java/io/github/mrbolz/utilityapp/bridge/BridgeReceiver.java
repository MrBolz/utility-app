package io.github.mrbolz.utilityapp.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/**
 * Receives Brutus's own bridge alarm and the system broadcasts after which the alarm has to be
 * set again (next alarm clock changed, boot, app update, time or time zone change, exact-alarm
 * permission granted). Works while the app is not running: Android starts the process for it.
 */
public class BridgeReceiver extends BroadcastReceiver {
    /** A broadcast receiver gets about a minute; this leaves room for finishing up. */
    private static final long INLINE_BUDGET_MS = 45_000;
    private static final long START_WAKE_MS = 15_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (AlarmScheduler.ACTION_FIRE.equals(intent.getAction())) {
            fire(app);
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                AlarmScheduler.rescheduleQuietly(app);
            } finally {
                pending.finish();
            }
        }, "bridge-reschedule").start();
    }

    /** Runs the bridge in the foreground service; runs it here if Android does not allow starting one. */
    private void fire(Context context) {
        // The alarm's wake lock ends with onReceive: keep the CPU awake until the service holds its own.
        context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Brutus:bridge-start").acquire(START_WAKE_MS);
        Intent service = new Intent(context, BridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
            return;
        } catch (IllegalStateException | SecurityException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException. Exact
            // alarms are exempt, but the inexact fallback without battery exemption is not.
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                BridgeRunner.runAlarm(context, INLINE_BUDGET_MS);
            } finally {
                pending.finish();
            }
        }, "bridge-alarm").start();
    }
}
