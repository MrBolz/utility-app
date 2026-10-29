package io.github.mrbolz.utilityapp.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class HttpAndSsdpTest {
    @Test
    public void parsesResponsesAndTitles() throws IOException {
        HttpGet.Response response = HttpGet.parse("HTTP/1.1 200 OK\r\nServer: lighttpd\r\nX-A: 1\r\n\r\n<html><TITLE> FRITZ!Box &amp; Co\n</TITLE>");
        assertEquals(200, response.status);
        assertEquals("lighttpd", response.headers.get("server"));
        assertEquals("FRITZ!Box & Co", HttpGet.title(response.body));
        assertNull(HttpGet.title("<html></html>"));
    }

    @Test
    public void readsUpnpDescriptions() {
        String xml = "<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Living Room TV</friendlyName><manufacturer>Samsung Electronics</manufacturer>"
                + "<modelName>QE55Q80</modelName><deviceList><device><friendlyName>Sub</friendlyName></device></deviceList></device></root>";
        Ssdp.Info info = new Ssdp.Info();
        Ssdp.describe(info, xml);
        assertEquals("Living Room TV", info.friendlyName);
        assertEquals("Samsung Electronics", info.manufacturer);
        assertEquals("QE55Q80", info.modelName);
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", info.deviceType);
        assertNull(info.modelNumber);
    }

    @Test
    public void parsesSearchResponses() {
        Map<String, String> headers = Ssdp.parseResponse("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.1:49000/desc.xml\r\nSERVER: Linux UPnP/1.0\r\nST: upnp:rootdevice\r\n\r\n");
        assertEquals("http://192.168.1.1:49000/desc.xml", headers.get("location"));
        assertEquals("upnp:rootdevice", headers.get("st"));
        assertNull(Ssdp.parseResponse("NOTIFY * HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void fetchesOverARawSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buffer = new byte[1024];
                    in.read(buffer);
                    OutputStream out = client.getOutputStream();
                    out.write("HTTP/1.0 200 OK\r\nServer: test\r\n\r\n<title>Hi</title>".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // Test fails on the client side.
                }
            });
            thread.start();
            HttpGet.Response response = HttpGet.get(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/"), 2000, 4096);
            thread.join();
            assertEquals(200, response.status);
            assertEquals("Hi", HttpGet.title(response.body));
        }
    }
}
