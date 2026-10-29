package io.github.mrbolz.utilityapp.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds the devices on the local IPv4 network:
 * <ol>
 *   <li>sweep: ping and a few TCP ports on every address of the subnet, while mDNS and SSDP
 *       listen for devices that announce themselves;
 *   <li>details: for every device found, probe common ports, look up its name and read the title
 *       of its web page.
 * </ol>
 */
final class LanScanner {
    /** Ports that most devices with any network service answer on, used to find hosts. */
    static final int[] SWEEP_PORTS = {80, 443, 22, 445, 62078, 8080, 7000, 8009, 554, 5000};
    /** Ports probed on every device that was found, to tell what it offers. */
    static final int[] DETAIL_PORTS = {21, 22, 23, 53, 80, 139, 443, 445, 515, 548, 554, 631, 1883, 3389, 5000, 5001,
            5900, 7000, 8008, 8009, 8080, 8123, 8443, 9100, 32400, 49152, 62078};

    interface Listener {
        void onProgress(String phase, int done, int total);

        void onDevice(Device device);
    }

    private final String selfIp;
    private final String gatewayIp;
    private final List<String> hosts;
    private final Listener listener;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();

    LanScanner(String selfIp, String gatewayIp, List<String> hosts, Listener listener) {
        this.selfIp = selfIp;
        this.gatewayIp = gatewayIp;
        this.hosts = hosts;
        this.listener = listener;
    }

    List<Device> run() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(64);
        ExecutorService side = Executors.newCachedThreadPool();
        try {
            Future<Map<String, Ssdp.Info>> ssdp = side.submit(() -> Ssdp.discover(3500));
            Future<MdnsBrowser.Result> mdns = side.submit(() -> MdnsBrowser.browse(4000, Collections.emptyList(), true));

            sweep(pool);

            mergeSsdp(await(ssdp, 6000));
            mergeMdns(await(mdns, 6000));
            device(selfIp).self = true;
            device(selfIp).found("self", 0L);
            if (gatewayIp != null) {
                device(gatewayIp).gateway = true;
                device(gatewayIp).found("gateway", null);
            }

            List<String> unnamed = new ArrayList<>();
            for (Device device : devices.values()) if (device.mdnsName == null && !device.self) unnamed.add(device.ip);
            Future<MdnsBrowser.Result> reverse = side.submit(() -> MdnsBrowser.browse(1500, unnamed, false));
            details(pool, side);
            mergeMdns(await(reverse, 4000));
        } finally {
            pool.shutdownNow();
            side.shutdownNow();
        }
        List<Device> result = new ArrayList<>(devices.values());
        result.sort(Comparator.comparingLong(d -> Ipv4.sortKey(d.ip)));
        for (Device device : result) listener.onDevice(device);
        return result;
    }

    private void sweep(ExecutorService pool) throws InterruptedException {
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        for (String host : hosts) {
            if (host.equals(selfIp)) {
                listener.onProgress("sweep", done.incrementAndGet(), hosts.size());
                continue;
            }
            tasks.add(pool.submit(() -> {
                try {
                    InetAddress address = InetAddress.getByName(host);
                    long start = System.nanoTime();
                    if (address.isReachable(500)) {
                        Device device = device(host);
                        device.found("ping", (System.nanoTime() - start) / 1_000_000L);
                        listener.onDevice(device);
                    } else {
                        TcpProbe.Result probe = TcpProbe.probe(address, SWEEP_PORTS, 500);
                        if (probe.alive) {
                            Device device = device(host);
                            device.found("tcp", probe.latencyMs);
                            synchronized (device) {
                                device.openPorts.addAll(probe.open);
                            }
                            listener.onDevice(device);
                        }
                    }
                } catch (IOException ignored) {
                    // Treat as not reachable.
                }
                listener.onProgress("sweep", done.incrementAndGet(), hosts.size());
            }));
        }
        awaitAll(tasks);
    }

    private void details(ExecutorService pool, ExecutorService side) throws InterruptedException {
        List<Device> found = new ArrayList<>(devices.values());
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        for (Device device : found) {
            tasks.add(pool.submit(() -> {
                try {
                    // Reverse DNS can hang for a long time, so it gets its own thread and a deadline.
                    Future<String> name = side.submit(() -> {
                        String canonical = InetAddress.getByName(device.ip).getCanonicalHostName();
                        return canonical.equals(device.ip) ? null : canonical;
                    });
                    if (!device.self) {
                        TcpProbe.Result probe = TcpProbe.probe(InetAddress.getByName(device.ip), DETAIL_PORTS, 800);
                        synchronized (device) {
                            device.openPorts.addAll(probe.open);
                            if (probe.alive && device.latencyMs == null) device.latencyMs = probe.latencyMs;
                        }
                        readWebPage(device);
                    }
                    String hostname = await(name, 2500);
                    synchronized (device) {
                        device.hostname = hostname;
                    }
                } catch (IOException ignored) {
                    // Keep what is known.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                listener.onDevice(device);
                listener.onProgress("details", done.incrementAndGet(), found.size());
            }));
        }
        awaitAll(tasks);
    }

    private void readWebPage(Device device) {
        int port;
        synchronized (device) {
            port = device.openPorts.contains(80) ? 80 : device.openPorts.contains(8080) ? 8080 : -1;
        }
        if (port == -1) return;
        try {
            URI uri = URI.create("http://" + device.ip + ":" + port + "/");
            HttpGet.Response response = HttpGet.get(uri, 1500, 64 * 1024);
            // Router pages often redirect to a login page; follow a couple of plain-HTTP redirects.
            for (int i = 0; i < 2 && response.status / 100 == 3 && response.headers.containsKey("location"); i++) {
                URI next = uri.resolve(response.headers.get("location"));
                if (!"http".equalsIgnoreCase(next.getScheme())) break;
                response = HttpGet.get(next, 1500, 64 * 1024);
            }
            String title = HttpGet.title(response.body);
            synchronized (device) {
                device.httpServer = response.headers.get("server");
                device.httpTitle = title;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Not every open port 80 serves a readable page.
        }
    }

    private void mergeSsdp(Map<String, Ssdp.Info> found) {
        if (found == null) return;
        for (Map.Entry<String, Ssdp.Info> entry : found.entrySet()) {
            if (!Ipv4.isIpv4(entry.getKey())) continue;
            Device device = device(entry.getKey());
            device.found("ssdp", null);
            synchronized (device) {
                device.upnp = entry.getValue();
            }
        }
    }

    private void mergeMdns(MdnsBrowser.Result found) {
        if (found == null) return;
        for (Map.Entry<String, List<MdnsBrowser.Service>> entry : found.servicesByIp.entrySet()) {
            Device device = device(entry.getKey());
            device.found("mdns", null);
            synchronized (device) {
                device.services.addAll(entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : found.hostnamesByIp.entrySet()) {
            Device device = devices.get(entry.getKey());
            if (device == null) continue;
            synchronized (device) {
                if (device.mdnsName == null) device.mdnsName = entry.getValue();
            }
        }
    }

    private Device device(String ip) {
        return devices.computeIfAbsent(ip, Device::new);
    }

    private static <T> T await(Future<T> future, long timeoutMs) throws InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private static void awaitAll(List<Future<?>> tasks) throws InterruptedException {
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (ExecutionException ignored) {
                // A failed probe only means less information about one host.
            }
        }
    }
}
