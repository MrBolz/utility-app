package io.github.mrbolz.utilityapp.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class Ipv4Test {
    @Test
    public void parsesAndFormats() {
        assertEquals("192.168.1.42", Ipv4.format(Ipv4.parse("192.168.1.42")));
        assertEquals("255.255.255.255", Ipv4.format(Ipv4.parse("255.255.255.255")));
        assertFalse(Ipv4.isIpv4("fe80::1"));
        assertFalse(Ipv4.isIpv4("1.2.3.256"));
    }

    @Test
    public void scansTheWholeSlash24() {
        List<String> hosts = Ipv4.scanRange("192.168.1.42", 24);
        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
        assertEquals("192.168.1.254", hosts.get(253));
    }

    @Test
    public void limitsLargeNetworksToTheSurroundingSlash24() {
        List<String> hosts = Ipv4.scanRange("10.20.30.40", 16);
        assertEquals(254, hosts.size());
        assertEquals("10.20.30.1", hosts.get(0));
    }

    @Test
    public void handlesSmallNetworks() {
        assertEquals(List.of("192.168.1.41", "192.168.1.42"), Ipv4.scanRange("192.168.1.42", 30));
        assertTrue(Ipv4.scanRange("192.168.1.42", 32).isEmpty());
    }

    @Test
    public void sortsNumerically() {
        assertTrue(Ipv4.sortKey("10.0.0.9") < Ipv4.sortKey("10.0.0.10"));
        assertTrue(Ipv4.sortKey("200.0.0.1") > Ipv4.sortKey("10.0.0.1"));
        assertEquals("4.3.2.1.in-addr.arpa", Ipv4.reverseName("1.2.3.4"));
    }
}
