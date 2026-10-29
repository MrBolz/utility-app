package io.github.mrbolz.utilityapp.bridge;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.mrbolz.utilityapp.tuya.TuyaCloud;
import io.github.mrbolz.utilityapp.tuya.TuyaDiscovery;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Bridges tab: Tuya devices, the alarm bridge and its run log. Local keys and the cloud
 * secret stay in native storage; the web layer only learns whether they are set. Every method
 * runs on a background thread and rejects with a readable message.
 */
@CapacitorPlugin(name = "Bridges")
public class BridgesPlugin extends Plugin {
    private static final long RUN_BUDGET_MS = 3 * 60_000L;
    private static final List<String> VERSIONS = Arrays.asList("3.1", "3.2", "3.3", "3.4", "3.5");

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Returns the result for call.resolve, or null to resolve without data. */
    private interface Action {
        JSObject run() throws BridgeException;
    }

    @Override
    public void load() {
        Context context = context();
        // A force-stopped app loses its alarms and gets no broadcasts until it is opened again.
        executor.execute(() -> AlarmScheduler.rescheduleQuietly(context));
    }

    @PluginMethod
    public void getState(PluginCall call) {
        background(call, this::state);
    }

    @PluginMethod
    public void saveBridge(PluginCall call) {
        background(call, () -> {
            BridgeStore store = new BridgeStore(context());
            Set<String> known = new HashSet<>();
            for (BridgeStore.Device device : store.devices()) known.add(device.id);
            BridgeStore.Bridge bridge = new BridgeStore.Bridge();
            bridge.enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
            for (String id : strings(call.getArray("deviceIds"))) {
                if (known.contains(id)) bridge.deviceIds.add(id);
            }
            bridge.clockPackages = strings(call.getArray("clockPackages"));
            store.saveBridge(bridge);
            return state();
        });
    }

    @PluginMethod
    public void saveCloud(PluginCall call) {
        background(call, () -> {
            String accessId = trimmed(call.getString("accessId"));
            if (accessId == null) throw new BridgeException("Enter the Access ID of your Tuya cloud project");
            String region = call.getString("region", BridgeStore.DEFAULT_REGION);
            region(region);
            BridgeStore store = new BridgeStore(context());
            BridgeStore.Cloud cloud = store.cloud();
            cloud.accessId = accessId;
            cloud.region = region;
            String secret = trimmed(call.getString("accessSecret"));
            if (secret != null) cloud.sealedSecret = seal(secret);
            store.saveCloud(cloud);
            return state();
        });
    }

