package io.github.mrbolz.utilityapp.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import io.github.mrbolz.utilityapp.tuya.TuyaDiscovery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bridge's settings, devices and run log in SharedPreferences, as JSON. Local keys and the
 * cloud secret are stored sealed by {@link SecretBox}. The plugin, the alarm receiver and the
 * service use it from different threads, so every method holds one lock.
 */
final class BridgeStore {
    private static final String PREFS = "bridges";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_BRIDGE = "bridge";
    private static final String KEY_CLOUD = "cloud";
    private static final String KEY_RUNS = "runs";
    private static final String KEY_SCHEDULED_AT = "scheduledAt";
    private static final String KEY_LAST_ALARM_RUN = "lastAlarmRun";
    private static final int MAX_RUNS = 20;
    static final String DEFAULT_REGION = "central-europe";
    private static final Object LOCK = new Object();
    private static boolean movedToDeviceStorage;

    /** A Tuya device the bridge can switch. */
    static final class Device {
        String id;
        String name;
        String ip;
        String version;
        String dpId = "1";
        String category;
        String productName;
        /** ms epoch of the last discovery that saw the device. */
        Long lastSeen;
        /** The local key, sealed by SecretBox. */
        String sealedKey;

        Device(String id) {
            this.id = id;
            this.name = id;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            put(json, "id", id);
            put(json, "name", name);
            put(json, "ip", ip);
            put(json, "version", version);
            put(json, "dpId", dpId);
            put(json, "category", category);
            put(json, "productName", productName);
            put(json, "lastSeen", lastSeen);
            put(json, "sealedKey", sealedKey);
            return json;
        }

        static Device fromJson(JSONObject json) {
            Device device = new Device(json.optString("id"));
            device.name = text(json, "name", device.id);
            device.ip = text(json, "ip", null);
            device.version = text(json, "version", null);
            device.dpId = text(json, "dpId", "1");
            device.category = text(json, "category", null);
            device.productName = text(json, "productName", null);
            device.lastSeen = json.has("lastSeen") ? json.optLong("lastSeen") : null;
            device.sealedKey = text(json, "sealedKey", null);
            return device;
        }
    }

    /** The alarm bridge: which devices to switch on, for alarms of which clock apps. */
    static final class Bridge {
        boolean enabled;
        List<String> deviceIds = new ArrayList<>();
        /** Empty: any clock app except Brutus itself. */
        List<String> clockPackages = new ArrayList<>();
    }

    /** The user's Tuya cloud project. */
    static final class Cloud {
        String accessId;
        String region = DEFAULT_REGION;
        String sealedSecret;
    }

    private final SharedPreferences prefs;

