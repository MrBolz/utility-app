package io.github.mrbolz.utilityapp.bridge;

import android.content.Context;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import io.github.mrbolz.utilityapp.tuya.TuyaDevice;
import io.github.mrbolz.utilityapp.tuya.TuyaDiscovery;
import io.github.mrbolz.utilityapp.tuya.TuyaException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.SocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Switches the bridge's plugs on over the Wi-Fi: waits for the network, retries, looks for plugs
 * that moved to another address, paces the devices and records the run.
 */
final class BridgeRunner {
    static final String TRIGGER_ALARM = "alarm";
    static final String TRIGGER_TEST = "test";
    static final String NO_WIFI = "Not connected to Wi-Fi";
    static final String TAG = "Brutus";

    /** An alarm delivered twice runs once. */
    private static final long DUPLICATE_MS = 2 * 60_000L;
    /** After Doze the phone may need a while to reconnect to the Wi-Fi. */
    private static final long WIFI_WAIT_MS = 30_000;
    private static final int TIMEOUT_MS = 5000;
    static final int DISCOVERY_MS = 6000;
    /** Some plugs drop offline briefly when commanded too fast. */
    private static final long PACE_MS = 300;
    private static final long[] BACKOFF_MS = {1000, 3000};
    /** Tried in turn when a device's protocol version is unknown. */
    private static final String[] VERSIONS = {"3.3", "3.4", "3.5"};
    /** Plugs accept one local connection at a time, so runs and switch tests take turns. */
    private static final Object DEVICE_LOCK = new Object();

    private BridgeRunner() {}

    /**
     * What Brutus's alarm does: switches the bridge's devices on, unless the bridge is off or ran
     * less than two minutes ago, posts the result and schedules the next alarm.
     */
    static void runAlarm(Context context, long budgetMs) {
        try {
            BridgeStore store = new BridgeStore(context);
            if (store.bridge().enabled && store.claimAlarmRun(System.currentTimeMillis(), DUPLICATE_MS)) {
                BridgeNotifications.result(context, run(context, TRIGGER_ALARM, budgetMs));
            }
        } catch (RuntimeException e) {
            // Never crash in the background: the next alarm still has to be set.
            Log.w(TAG, "Alarm run failed", e);
        }
        AlarmScheduler.rescheduleQuietly(context);
    }

    /** Switches the bridge's devices on, records the run in the log and returns it. */
    static JSONObject run(Context context, String trigger, long budgetMs) {
        long at = System.currentTimeMillis();
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        BridgeStore store = new BridgeStore(context);
        PowerManager.WakeLock wakeLock = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Brutus:bridge");
        @SuppressWarnings("deprecation") // Still keeps the Wi-Fi awake on the Android versions that honour it.
        WifiManager.WifiLock wifiLock = context.getApplicationContext().getSystemService(WifiManager.class)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Brutus:bridge");
        wifiLock.setReferenceCounted(false);
        JSONArray results = new JSONArray();
        wakeLock.acquire(budgetMs + 10_000);
        try {
            wifiLock.acquire();
            synchronized (DEVICE_LOCK) {
                List<BridgeStore.Device> devices = selected(store);
                Network network = devices.isEmpty() ? null : WifiLan.await(context, Math.min(WIFI_WAIT_MS, remaining(deadline)));
                Lan lan = network == null ? null : new Lan(context, store, network);
                for (int i = 0; i < devices.size(); i++) {
                    if (i > 0) pause(PACE_MS, deadline);
                    BridgeStore.Device device = devices.get(i);
                    String failure = null;
                    try {
                        if (lan == null) throw new BridgeException(NO_WIFI);
                        switchOn(lan, device, deadline);
                    } catch (BridgeException e) {
                        failure = e.getMessage();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Switching " + device.id + " failed", e);
                        failure = "Unexpected error (" + e + ")";
                    }
                    JSONObject result = new JSONObject();
                    put(result, "id", device.id);
                    put(result, "name", device.name);
                    put(result, "ok", failure == null);
                    put(result, "message", failure);
                    results.put(result);
                }
            }
        } finally {
            wifiLock.release();
            if (wakeLock.isHeld()) wakeLock.release();
        }
        JSONObject run = new JSONObject();
        put(run, "at", at);
        put(run, "trigger", trigger);
        put(run, "results", results);
        store.addRun(run);
        return run;
    }

