package io.github.mrbolz.utilityapp.tuya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Drives TuyaDevice against {@link FakeTuyaDevice} over loopback. */
public class TuyaDeviceTest {
    private static final String ID = "eb1a2b3c4d5e6f708192";
    private static final String KEY = "k3Y#9pQ!2mZx7&Lw";
    private static final String WRONG_KEY = "0000000000000000";

    private static TuyaDevice client(FakeTuyaDevice fake, String key, String version, int timeoutMs) {
        return client(fake.port(), key, version, timeoutMs);
    }

    private static TuyaDevice client(int port, String key, String version, int timeoutMs) {
        return new TuyaDevice(ID, key, "127.0.0.1", port, version, null, timeoutMs, new SecureRandom(), () -> System.currentTimeMillis() / 1000L);
    }

    private static Map<String, Object> on() {
        Map<String, Object> dps = new LinkedHashMap<>();
        dps.put("1", true);
        return dps;
    }

    private static TuyaException.Reason failure(ThrowingRunnable action) {
        return assertThrows(TuyaException.class, action::run).reason();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void switchesOn(String version) throws Exception {
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, version); TuyaDevice device = client(fake, KEY, version, 2000)) {
            fake.noisy = true;
            device.connect();
            Map<String, Object> status = device.status();
            assertEquals(false, status.get("1"));
            assertEquals(2301L, status.get("20"));
            assertEquals(on(), device.setDps(on()));
            assertEquals(true, fake.dps.get("1"));
            assertEquals(true, device.status().get("1"));
            assertEquals(1, fake.connections.get());
            // One connection, sequence numbers counting up from 1 (negotiation included).
            List<Integer> seqnos = new ArrayList<>();
            for (String frame : fake.received) seqnos.add(Integer.parseInt(frame.split(":")[1]));
            for (int i = 0; i < seqnos.size(); i++) assertEquals(i + 1, (int) seqnos.get(i));
        }
    }

    @Test
    public void version31() throws Exception {
        switchesOn("3.1");
    }

    @Test
    public void version33() throws Exception {
        switchesOn("3.3");
    }

    @Test
    public void version34() throws Exception {
        switchesOn("3.4");
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.4"); TuyaDevice device = client(fake, KEY, "3.4", 2000)) {
            device.connect();
            assertEquals(List.of(TuyaDevice.SESS_KEY_NEG_START + ":1", TuyaDevice.SESS_KEY_NEG_FINISH + ":2"), waitFor(fake, 2));
        }
    }

    @Test
    public void version35() throws Exception {
        switchesOn("3.5");
    }

    @Test
    public void device22() throws Exception {
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.3"); TuyaDevice device = client(fake, KEY, "3.3", 2000)) {
            fake.device22 = true;
            assertEquals(false, device.status().get("1"));
            assertEquals(TuyaDevice.CONTROL_NEW + ":2", fake.received.get(1));
            assertEquals(on(), device.setDps(on()));
            assertEquals(true, device.status().get("1"));
        }
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.2"); TuyaDevice device = client(fake, KEY, "3.2", 2000)) {
            assertEquals(false, device.status().get("1"));
            assertEquals(TuyaDevice.CONTROL_NEW + ":1", fake.received.get(0));
        }
    }

    @Test
    public void renegotiatesASessionKeyStartingWithZero() throws Exception {
        for (String version : new String[] {"3.4", "3.5"}) {
            try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, version); TuyaDevice device = client(fake, KEY, version, 2000)) {
                fake.zeroSessionKeyOnce = true;
                assertEquals(on(), device.setDps(on()));
                assertEquals(2, fake.connections.get());
            }
        }
    }

    @Test
    public void reconnectsWhenTheDeviceDroppedTheConnection() throws Exception {
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.4"); TuyaDevice device = client(fake, KEY, "3.4", 2000)) {
            fake.closeAfterAnswer = true;
            device.status();
            assertEquals(on(), device.setDps(on()));
            assertEquals(2, fake.connections.get());
        }
    }

    @Test
    public void wrongKeyIsRejected() throws Exception {
        for (String version : new String[] {"3.3", "3.4", "3.5"}) {
            try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, version); TuyaDevice device = client(fake, WRONG_KEY, version, 2000)) {
                // 3.3 answers with an encrypted rejection, 3.4 and 3.5 drop the connection.
                assertEquals(version, TuyaException.Reason.KEY_REJECTED, failure(() -> device.setDps(on())));
            }
            try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, version); TuyaDevice device = client(fake, WRONG_KEY, version, 2000)) {
                // An answer under another key does not authenticate.
                fake.ignoreBadFrames = true;
                assertEquals(version, TuyaException.Reason.KEY_REJECTED, failure(device::status));
            }
        }
    }

    @Test
    public void closingAfterNegotiationMeansTheKeyIsRejected() throws Exception {
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.5"); TuyaDevice device = client(fake, KEY, "3.5", 2000)) {
            fake.closeAfterNegotiation = true;
            assertEquals(TuyaException.Reason.KEY_REJECTED, failure(() -> device.setDps(on())));
        }
    }

    @Test
    public void headerOnlyAcknowledgementHasNoDps() throws Exception {
        TuyaDevice device = client(1, KEY, "3.4", 100);
        byte[] session = new byte[16];
        device.useKey(session);
        byte[] header = Arrays.copyOf("3.4".getBytes(StandardCharsets.US_ASCII), 15);
        assertEquals(new LinkedHashMap<String, Object>(), device.decodePayload(TuyaCrypto.ecbEncrypt(session, header, true)));
    }

    @Test
    public void silenceTimesOut() throws Exception {
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.3"); TuyaDevice device = client(fake, KEY, "3.3", 300)) {
            fake.silent = true;
            assertEquals(TuyaException.Reason.TIMEOUT, failure(device::status));
        }
        try (FakeTuyaDevice fake = new FakeTuyaDevice(ID, KEY, "3.4"); TuyaDevice device = client(fake, KEY, "3.4", 300)) {
            fake.silent = true;
            assertEquals(TuyaException.Reason.TIMEOUT, failure(device::connect));
        }
    }

    @Test
    public void refusedConnectionMeansBusy() throws Exception {
        int port;
        try (ServerSocket temporary = new ServerSocket(0)) {
            port = temporary.getLocalPort();
        }
        TuyaDevice device = client(port, KEY, "3.3", 1000);
        assertEquals(TuyaException.Reason.BUSY, failure(device::connect));
    }

    @Test
    public void explainsConnectErrors() {
        assertEquals(TuyaException.Reason.BUSY, TuyaDevice.connectError("192.168.1.20", new ConnectException("Connection refused")).reason());
        assertEquals(TuyaException.Reason.BUSY, TuyaDevice.connectError("192.168.1.20",
            new ConnectException("failed to connect to /192.168.1.20 (port 6668) after 5000ms: isConnected failed: ECONNREFUSED (Connection refused)")).reason());
        assertEquals(TuyaException.Reason.BUSY, TuyaDevice.connectError("192.168.1.20", new SocketException("Connection reset")).reason());
        assertEquals(TuyaException.Reason.UNREACHABLE, TuyaDevice.connectError("192.168.1.20", new NoRouteToHostException("No route to host")).reason());
        assertEquals(TuyaException.Reason.UNREACHABLE, TuyaDevice.connectError("192.168.1.20",
            new ConnectException("failed to connect to /192.168.1.20 (port 6668) after 5000ms: isConnected failed: EHOSTUNREACH (No route to host)")).reason());
        assertEquals(TuyaException.Reason.UNREACHABLE, TuyaDevice.connectError("192.168.1.20", new SocketTimeoutException("connect timed out")).reason());
        assertEquals(TuyaException.Reason.UNREACHABLE, TuyaDevice.connectError("192.168.1.20", new IOException("Host is down")).reason());
    }

    @Test
    public void validatesSettings() {
        assertEquals(TuyaException.Reason.UNSUPPORTED_VERSION, failure(client(1, KEY, "3.6", 100)::connect));
        assertEquals(TuyaException.Reason.UNSUPPORTED_VERSION, failure(client(1, KEY, null, 100)::connect));
        assertEquals(TuyaException.Reason.KEY_REJECTED, failure(client(1, "short", "3.3", 100)::connect));
        assertEquals("3.3", TuyaDevice.normalizeVersion("3.3"));
        assertEquals("3.4", TuyaDevice.normalizeVersion("v3.4"));
        assertEquals("3.5", TuyaDevice.normalizeVersion("3.50"));
        assertEquals(null, TuyaDevice.normalizeVersion("3.35"));
    }

    /** The first frames the fake received once it has {@code count} of them. */
    private static List<String> waitFor(FakeTuyaDevice fake, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (fake.received.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
        return new ArrayList<>(fake.received.subList(0, count));
    }
}
