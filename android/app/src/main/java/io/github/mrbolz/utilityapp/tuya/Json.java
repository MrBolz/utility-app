package io.github.mrbolz.utilityapp.tuya;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. The Tuya code is plain Java so its tests run on any JVM, where
 * Android's org.json is not available.
 *
 * <p>Objects become {@code LinkedHashMap<String, Object>}, arrays {@code ArrayList<Object>}, numbers
 * {@code Long} when integral and {@code Double} otherwise. {@link #stringify} writes compact JSON
 * without spaces, which Tuya devices expect.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.position != text.length()) throw parser.error("Unexpected trailing data");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new IllegalArgumentException("Not a JSON number: " + number);
            if (number == Math.rint(number) && Math.abs(number) < 1e15) out.append((long) number);
            else out.append(number);
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> entries = ((Map<?, ?>) value).entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
                if (entries.hasNext()) out.append(',');
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            Iterator<?> items = ((Iterable<?>) value).iterator();
            while (items.hasNext()) {
                write(out, items.next());
                if (items.hasNext()) out.append(',');
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    private static final class Parser {
        final String text;
        int position;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            if (position >= text.length()) throw error("Unexpected end of input");
            char c = text.charAt(position);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Unexpected character '" + c + "'");
            }
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw error("Expected a key");
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (position + 4 > text.length()) throw error("Bad unicode escape");
                        try {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Bad unicode escape");
                        }
                        position += 4;
                        break;
                    default:
                        throw error("Bad escape '\\" + escape + "'");
                }
            }
        }

        Object number() {
            int start = position;
            if (peek() == '-') position++;
            boolean fraction = false;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = true;
                    position++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, position);
            try {
                if (!fraction) {
                    try {
                        return Long.parseLong(number);
                    } catch (NumberFormatException tooLarge) {
                        return Double.parseDouble(number);
                    }
                }
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                throw error("Bad number '" + number + "'");
            }
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) throw error("Unexpected token");
            position += word.length();
            return value;
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        char peek() {
            if (position >= text.length()) throw error("Unexpected end of input");
            return text.charAt(position);
        }

        char next() {
            char c = peek();
            position++;
            return c;
        }

        void expect(char c) {
            if (next() != c) throw error("Expected '" + c + "'");
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
