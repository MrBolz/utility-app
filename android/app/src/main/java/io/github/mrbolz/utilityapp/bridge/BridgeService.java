package io.github.mrbolz.utilityapp.bridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the alarm bridge as a foreground service (type connectedDevice), so Android keeps the
 * process alive and lets it use the network while the phone is still in Doze.
 */
public class BridgeService extends Service {
    /** Wi-Fi wait, three attempts per device and a rediscovery fit in easily. */
    private static final long BUDGET_MS = 3 * 60_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(BridgeNotifications.ID_RUNNING, BridgeNotifications.running(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(BridgeNotifications.ID_RUNNING, BridgeNotifications.running(this));
            }
        } catch (RuntimeException e) {
            // Android refused the foreground state; the process is running now, so try anyway.
        }
        executor.execute(() -> {
            try {
                BridgeRunner.runAlarm(getApplicationContext(), BUDGET_MS);
            } finally {
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