    /**
     * Switches one device on or off now (the On/Off test buttons), looking for it on the network
     * first if its address or version is unknown. Returns false when it reported another value.
     */
    static boolean switchNow(Context context, String id, boolean on) throws BridgeException {
        BridgeStore store = new BridgeStore(context);
        BridgeStore.Device device = store.device(id);
        if (device == null) throw new BridgeException("Unknown device " + id);
        String key = localKey(device);
        Network network = WifiLan.await(context, 3000);
        if (network == null) throw new BridgeException(NO_WIFI);
        synchronized (DEVICE_LOCK) {
            long deadline = SystemClock.elapsedRealtime() + 60_000;
            Lan lan = new Lan(context, store, network);
            if (device.ip == null || device.version == null) lan.refresh(device, deadline);
            if (device.ip == null) throw new BridgeException(noAddress(device));
            try {
                Object value = lan.send(device, key, on).get(device.dpId);
                return value == null || Boolean.valueOf(on).equals(value);
            } catch (IOException | TuyaException e) {
                throw new BridgeException(explain(e, device));
            }
        }
    }

    /** Three attempts with backoff; then looks for the device on the network and tries once more. */
    private static void switchOn(Lan lan, BridgeStore.Device device, long deadline) throws BridgeException {
        if (remaining(deadline) <= 0) throw new BridgeException("Skipped: the run took too long");
        String key = localKey(device);
        if (device.ip == null || device.version == null) lan.refresh(device, deadline);
        if (device.ip == null) throw new BridgeException(noAddress(device));
        Exception failure = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (attempt > 0 && !pause(BACKOFF_MS[attempt - 1], deadline)) break;
            try {
                lan.send(device, key, true);
                return;
            } catch (TuyaException e) {
                failure = e;
                if (e.reason() == TuyaException.Reason.UNSUPPORTED_VERSION) throw new BridgeException(explain(e, device));
            } catch (IOException e) {
                failure = e;
            }
        }
        // The plug may have got a new address from the router, or report another protocol version.
        String ip = device.ip;
        String version = device.version;
        boolean keyRejected = failure instanceof TuyaException && ((TuyaException) failure).reason() == TuyaException.Reason.KEY_REJECTED;
        if (lan.refresh(device, deadline) && remaining(deadline) > 0
                && (!keyRejected || !Objects.equals(ip, device.ip) || !Objects.equals(version, device.version))) {
            try {
                lan.send(device, key, true);
                return;
            } catch (IOException | TuyaException e) {
                failure = e;
            }
        }
        throw new BridgeException(explain(failure, device));
    }

    /** The bridge's devices, in the order the user ticked them. */
    private static List<BridgeStore.Device> selected(BridgeStore store) {
        List<BridgeStore.Device> devices = store.devices();
        List<BridgeStore.Device> selected = new ArrayList<>();
        for (String id : store.bridge().deviceIds) {
            for (BridgeStore.Device device : devices) {
                if (device.id.equals(id)) selected.add(device);
            }
        }
        return selected;
    }

    private static String localKey(BridgeStore.Device device) throws BridgeException {
        if (device.sealedKey == null) throw new BridgeException("No local key — import it from the Tuya cloud or add it manually");
        String key = SecretBox.open(device.sealedKey);
        if (key == null) throw new BridgeException("The saved local key cannot be read — import the keys again");
        return key;
    }

    private static String noAddress(BridgeStore.Device device) {
        return "IP address unknown and " + device.name + " did not answer on the Wi-Fi — use Find on network or enter it";
    }

    /** A readable reason for a failed request. */
    static String explain(Exception e, BridgeStore.Device device) {
        if (e instanceof TuyaException) {
            switch (((TuyaException) e).reason()) {
                case KEY_REJECTED:
                    return "Wrong local key or protocol version — import the keys again";
                case BUSY:
                    return "The plug is busy (another app is connected to it)";
                case UNREACHABLE:
                    return "Plug not reachable at " + device.ip;
                case TIMEOUT:
                    return "The plug at " + device.ip + " did not answer";
                case BAD_RESPONSE:
                    return "The plug sent an unreadable answer";
                case DEVICE_ERROR:
                case UNSUPPORTED_VERSION:
                default:
                    return e.getMessage();
            }
        }
        return "Connection to " + device.ip + " failed (" + e.getMessage() + ")";
    }

    private static long remaining(long deadline) {
        return deadline - SystemClock.elapsedRealtime();
    }

    /** Sleeps up to {@code ms}; false when the run is out of time or interrupted. */
    private static boolean pause(long ms, long deadline) {
        long wait = Math.min(ms, remaining(deadline));
        if (wait <= 0) return false;
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The Wi-Fi network of a run and what one discovery on it found (run when first needed). */
    private static final class Lan {
        private final Context context;
        private final BridgeStore store;
        private final Network network;
        private final SocketFactory sockets;
        private Map<String, TuyaDiscovery.Found> found;

        Lan(Context context, BridgeStore store, Network network) {
            this.context = context;
            this.store = store;
            this.network = network;
            this.sockets = network.getSocketFactory();
        }

        /** Looks for the device's announcements and takes its address and version from them; true when it was heard. */
        boolean refresh(BridgeStore.Device device, long deadline) {
            if (found == null) {
                if (remaining(deadline) < DISCOVERY_MS) return false;
                found = new HashMap<>();
                try {
                    List<TuyaDiscovery.Found> heard = WifiLan.discover(context, network, DISCOVERY_MS);
                    for (TuyaDiscovery.Found announcement : heard) found.put(announcement.id, announcement);
                    store.applyDiscovery(heard, System.currentTimeMillis());
                } catch (IOException | RuntimeException e) {
                    // The discovery ports are not available, or a broken datagram: go on with what is known.
                    Log.w(TAG, "Discovery failed", e);
                }
            }
            TuyaDiscovery.Found announcement = found.get(device.id);
            if (announcement == null) return false;
            if (announcement.ip != null) device.ip = announcement.ip;
            if (announcement.version != null) device.version = announcement.version;
            return true;
        }

        /**
         * Writes the device's switch DP. When its protocol version is unknown, tries the common
         * ones and remembers the one that works. Returns the DPs the device reported back.
         */
        Map<String, Object> send(BridgeStore.Device device, String key, boolean on) throws IOException, TuyaException {
            String[] versions = device.version != null ? new String[] {device.version} : VERSIONS;
            boolean probing = versions.length > 1;
            TuyaException rejected = null;
            TuyaException last = null;
            for (String version : versions) {
                if (last != null) pause(PACE_MS, Long.MAX_VALUE);
                Map<String, Object> dps = new LinkedHashMap<>();
                dps.put(device.dpId, on);
                try (TuyaDevice tuya = new TuyaDevice(device.id, key, device.ip, version, sockets, TIMEOUT_MS)) {
                    Map<String, Object> reported = tuya.setDps(dps);
                    if (device.version == null) {
                        device.version = version;
                        store.saveVersion(device.id, version);
                    }
                    return reported;
                } catch (TuyaException e) {
                    // A device that speaks another version either drops the connection, like one with
                    // another key, or ignores the frame (tinytuya's scanner treats both the same).
                    boolean timedOut = e.reason() == TuyaException.Reason.TIMEOUT;
                    if (e.reason() != TuyaException.Reason.KEY_REJECTED && !(probing && timedOut)) throw e;
                    if (!timedOut) rejected = e;
                    last = e;
                }
            }
            throw rejected != null ? rejected : last;
        }
    }
}
