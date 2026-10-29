package io.github.mrbolz.utilityapp.network;

import android.Manifest;
import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Network details and local device discovery for the Network tab. Location access is optional:
 * Android only reveals the Wi-Fi name and nearby networks to apps that have it.
 */
@CapacitorPlugin(
        name = "NetworkScanner",
        permissions = {
            @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
        })
public class NetworkScannerPlugin extends Plugin {
    private static final String[][] LATENCY_TARGETS = {{"1.1.1.1", "443"}, {"8.8.8.8", "443"}};

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean scanning = new AtomicBoolean();

    @PluginMethod
    public void getInfo(PluginCall call) {
        executor.execute(() -> {
            JSObject info = NetworkInfoReader.read(getContext(), locationGranted(), new NetworkInfoReader.Lan());
            Long latency = internetLatencyMs();
            if (latency != null) info.put("internetLatencyMs", latency);
            call.resolve(info);
        });
    }

    @PluginMethod
    public void scan(PluginCall call) {
        NetworkInfoReader.Lan lan = new NetworkInfoReader.Lan();
        NetworkInfoReader.read(getContext(), locationGranted(), lan);
        if (lan.ipAddress == null) {
            call.reject("Not connected to a Wi-Fi or Ethernet network");
            return;
        }
        List<String> hosts = Ipv4.scanRange(lan.ipAddress, lan.prefixLength);
        if (hosts.isEmpty()) {
            call.reject("This network cannot be scanned");
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            call.reject("A scan is already running");
            return;
        }
        String selfName = selfName();
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                LanScanner scanner = new LanScanner(lan.ipAddress, lan.gateway, hosts, new LanScanner.Listener() {
                    @Override
                    public void onProgress(String phase, int done, int total) {
                        JSObject progress = new JSObject();
                        progress.put("phase", phase);
                        progress.put("done", done);
                        progress.put("total", total);
                        notifyListeners("scanProgress", progress);
                    }

                    @Override
                    public void onDevice(Device device) {
                        notifyListeners("device", toJson(device, selfName));
                    }
                });
                JSArray devices = new JSArray();
                for (Device device : scanner.run()) devices.put(toJson(device, selfName));
                JSObject result = new JSObject();
                result.put("devices", devices);
                result.put("scannedHosts", hosts.size());
                result.put("subnet", hosts.get(0) + " – " + hosts.get(hosts.size() - 1));
                result.put("durationMs", System.currentTimeMillis() - start);
                call.resolve(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.reject("Scan interrupted");
            } catch (RuntimeException e) {
                call.reject("Scan failed: " + e.getMessage(), e);
            } finally {
                scanning.set(false);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdownNow();
    }

    private boolean locationGranted() {
        return getPermissionState("location") == PermissionState.GRANTED;
    }

    private String selfName() {
        String name = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
                ? Settings.Global.getString(getContext().getContentResolver(), Settings.Global.DEVICE_NAME)
                : null;
        return name != null ? name : Build.MANUFACTURER + " " + Build.MODEL;
    }

    /** Time to open a TCP connection to well-known public servers; the fastest one wins. */
    private static Long internetLatencyMs() {
        Long best = null;
        for (String[] target : LATENCY_TARGETS) {
            try (Socket socket = new Socket()) {
                long start = System.nanoTime();
                socket.connect(new InetSocketAddress(target[0], Integer.parseInt(target[1])), 2000);
                long ms = (System.nanoTime() - start) / 1_000_000L;
                if (best == null || ms < best) best = ms;
            } catch (IOException ignored) {
                // Try the next one.
            }
        }
        return best;
    }

    static JSObject toJson(Device device, String selfName) {
        JSObject json = new JSObject();
        synchronized (device) {
            json.put("ip", device.ip);
            json.put("self", device.self);
            json.put("gateway", device.gateway);
            if (device.self) json.put("selfName", selfName);
            JSArray sources = new JSArray();
            for (String source : device.sources) sources.put(source);
            json.put("sources", sources);
            if (device.latencyMs != null) json.put("latencyMs", device.latencyMs);
            json.put("hostname", device.hostname);
            json.put("mdnsName", device.mdnsName);
            JSArray ports = new JSArray();
            for (int port : device.openPorts) ports.put(port);
            json.put("openPorts", ports);
            JSArray services = new JSArray();
            for (MdnsBrowser.Service service : device.services) {
                JSObject entry = new JSObject();
                entry.put("name", service.name);
                entry.put("type", service.type);
                entry.put("port", service.port);
                JSObject txt = new JSObject();
                for (Map.Entry<String, String> pair : service.txt.entrySet()) txt.put(pair.getKey(), pair.getValue());
                entry.put("txt", txt);
                services.put(entry);
            }
            json.put("services", services);
            if (device.upnp != null) {
                JSObject upnp = new JSObject();
                upnp.put("friendlyName", device.upnp.friendlyName);
                upnp.put("manufacturer", device.upnp.manufacturer);
                upnp.put("modelName", device.upnp.modelName);
                upnp.put("modelNumber", device.upnp.modelNumber);
                upnp.put("deviceType", device.upnp.deviceType);
                upnp.put("server", device.upnp.server);
                json.put("upnp", upnp);
            }
            json.put("httpTitle", device.httpTitle);
            json.put("httpServer", device.httpServer);
        }
        return json;
    }
}
