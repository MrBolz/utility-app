package io.github.mrbolz.utilityapp.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Reads what Android knows about the current connection and the local network. */
final class NetworkInfoReader {
    /** The local IPv4 network the scanner works on. */
    static final class Lan {
        String ipAddress;
        int prefixLength = -1;
        String gateway;
    }

    private static final int MAX_NEARBY_NETWORKS = 30;

    private NetworkInfoReader() {}

    @SuppressWarnings("deprecation") // getAllNetworks, getConnectionInfo and ScanResult.SSID still work and cover all API levels.
    static JSObject read(Context context, boolean locationGranted, Lan lan) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        JSObject info = new JSObject();
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities activeCaps = active == null ? null : connectivity.getNetworkCapabilities(active);
        info.put("connected", activeCaps != null);
        info.put("locationPermission", locationGranted);
        if (activeCaps != null) {
            info.put("transport", transport(activeCaps));
            info.put("internet", activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            info.put("captivePortal", activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
            info.put("metered", !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            info.put("vpn", activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            info.put("downstreamKbps", activeCaps.getLinkDownstreamBandwidthKbps());
            info.put("upstreamKbps", activeCaps.getLinkUpstreamBandwidthKbps());
        }

        // The local network is Wi-Fi or Ethernet, even while a VPN is the active network.
        Network local = null;
        boolean localIsWifi = false;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null) continue;
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            if (wifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                local = network;
                localIsWifi = wifi;
                if (network.equals(active)) break;
            }
        }
        LinkProperties link = local == null ? null : connectivity.getLinkProperties(local);
        if (link != null) {
            info.put("interfaceName", link.getInterfaceName());
            for (LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    lan.ipAddress = address.getAddress().getHostAddress();
                    lan.prefixLength = address.getPrefixLength();
                    break;
                }
            }
            for (RouteInfo route : link.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                    lan.gateway = route.getGateway().getHostAddress();
                    break;
                }
            }
            JSArray dns = new JSArray();
            for (InetAddress server : link.getDnsServers()) dns.put(server.getHostAddress());
            info.put("dnsServers", dns);
            info.put("domain", link.getDomains());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.put("privateDns", link.isPrivateDnsActive());
                info.put("privateDnsServer", link.getPrivateDnsServerName());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.put("mtu", link.getMtu());
        }
        info.put("ipAddress", lan.ipAddress);
        if (lan.prefixLength >= 0) info.put("prefixLength", lan.prefixLength);
        info.put("gateway", lan.gateway);

        if (localIsWifi) {
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            info.put("wifi", wifi(wifi, locationGranted));
        }
        return info;
    }

    @SuppressWarnings("deprecation")
    private static JSObject wifi(WifiManager manager, boolean locationGranted) {
        JSObject wifi = new JSObject();
        WifiInfo connection = manager.getConnectionInfo();
        if (connection == null) return wifi;
        wifi.put("ssid", cleanSsid(connection.getSSID()));
        String bssid = connection.getBSSID();
        if (bssid != null && !bssid.equals("02:00:00:00:00:00")) wifi.put("bssid", bssid);
        int rssi = connection.getRssi();
        wifi.put("rssi", rssi);
        wifi.put("signalPercent", signalPercent(manager, rssi));
        wifi.put("linkSpeedMbps", connection.getLinkSpeed());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifi.put("txLinkSpeedMbps", connection.getTxLinkSpeedMbps());
            wifi.put("rxLinkSpeedMbps", connection.getRxLinkSpeedMbps());
        }
        int frequency = connection.getFrequency();
        wifi.put("frequencyMhz", frequency);
        wifi.put("channel", channel(frequency));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifi.put("standard", wifiStandard(connection.getWifiStandard()));

        if (locationGranted) {
            List<ScanResult> results = new ArrayList<>(manager.getScanResults());
            results.sort((a, b) -> Integer.compare(b.level, a.level));
            JSArray nearby = new JSArray();
            for (ScanResult result : results.subList(0, Math.min(results.size(), MAX_NEARBY_NETWORKS))) {
                JSObject network = new JSObject();
                network.put("ssid", result.SSID == null || result.SSID.isEmpty() ? null : result.SSID);
                network.put("bssid", result.BSSID);
                network.put("rssi", result.level);
                network.put("frequencyMhz", result.frequency);
                network.put("channel", channel(result.frequency));
                nearby.put(network);
            }
            wifi.put("nearby", nearby);
        }
        return wifi;
    }

    private static int signalPercent(WifiManager manager, int rssi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int max = manager.getMaxSignalLevel();
            return max == 0 ? 0 : Math.round(100f * manager.calculateSignalLevel(rssi) / max);
        }
        @SuppressWarnings("deprecation")
        int level = WifiManager.calculateSignalLevel(rssi, 5);
        return level * 25;
    }

    private static String transport(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        return "other";
    }

    /** Android returns the SSID in quotes, or "&lt;unknown ssid&gt;" without location access. */
    static String cleanSsid(String ssid) {
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) return null;
        return ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
    }

    static int channel(int frequencyMhz) {
        if (frequencyMhz == 2484) return 14;
        if (frequencyMhz >= 2412 && frequencyMhz < 2484) return (frequencyMhz - 2407) / 5;
        if (frequencyMhz >= 5955 && frequencyMhz <= 7115) return (frequencyMhz - 5950) / 5;
        if (frequencyMhz >= 5000 && frequencyMhz < 5955) return (frequencyMhz - 5000) / 5;
        return -1;
    }

    private static String wifiStandard(int standard) {
        switch (standard) {
            case 1: return "802.11a/b/g";
            case 4: return "Wi-Fi 4 (802.11n)";
            case 5: return "Wi-Fi 5 (802.11ac)";
            case 6: return "Wi-Fi 6 (802.11ax)";
            case 7: return "WiGig (802.11ad)";
            case 8: return "Wi-Fi 7 (802.11be)";
            default: return null;
        }
    }
}
