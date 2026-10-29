package io.github.mrbolz.utilityapp.bridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import io.github.mrbolz.utilityapp.tuya.TuyaDiscovery;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The Wi-Fi network the plugs are on: waiting for it and listening for their announcements. */
final class WifiLan {
    private WifiLan() {}

    /**
     * The connected Wi-Fi network, waiting up to {@code timeoutMs} for one (after Doze the phone
     * may still be reconnecting); null when there is none.
     */
    static Network await(Context context, long timeoutMs) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        AtomicReference<Network> found = new AtomicReference<>();
        CountDownLatch available = new CountDownLatch(1);
        // Called at once for a network that is already connected.
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                found.compareAndSet(null, network);
                available.countDown();
            }
        };
        try {
            connectivity.registerNetworkCallback(request, callback);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            available.await(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            connectivity.unregisterNetworkCallback(callback);
        }
        return found.get();
    }

    /** Listens for Tuya announcements on {@code network}, holding a multicast lock so broadcasts get through. */
    static List<TuyaDiscovery.Found> discover(Context context, Network network, int durationMs) throws IOException {
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("Brutus:tuya-discovery");
        lock.setReferenceCounted(false);
        lock.acquire();
        try {
            return TuyaDiscovery.listen(durationMs, network::bindSocket);
        } finally {
            lock.release();
        }
    }
}