    @PluginMethod
    public void importFromCloud(PluginCall call) {
        background(call, () -> {
            BridgeStore store = new BridgeStore(context());
            BridgeStore.Cloud cloud = store.cloud();
            if (cloud.accessId == null || cloud.sealedSecret == null) {
                throw new BridgeException("Enter the Access ID and Access Secret of your Tuya cloud project first");
            }
            String secret = SecretBox.open(cloud.sealedSecret);
            if (secret == null) throw new BridgeException("The saved Access Secret cannot be read — enter it again");
            List<TuyaCloud.CloudDevice> found;
            try {
                found = new TuyaCloud(region(cloud.region), cloud.accessId, secret).listDevices();
            } catch (TuyaCloud.TuyaCloudException | IllegalArgumentException e) {
                throw new BridgeException(e.getMessage());
            } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
                throw new BridgeException("Could not reach the Tuya cloud — check the internet connection");
            } catch (IOException e) {
                String message = String.valueOf(e.getMessage());
                throw new BridgeException(message.contains("Tuya") ? message : "The Tuya cloud request failed (" + message + ")");
            }
            // Keeps what only the phone knows: address, version and DP id.
            List<BridgeStore.Device> merged = new ArrayList<>();
            for (TuyaCloud.CloudDevice entry : found) {
                BridgeStore.Device device = store.device(entry.id);
                if (device == null) device = new BridgeStore.Device(entry.id);
                device.name = entry.name;
                if (entry.category != null) device.category = entry.category;
                if (entry.productName != null) device.productName = entry.productName;
                if (entry.localKey != null && !entry.localKey.isEmpty()) device.sealedKey = seal(entry.localKey);
                merged.add(device);
            }
            store.saveDevices(merged);
            JSObject result = new JSObject();
            result.put("state", state());
            result.put("imported", merged.size());
            return result;
        });
    }

    @PluginMethod
    public void discover(PluginCall call) {
        background(call, () -> {
            Context context = context();
            int durationMs = Math.max(1000, Math.min(30_000, call.getInt("durationMs", BridgeRunner.DISCOVERY_MS)));
            Network network = WifiLan.await(context, 3000);
            if (network == null) throw new BridgeException("Connect the phone to the Wi-Fi of your plugs first");
            List<TuyaDiscovery.Found> found;
            try {
                found = WifiLan.discover(context, network, durationMs);
            } catch (IOException e) {
                throw new BridgeException("Cannot listen for devices on the Wi-Fi (" + e.getMessage() + ")");
            }
            Set<String> known = new BridgeStore(context).applyDiscovery(found, System.currentTimeMillis());
            JSArray devices = new JSArray();
            for (TuyaDiscovery.Found device : found) {
                JSObject json = new JSObject();
                json.put("id", device.id);
                json.put("ip", device.ip);
                json.put("version", device.version);
                json.put("productKey", device.productKey);
                json.put("known", known.contains(device.id));
                devices.put(json);
            }
            JSObject result = new JSObject();
            result.put("state", state());
            result.put("found", devices);
            return result;
        });
    }

    @PluginMethod
    public void saveDevice(PluginCall call) {
        background(call, () -> {
            String id = trimmed(call.getString("id"));
            if (id == null) throw new BridgeException("Enter the device ID");
            BridgeStore store = new BridgeStore(context());
            BridgeStore.Device device = store.device(id);
            if (device == null) device = new BridgeStore.Device(id);
            String name = trimmed(call.getString("name"));
            if (name != null) device.name = name;
            // An empty IP clears it.
            if (call.getString("ip") != null) device.ip = trimmed(call.getString("ip"));
            String version = trimmed(call.getString("version"));
            if (version != null) {
                if (!VERSIONS.contains(version)) throw new BridgeException("Unsupported protocol version " + version);
                device.version = version;
            }
            String dpId = trimmed(call.getString("dpId"));
            if (dpId != null) device.dpId = dpId;
            String localKey = trimmed(call.getString("localKey"));
            if (localKey != null) {
                if (localKey.length() != 16) throw new BridgeException("A local key has 16 characters");
                device.sealedKey = seal(localKey);
            }
            store.saveDevice(device);
            return state();
        });
    }

    @PluginMethod
    public void removeDevice(PluginCall call) {
        background(call, () -> {
            String id = call.getString("id");
            if (id != null) new BridgeStore(context()).removeDevice(id);
            return state();
        });
    }

    @PluginMethod
    public void setSwitch(PluginCall call) {
        background(call, () -> {
            String id = call.getString("id");
            Boolean on = call.getBoolean("on");
            if (id == null || on == null) throw new BridgeException("Missing device or state");
            JSObject result = new JSObject();
            result.put("ok", BridgeRunner.switchNow(context(), id, on));
            return result;
        });
    }

    @PluginMethod
    public void runNow(PluginCall call) {
        background(call, () -> {
            JSONObject run = BridgeRunner.run(context(), BridgeRunner.TRIGGER_TEST, RUN_BUDGET_MS);
            JSObject result = new JSObject();
            result.put("state", state());
            result.put("run", run);
            return result;
        });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        background(call, () -> {
            Context context = getContext();
            Uri packageUri = Uri.parse("package:" + context.getPackageName());
            Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri);
            Intent intent;
            String target = String.valueOf(call.getString("target"));
            switch (target) {
                case "exactAlarms":
                    intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri) : appInfo;
                    break;
                case "battery":
                    // Allowed by REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: asks the user in a system dialog.
                    // Once granted, that dialog shows nothing, so open the list where it can be undone.
                    intent = context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName())
                            ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri);
                    break;
                case "notifications":
                    intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                            : appInfo;
                    break;
                case "appInfo":
                    intent = appInfo;
                    break;
                default:
                    throw new BridgeException("Unknown settings page " + target);
            }
            try {
                start(context, intent);
            } catch (ActivityNotFoundException e) {
                // Some phones lack the specific page.
                try {
                    start(context, appInfo);
                } catch (ActivityNotFoundException again) {
                    throw new BridgeException("This phone has no settings page for that");
                }
            }
            return null;
        });
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdown();
    }

    /** Everything the tab shows. Also brings Brutus's alarm up to date (permissions or alarms may have changed meanwhile). */
    private JSObject state() {
        Context context = context();
        BridgeStore store = new BridgeStore(context);
        Long scheduledAt = AlarmScheduler.rescheduleQuietly(context);
        JSObject state = new JSObject();

        JSArray devices = new JSArray();
        for (BridgeStore.Device device : store.devices()) {
            JSObject json = new JSObject();
            json.put("id", device.id);
            json.put("name", device.name);
            json.put("ip", device.ip);
            json.put("version", device.version);
            json.put("dpId", device.dpId);
            json.put("category", device.category);
            json.put("productName", device.productName);
            json.put("hasKey", readable(device.sealedKey));
            json.put("lastSeen", device.lastSeen);
            devices.put(json);
        }
        state.put("devices", devices);

        BridgeStore.Bridge bridge = store.bridge();
        JSObject bridgeJson = new JSObject();
        bridgeJson.put("enabled", bridge.enabled);
        bridgeJson.put("deviceIds", new JSArray(bridge.deviceIds));
        bridgeJson.put("clockPackages", new JSArray(bridge.clockPackages));
        state.put("bridge", bridgeJson);

        AlarmScheduler.NextAlarm next = AlarmScheduler.nextAlarm(context, bridge.clockPackages);
        if (next == null) {
            state.put("nextAlarm", JSONObject.NULL);
        } else {
            JSObject alarm = new JSObject();
            alarm.put("triggerAt", next.triggerAt);
            alarm.put("packageName", next.packageName);
            alarm.put("appLabel", next.appLabel);
            alarm.put("matches", next.matches);
            state.put("nextAlarm", alarm);
        }
        state.put("scheduledAt", scheduledAt);

        JSObject permissions = new JSObject();
        permissions.put("exactAlarms", AlarmScheduler.canScheduleExactAlarms(context));
        permissions.put("batteryUnrestricted", context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName()));
        permissions.put("notifications", context.getSystemService(NotificationManager.class).areNotificationsEnabled());
        state.put("permissions", permissions);

        BridgeStore.Cloud cloud = store.cloud();
        JSObject cloudJson = new JSObject();
        cloudJson.put("accessId", cloud.accessId);
        cloudJson.put("region", cloud.region);
        cloudJson.put("hasSecret", readable(cloud.sealedSecret));
        state.put("cloud", cloudJson);

        JSONArray runs = store.runs();
        state.put("runs", runs);
        return state;
    }

    private void background(PluginCall call, Action action) {
        executor.execute(() -> {
            try {
                JSObject result = action.run();
                if (result == null) call.resolve();
                else call.resolve(result);
            } catch (BridgeException e) {
                call.reject(e.getMessage());
            } catch (RuntimeException e) {
                call.reject("Something went wrong: " + e.getMessage(), e);
            }
        });
    }

    private Context context() {
        return getContext().getApplicationContext();
    }

    private static void start(Context context, Intent intent) {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static TuyaCloud.Region region(String id) throws BridgeException {
        try {
            return TuyaCloud.Region.fromId(id);
        } catch (IllegalArgumentException e) {
            throw new BridgeException(e.getMessage());
        }
    }

    private static String seal(String secret) throws BridgeException {
        try {
            return SecretBox.seal(secret);
        } catch (GeneralSecurityException e) {
            throw new BridgeException("Could not store the key securely on this phone (" + e.getMessage() + ")");
        }
    }

    /** Whether a sealed secret is set and can still be opened (not after a backup was restored onto another phone). */
    private static boolean readable(String sealed) {
        return sealed != null && SecretBox.open(sealed) != null;
    }

    /** The trimmed text; null when missing or blank. */
    private static String trimmed(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> strings(JSArray array) {
        List<String> strings = new ArrayList<>();
        if (array == null) return strings;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String && !strings.contains(value)) strings.add((String) value);
        }
        return strings;
    }
}
