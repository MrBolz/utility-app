package io.github.mrbolz.utilityapp.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal DNS message encoding and decoding, enough for mDNS service discovery. */
final class Dns {
    static final int TYPE_A = 1;
    static final int TYPE_PTR = 12;
    static final int TYPE_TXT = 16;
    static final int TYPE_SRV = 33;
    static final int TYPE_ANY = 255;
    private static final int CLASS_IN = 1;

    private Dns() {}

    static final class Question {
        final String name;
        final int type;

        Question(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    /** A resource record. Only the fields that belong to its type are set. */
    static final class Record {
        final String name;
        final int type;
        /** PTR: the name pointed to. SRV: the target host. */
        String target;
        /** SRV: the port. */
        int port;
        /** A: the IPv4 address. */
        String address;
        /** TXT: key/value pairs. */
        final Map<String, String> txt = new LinkedHashMap<>();

        Record(String name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    static byte[] query(int id, List<Question> questions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, id);
        writeShort(out, 0); // standard query
        writeShort(out, questions.size());
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        for (Question question : questions) {
            for (String label : trimDot(question.name).split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
            out.write(0);
            writeShort(out, question.type);
            writeShort(out, CLASS_IN);
        }
        return out.toByteArray();
    }

    /** All answer, authority and additional records of a response. Throws on malformed input. */
    static List<Record> parse(byte[] data, int length) {
        Reader reader = new Reader(data, length);
        reader.position = 4;
        int questions = reader.u16();
        int records = reader.u16() + reader.u16() + reader.u16();
        for (int i = 0; i < questions; i++) {
            reader.name();
            reader.position += 4;
        }
        List<Record> result = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            Record record = new Record(reader.name(), reader.u16());
            reader.position += 2 + 4; // class, ttl
            int size = reader.u16();
            int end = reader.position + size;
            if (end > length) throw new IllegalArgumentException("Truncated record");
            switch (record.type) {
                case TYPE_A:
                    if (size == 4) {
                        record.address = (data[reader.position] & 0xff) + "." + (data[reader.position + 1] & 0xff) + "."
                                + (data[reader.position + 2] & 0xff) + "." + (data[reader.position + 3] & 0xff);
                    }
                    break;
                case TYPE_PTR:
                    record.target = reader.name();
                    break;
                case TYPE_SRV:
                    reader.position += 4; // priority, weight
                    record.port = reader.u16();
                    record.target = reader.name();
                    break;
                case TYPE_TXT:
                    while (reader.position < end) {
                        int entryLength = data[reader.position++] & 0xff;
                        String entry = new String(data, reader.position, Math.min(entryLength, end - reader.position), StandardCharsets.UTF_8);
                        reader.position += entryLength;
                        int equals = entry.indexOf('=');
                        if (!entry.isEmpty()) {
                            record.txt.put(equals < 0 ? entry : entry.substring(0, equals), equals < 0 ? "" : entry.substring(equals + 1));
                        }
                    }
                    break;
                default:
                    break;
            }
            reader.position = end;
            result.add(record);
        }
        return result;
    }

    static String trimDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static final class Reader {
        final byte[] data;
        final int length;
        int position;

        Reader(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        int u16() {
            if (position + 2 > length) throw new IllegalArgumentException("Truncated message");
            int value = ((data[position] & 0xff) << 8) | (data[position + 1] & 0xff);
            position += 2;
            return value;
        }

        /** Reads a possibly compressed name and leaves the position after it. */
        String name() {
            StringBuilder name = new StringBuilder();
            int cursor = position;
            int jumps = 0;
            boolean jumped = false;
            while (true) {
                if (cursor >= length) throw new IllegalArgumentException("Truncated name");
                int labelLength = data[cursor] & 0xff;
                if ((labelLength & 0xc0) == 0xc0) {
                    if (cursor + 1 >= length || ++jumps > 32) throw new IllegalArgumentException("Bad name pointer");
                    if (!jumped) position = cursor + 2;
                    jumped = true;
                    cursor = ((labelLength & 0x3f) << 8) | (data[cursor + 1] & 0xff);
                    continue;
                }
                if (labelLength == 0) {
                    if (!jumped) position = cursor + 1;
                    return name.toString();
                }
                if (cursor + 1 + labelLength > length) throw new IllegalArgumentException("Truncated label");
                if (name.length() > 0) name.append('.');
                name.append(new String(data, cursor + 1, labelLength, StandardCharsets.UTF_8));
                cursor += 1 + labelLength;
            }
        }
    }
}
