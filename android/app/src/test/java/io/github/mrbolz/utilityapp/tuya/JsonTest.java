package io.github.mrbolz.utilityapp.tuya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class JsonTest {
    @Test
    public void parsesNestedValues() {
        Map<String, Object> value = Json.parseObject("{ \"a\": [1, 2.5, -3e2, true, null], \"b\": {\"c\": \"x\\\"y\\u00e9\"} }");
        List<?> a = (List<?>) value.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(-300.0, a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertNull(a.get(4));
        assertEquals("x\"yé", ((Map<?, ?>) value.get("b")).get("c"));
    }

    @Test
    public void writesCompactJson() {
        Map<String, Object> dps = new LinkedHashMap<>();
        dps.put("1", true);
        dps.put("2", 25L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("devId", "abc");
        payload.put("dps", dps);
        payload.put("list", Arrays.asList("a", null, 1.5));
        assertEquals("{\"devId\":\"abc\",\"dps\":{\"1\":true,\"2\":25},\"list\":[\"a\",null,1.5]}", Json.stringify(payload));
        assertEquals("\"tab\\tnew\\nline\\u0001\"", Json.stringify("tab\tnew\nline\u0001"));
    }

    @Test
    public void roundTrips() {
        String text = "{\"result\":{\"devices\":[{\"id\":\"bf1\",\"local_key\":\"a$b#c=d:e!f\",\"online\":false}],\"has_more\":true}}";
        assertEquals(text, Json.stringify(Json.parse(text)));
    }

    @Test
    public void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
    }
}
