package io.github.mrbolz.utilityapp.bridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.util.List;

/**
 * Follows the next alarm clock of the phone: when it comes from an allowed clock app, Brutus sets
 * its own exact alarm for the same moment, which runs the bridge. setExactAndAllowWhileIdle, not
 * setAlarmClock: that would make Brutus's alarm the next alarm clock itself.
 */
final class AlarmScheduler {
    static final String ACTION_FIRE = "io.github.mrbolz.utilityapp.bridge.FIRE";
    static final String EXTRA_TRIGGER_AT = "triggerAt";

    /** The phone's next alarm clock. */
    static final class NextAlarm {
        final long triggerAt;
        /** The app that set it; null when Android does not say. */
        final String packageName;
        final String appLabel;
        /** Whether it comes from an allowed clock app. */
        final boolean matches;

        NextAlarm(long triggerAt, String packageName, String appLabel, boolean matches) {
            this.triggerAt = triggerAt;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.matches = matches;
        }
    }

    private AlarmScheduler() {}

    /** The next alarm clock, or null when none is set. */
    static NextAlarm nextAlarm(Context context, List<String> clockPackages) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo info = alarms.getNextAlarmClock();
        if (info == null) return null;
        String packageName = info.getShowIntent() == null ? null : info.getShowIntent().getCreatorPackage();
        boolean matches = clockPackages.isEmpty()
                ? !context.getPackageName().equals(packageName)
                : packageName != null && clockPackages.contains(packageName);
        return new NextAlarm(info.getTriggerTime(), packageName, appLabel(context, packageName), matches);
    }

    /**
     * Sets or cancels Brutus's alarm to match the bridge settings and the next alarm clock, and
     * records when it is due. Returns that time, or null when nothing is scheduled.
     */
    static synchronized Long reschedule(Context context) {
        BridgeStore store = new BridgeStore(context);
        BridgeStore.Bridge bridge = store.bridge();
        NextAlarm next = bridge.enabled && !bridge.deviceIds.isEmpty() ? nextAlarm(context, bridge.clockPackages) : null;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (next == null || !next.matches || next.triggerAt <= System.currentTimeMillis()) {
            PendingIntent existing = fireIntent(context, 0, PendingIntent.FLAG_NO_CREATE);
            if (existing != null) {
                alarms.cancel(existing);
                existing.cancel();
            }
            store.setScheduledAt(null);
            return null;
        }
        PendingIntent fire = fireIntent(context, next.triggerAt, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (canScheduleExactAlarms(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAt, fire);
            } else {
                // Without the exact-alarm permission Android may run it a few minutes late.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAt, fire);
            }
        } catch (SecurityException e) {
            // The permission was revoked in the meantime.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.triggerAt, fire);
        }
        store.setScheduledAt(next.triggerAt);
        return next.triggerAt;
    }

    /** reschedule that never throws: in receivers and services an exception would crash the app. */
    static Long rescheduleQuietly(Context context) {
        try {
            return reschedule(context);
        } catch (RuntimeException e) {
            Log.w(BridgeRunner.TAG, "Could not set the bridge alarm", e);
            return new BridgeStore(context).scheduledAt();
        }
    }

    /** Android 12 needs the user's consent for exact alarms; from 13 on USE_EXACT_ALARM grants it. */
    static boolean canScheduleExactAlarms(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static PendingIntent fireIntent(Context context, long triggerAt, int flags) {
        Intent intent = new Intent(context, BridgeReceiver.class).setAction(ACTION_FIRE).putExtra(EXTRA_TRIGGER_AT, triggerAt);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The app's name; its package name when Android does not reveal it. */
    private static String appLabel(Context context, String packageName) {
        if (packageName == null) return null;
        PackageManager packages = context.getPackageManager();
        try {
            return packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
