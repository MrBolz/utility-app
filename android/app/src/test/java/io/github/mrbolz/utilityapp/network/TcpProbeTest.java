package io.github.mrbolz.utilityapp.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class TcpProbeTest {
    @Test
    public void tellsOpenFromClosedPorts() throws Exception {
        int closed;
        try (ServerSocket temporary = new ServerSocket(0)) {
            closed = temporary.getLocalPort();
        }
        try (ServerSocket open = new ServerSocket(0)) {
            TcpProbe.Result result = TcpProbe.probe(InetAddress.getLoopbackAddress(), new int[] {open.getLocalPort(), closed}, 1000);
            assertTrue(result.alive);
            assertEquals(Set.of(open.getLocalPort()), result.open);
            assertTrue(result.latencyMs >= 0);
        }
    }

    @Test
    public void findsLoopbackInAScan() throws Exception {
        LanScanner scanner = new LanScanner("127.0.0.2", null, List.of("127.0.0.1"), new LanScanner.Listener() {
            @Override
            public void onProgress(String phase, int done, int total) {}

            @Override
            public void onDevice(Device device) {}
        });
        List<Device> devices = scanner.run();
        assertTrue(devices.stream().anyMatch(d -> d.ip.equals("127.0.0.1") && !d.sources.isEmpty()));
        assertTrue(devices.stream().anyMatch(d -> d.ip.equals("127.0.0.2") && d.self));
    }
}