    BridgeStore(Context context) {
        Context app = context.getApplicationContext();
        // Device-protected storage: after a reboot in the night the bridge is armed again before
        // the phone is first unlocked (the keys stay sealed by SecretBox).
        Context storage = app.createDeviceProtectedStorageContext();
        synchronized (LOCK) {
            if (!movedToDeviceStorage && app.getSystemService(UserManager.class).isUserUnlocked()) {
                // Earlier versions kept the bridge in credential-protected storage; a no-op once moved.
                storage.moveSharedPreferencesFrom(app, PREFS);
                movedToDeviceStorage = true;
            }
        }
        prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Device> devices() {
        synchronized (LOCK) {
            List<Device> devices = new ArrayList<>();
            JSONArray array = array(KEY_DEVICES);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null && !json.optString("id").isEmpty()) devices.add(Device.fromJson(json));
            }
            return devices;
        }
    }

    Device device(String id) {
        for (Device device : devices()) {
            if (device.id.equals(id)) return device;
        }
        return null;
    }

    void saveDevice(Device device) {
        saveDevices(Collections.singletonList(device));
    }

    /** Adds the devices or replaces the ones with their ids. */
    void saveDevices(List<Device> changed) {
        synchronized (LOCK) {
            List<Device> devices = devices();
            for (Device device : changed) {
                boolean replaced = false;
                for (int i = 0; i < devices.size(); i++) {
                    if (devices.get(i).id.equals(device.id)) {
                        devices.set(i, device);
                        replaced = true;
                    }
                }
                if (!replaced) devices.add(device);
            }
            writeDevices(devices);
        }
    }

    /** Removes the device, also from the bridge. */
    void removeDevice(String id) {
        synchronized (LOCK) {
            List<Device> devices = devices();
            List<Device> kept = new ArrayList<>();
            for (Device device : devices) {
                if (!device.id.equals(id)) kept.add(device);
            }
            writeDevices(kept);
            Bridge bridge = bridge();
            if (bridge.deviceIds.remove(id)) saveBridge(bridge);
        }
    }

    /**
     * Updates the address, version and last-seen time of the devices discovery found.
     * Returns the ids of the found devices that are in the list.
     */
    Set<String> applyDiscovery(List<TuyaDiscovery.Found> found, long now) {
        synchronized (LOCK) {
            List<Device> devices = devices();
            Set<String> known = new HashSet<>();
            for (TuyaDiscovery.Found device : found) {
                for (Device stored : devices) {
                    if (!stored.id.equals(device.id)) continue;
                    if (device.ip != null) stored.ip = device.ip;
                    if (device.version != null) stored.version = device.version;
                    stored.lastSeen = now;
                    known.add(stored.id);
                }
            }
            if (!known.isEmpty()) writeDevices(devices);
            return known;
        }
    }

    /** Remembers a protocol version that worked for a device whose version was unknown. */
    void saveVersion(String id, String version) {
        synchronized (LOCK) {
            Device device = device(id);
            if (device == null || version.equals(device.version)) return;
            device.version = version;
            saveDevice(device);
        }
    }

    Bridge bridge() {
        synchronized (LOCK) {
            JSONObject json = object(KEY_BRIDGE);
            Bridge bridge = new Bridge();
            bridge.enabled = json.optBoolean("enabled", false);
            bridge.deviceIds = strings(json.optJSONArray("deviceIds"));
            bridge.clockPackages = strings(json.optJSONArray("clockPackages"));
            return bridge;
        }
    }

    void saveBridge(Bridge bridge) {
        JSONObject json = new JSONObject();
        put(json, "enabled", bridge.enabled);
        put(json, "deviceIds", new JSONArray(new LinkedHashSet<>(bridge.deviceIds)));
        put(json, "clockPackages", new JSONArray(new LinkedHashSet<>(bridge.clockPackages)));
        synchronized (LOCK) {
            prefs.edit().putString(KEY_BRIDGE, json.toString()).commit();
        }
    }

    Cloud cloud() {
        synchronized (LOCK) {
            JSONObject json = object(KEY_CLOUD);
            Cloud cloud = new Cloud();
            cloud.accessId = text(json, "accessId", null);
            cloud.region = text(json, "region", DEFAULT_REGION);
            cloud.sealedSecret = text(json, "sealedSecret", null);
            return cloud;
        }
    }

    void saveCloud(Cloud cloud) {
        JSONObject json = new JSONObject();
        put(json, "accessId", cloud.accessId);
        put(json, "region", cloud.region);
        put(json, "sealedSecret", cloud.sealedSecret);
        synchronized (LOCK) {
            prefs.edit().putString(KEY_CLOUD, json.toString()).commit();
        }
    }

    /** The run log, newest first. */
    JSONArray runs() {
        synchronized (LOCK) {
            return array(KEY_RUNS);
        }
    }

    /** Adds a run to the front of the log and keeps the last 20. */
    void addRun(JSONObject run) {
        synchronized (LOCK) {
            JSONArray old = array(KEY_RUNS);
            JSONArray runs = new JSONArray();
            runs.put(run);
            for (int i = 0; i < old.length() && runs.length() < MAX_RUNS; i++) runs.put(old.opt(i));
            prefs.edit().putString(KEY_RUNS, runs.toString()).commit();
        }
    }

    /** When Brutus's own alarm is due; null when none is scheduled. */
    Long scheduledAt() {
        synchronized (LOCK) {
            return prefs.contains(KEY_SCHEDULED_AT) ? prefs.getLong(KEY_SCHEDULED_AT, 0) : null;
        }
    }

    void setScheduledAt(Long at) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs.edit();
            if (at == null) editor.remove(KEY_SCHEDULED_AT);
            else editor.putLong(KEY_SCHEDULED_AT, at);
            editor.commit();
        }
    }

    /**
     * Claims an alarm run: false when the previous one started less than {@code minGapMs} ago
     * (the alarm was delivered twice), otherwise records {@code now} and returns true.
     */
    boolean claimAlarmRun(long now, long minGapMs) {
        synchronized (LOCK) {
            long last = prefs.getLong(KEY_LAST_ALARM_RUN, 0);
            if (Math.abs(now - last) < minGapMs) return false;
            prefs.edit().putLong(KEY_LAST_ALARM_RUN, now).commit();
            return true;
        }
    }

    private void writeDevices(List<Device> devices) {
        JSONArray array = new JSONArray();
        for (Device device : devices) array.put(device.toJson());
        prefs.edit().putString(KEY_DEVICES, array.toString()).commit();
    }

    private JSONArray array(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private JSONObject object(String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> strings = new ArrayList<>();
        if (array == null) return strings;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String && !strings.contains(value)) strings.add((String) value);
        }
        return strings;
    }

    /** A string field; {@code fallback} when it is missing, null or empty. */
    private static String text(JSONObject json, String key, String fallback) {
        Object value = json.opt(key);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
    }

    /** JSONObject.put without the checked exception; null values are left out. */
    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
