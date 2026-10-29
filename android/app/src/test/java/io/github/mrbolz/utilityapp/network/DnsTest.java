package io.github.mrbolz.utilityapp.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class DnsTest {
    /** Builds DNS responses by hand, including compressed names. */
    static final class Message {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Message(int answers) {
            short16(0);
            short16(0x8400);
            short16(0);
            short16(answers);
            short16(0);
            short16(0);
        }

        int position() {
            return out.size();
        }

        Message name(String name) {
            for (String label : name.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
            out.write(0);
            return this;
        }

        /** Labels followed by a pointer to an earlier name. */
        Message name(String prefix, int pointer) {
            for (String label : prefix.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
            return pointer(pointer);
        }

        Message pointer(int offset) {
            out.write(0xc0 | (offset >> 8));
            out.write(offset & 0xff);
            return this;
        }

        Message header(int type, int dataLength) {
            short16(type);
            short16(0x8001); // IN with the cache-flush bit
            short16(0);
            short16(120);
            short16(dataLength);
            return this;
        }

        Message bytes(int... values) {
            for (int value : values) out.write(value);
            return this;
        }

        Message raw(byte[] values) {
            out.write(values, 0, values.length);
            return this;
        }

        Message short16(int value) {
            out.write((value >> 8) & 0xff);
            out.write(value & 0xff);
            return this;
        }

        byte[] build() {
            return out.toByteArray();
        }
    }

    @Test
    public void roundTripsQueries() {
        byte[] query = Dns.query(7, List.of(new Dns.Question("_http._tcp.local", Dns.TYPE_PTR)));
        assertEquals(12 + 18 + 4, query.length);
        assertEquals(1, query[5]); // one question
    }

    @Test
    public void parsesCompressedRecords() {
        Message message = new Message(4);
        int typeOffset = message.position();
        message.name("_googlecast._tcp.local").header(Dns.TYPE_PTR, 1 + 7 + 2);
        int instanceOffset = message.position();
        message.name("Kitchen", typeOffset);
        message.pointer(instanceOffset).header(Dns.TYPE_SRV, 6 + 14).short16(0).short16(0).short16(8009).name("tv-box.local");
        message.pointer(instanceOffset).header(Dns.TYPE_TXT, 9).bytes(8).raw("md=Model".getBytes(StandardCharsets.UTF_8));
        message.name("tv-box.local").header(Dns.TYPE_A, 4).bytes(192, 168, 1, 50);

        List<Dns.Record> records = Dns.parse(message.build(), message.build().length);

        assertEquals(4, records.size());
        assertEquals("_googlecast._tcp.local", records.get(0).name);
        assertEquals("Kitchen._googlecast._tcp.local", records.get(0).target);
        assertEquals("Kitchen._googlecast._tcp.local", records.get(1).name);
        assertEquals(8009, records.get(1).port);
        assertEquals("tv-box.local", records.get(1).target);
        assertEquals("Model", records.get(2).txt.get("md"));
        assertEquals("192.168.1.50", records.get(3).address);
    }

    @Test
    public void rejectsPointerLoops() {
        Message message = new Message(1);
        message.pointer(12);
        byte[] data = message.build();
        assertThrows(IllegalArgumentException.class, () -> Dns.parse(data, data.length));
    }

    @Test
    public void rejectsTruncatedMessages() {
        byte[] data = new Message(1).name("a.local").build();
        assertThrows(IllegalArgumentException.class, () -> Dns.parse(data, data.length));
    }
}
