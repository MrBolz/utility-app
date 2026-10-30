package io.github.mrbolz.utilityapp.tuya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class TuyaCloudTest {
    // Fixed inputs. The expected signatures were computed by tinytuya 1.20.0 for the same inputs:
    // Cloud(apiRegion="eu", ...) and Cloud._get_all_devices() with requests.get faked and time.time()
    // patched to 1700000000.5, i.e. t = 1700000000500.
    private static final String ACCESS_ID = "fxkmqxzd5vqgw3ejnhcy";
    private static final String SECRET = "0f4c7e3a9b1d48e6a2c5f7b9d1e3a5c7";
    private static final String TOKEN = "e6c2a1b7f3d94c8a0b5e7f9d1c3a5b70";
    private static final long T = 1700000000500L;
    /** GET /v1.0/token?grant_type=1, signed without a token. */
    private static final String TOKEN_SIGN = "A5111DF0670EEFFF049527936B00FD9CDCF1F56AA7F394E3473C17074344CB26";
    /** GET /v1.0/iot-01/associated-users/devices?size=50 with the token. */
    private static final String PAGE1_SIGN = "91521333989BDF977A67524892B32CB66772C7C8F65E6A0A1FE331566DC4F35A";
    /** The same with last_row_key=row+key/2== (signed raw, sent URL-encoded and sorted before size). */
    private static final String PAGE2_SIGN = "DDF8F62F4BF3E45083D956F4422E9BFC2F2A9C865654B55484AD86E5045A8C25";

    private static final String TOKEN_OK = "{\"result\":{\"access_token\":\"" + TOKEN + "\",\"expire_time\":7200,"
            + "\"refresh_token\":\"r\",\"uid\":\"u\"},\"success\":true,\"t\":1700000000600}";
    private static final String DEVICES_BASE = "https://openapi.tuyaeu.com/v1.0/iot-01/associated-users/devices";

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Records the requests and answers them with the queued bodies, in order. */
    private static final class FakeHttp implements TuyaCloud.Http {
        final List<Request> requests = new ArrayList<>();
        final Deque<String> answers = new ArrayDeque<>();

        FakeHttp answer(String... bodies) {
            for (String body : bodies) answers.add(body);
            return this;
        }

        @Override
        public TuyaCloud.Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            requests.add(new Request(method, url, headers, body));
            if (answers.isEmpty()) throw new IOException("Unexpected request " + url);
            return new TuyaCloud.Response(200, answers.remove());
        }
    }

    private static TuyaCloud cloud(FakeHttp http) {
        return new TuyaCloud(TuyaCloud.Region.CENTRAL_EUROPE, ACCESS_ID, SECRET, http, () -> T);
    }

    private static String page(String devices, boolean hasMore, String lastRowKey) {
        return "{\"result\":{\"devices\":[" + devices + "],\"has_more\":" + hasMore
                + (lastRowKey == null ? "" : ",\"last_row_key\":\"" + lastRowKey + "\"") + "},\"success\":true,\"t\":1700000000700}";
    }

    @Test
    public void signsLikeTinytuya() {
        assertEquals(TOKEN_SIGN, TuyaCloud.sign(ACCESS_ID, SECRET, null, T, "GET", "/v1.0/token?grant_type=1", ""));
        assertEquals(PAGE1_SIGN, TuyaCloud.sign(ACCESS_ID, SECRET, TOKEN, T, "GET", "/v1.0/iot-01/associated-users/devices?size=50", ""));
        assertEquals(PAGE2_SIGN, TuyaCloud.sign(ACCESS_ID, SECRET, TOKEN, T, "GET",
                "/v1.0/iot-01/associated-users/devices?last_row_key=row+key/2==&size=50", ""));
    }

    @Test
    public void listsDevicesAcrossPages() throws Exception {
        FakeHttp http = new FakeHttp().answer(TOKEN_OK,
                page("{\"id\":\"bf01\",\"name\":\"Bedside\",\"local_key\":\"k1'a$b\",\"category\":\"cz\",\"product_name\":\"Smart Plug\","
                        + "\"product_id\":\"p1\",\"ip\":\"203.0.113.7\",\"online\":true,\"uid\":\"u\"}", true, "row+key/2=="),
                page("{\"id\":\"bf02\",\"local_key\":\"k2\",\"online\":false},{\"id\":\"bf01\",\"name\":\"Duplicate\"},{\"name\":\"no id\"}", false, null));
        List<TuyaCloud.CloudDevice> devices = cloud(http).listDevices();

        assertEquals(3, http.requests.size());
        Request token = http.requests.get(0);
        assertEquals("GET", token.method);
        assertEquals("https://openapi.tuyaeu.com/v1.0/token?grant_type=1", token.url);
        assertNull(token.body);
        assertEquals(ACCESS_ID, token.headers.get("client_id"));
        assertEquals(TOKEN_SIGN, token.headers.get("sign"));
        assertEquals("1700000000500", token.headers.get("t"));
        assertEquals("HMAC-SHA256", token.headers.get("sign_method"));
        assertEquals(SECRET, token.headers.get("secret"));
        assertFalse(token.headers.containsKey("access_token"));

        Request first = http.requests.get(1);
        assertEquals(DEVICES_BASE + "?size=50", first.url);
        assertEquals(PAGE1_SIGN, first.headers.get("sign"));
        assertEquals(TOKEN, first.headers.get("access_token"));
        assertFalse(first.headers.containsKey("secret"));
        Request second = http.requests.get(2);
        assertEquals(DEVICES_BASE + "?last_row_key=row%2Bkey%2F2%3D%3D&size=50", second.url);
        assertEquals(PAGE2_SIGN, second.headers.get("sign"));

        assertEquals(2, devices.size());
        TuyaCloud.CloudDevice plug = devices.get(0);
        assertEquals("bf01", plug.id);
        assertEquals("Bedside", plug.name);
        assertEquals("k1'a$b", plug.localKey);
        assertEquals("cz", plug.category);
        assertEquals("Smart Plug", plug.productName);
        assertEquals("p1", plug.productId);
        assertEquals("203.0.113.7", plug.ip);
        assertTrue(plug.online);
        TuyaCloud.CloudDevice other = devices.get(1);
        assertEquals("bf02", other.id);
        assertEquals("bf02", other.name);
        assertEquals("k2", other.localKey);
        assertNull(other.category);
        assertNull(other.ip);
        assertFalse(other.online);
    }

    @Test
    public void reusesTheTokenUntilItExpires() throws Exception {
        AtomicLong now = new AtomicLong(T);
        FakeHttp http = new FakeHttp().answer(TOKEN_OK, page("", false, null), page("", false, null), TOKEN_OK, page("", false, null));
        TuyaCloud cloud = new TuyaCloud(TuyaCloud.Region.CENTRAL_EUROPE, ACCESS_ID, SECRET, http, now::get);
        cloud.listDevices();
        now.addAndGet(7000 * 1000L);
        cloud.listDevices();
        now.addAndGet(200 * 1000L);
        cloud.listDevices();
        assertEquals(5, http.requests.size());
        assertTrue(http.requests.get(3).url.contains("/v1.0/token?"));
    }

    @Test
    public void renewsAnInvalidTokenOnce() throws Exception {
        String invalid = "{\"code\":1010,\"msg\":\"token invalid\",\"success\":false,\"t\":1700000000700}";
        String newToken = TOKEN_OK.replace(TOKEN, "fresh");
        FakeHttp http = new FakeHttp().answer(TOKEN_OK, invalid, newToken, page("{\"id\":\"bf01\"}", false, null));
        List<TuyaCloud.CloudDevice> devices = cloud(http).listDevices();
        assertEquals(1, devices.size());
        assertEquals(4, http.requests.size());
        assertEquals(TOKEN, http.requests.get(1).headers.get("access_token"));
        assertFalse(http.requests.get(2).headers.containsKey("access_token"));
        assertEquals("fresh", http.requests.get(3).headers.get("access_token"));
        assertEquals(DEVICES_BASE + "?size=50", http.requests.get(3).url);

        FakeHttp stillInvalid = new FakeHttp().answer(TOKEN_OK, invalid, newToken, invalid);
        TuyaCloud.TuyaCloudException error = assertThrows(TuyaCloud.TuyaCloudException.class, () -> cloud(stillInvalid).listDevices());
        assertEquals("1010", error.code);
        assertEquals(4, stillInvalid.requests.size());
    }

    @Test
    public void explainsCommonErrors() {
        TuyaCloud.TuyaCloudException sign = failure("{\"code\":1004,\"msg\":\"sign invalid\",\"success\":false}");
        assertEquals("1004", sign.code);
        assertEquals("Wrong Access Secret, or the phone's clock is off (Tuya error 1004: sign invalid)", sign.getMessage());

        TuyaCloud.TuyaCloudException permission = failure("{\"code\":1106,\"msg\":\"permission deny\",\"success\":false}");
        assertEquals("1106", permission.code);
        assertTrue(permission.getMessage(), permission.getMessage().contains("Link Tuya App Account"));

        TuyaCloud.TuyaCloudException expired = failure(
                "{\"code\":28841105,\"msg\":\"No permissions. This project is not subscribed to this API.\",\"success\":false}");
        assertEquals("28841105", expired.code);
        assertTrue(expired.getMessage(), expired.getMessage().startsWith("The IoT Core service of the cloud project is not subscribed or its free trial expired"));
        assertTrue(expired.getMessage(), expired.getMessage().endsWith("(Tuya error 28841105: No permissions. This project is not subscribed to this API.)"));

        TuyaCloud.TuyaCloudException other = failure("{\"code\":2017,\"msg\":\"schema does not exist\",\"success\":false}");
        assertEquals("The Tuya cloud refused the request (Tuya error 2017: schema does not exist)", other.getMessage());
    }

    @Test
    public void reportsErrorsOfTheDeviceListAndBadAnswers() throws Exception {
        FakeHttp http = new FakeHttp().answer(TOKEN_OK, "{\"code\":1106,\"msg\":\"permission deny\",\"success\":false}");
        TuyaCloud.TuyaCloudException error = assertThrows(TuyaCloud.TuyaCloudException.class, () -> cloud(http).listDevices());
        assertEquals("1106", error.code);
        assertEquals(2, http.requests.size());

        FakeHttp html = new FakeHttp().answer("<html>Bad Gateway</html>");
        IOException bad = assertThrows(IOException.class, () -> cloud(html).listDevices());
        assertTrue(bad.getMessage(), bad.getMessage().contains("Unexpected answer from the Tuya cloud"));
    }

    @Test
    public void findsRegionsById() {
        assertEquals(TuyaCloud.Region.CENTRAL_EUROPE, TuyaCloud.Region.fromId("central-europe"));
        assertEquals("openapi-sg.iotbing.com", TuyaCloud.Region.fromId("singapore").host);
        assertThrows(IllegalArgumentException.class, () -> TuyaCloud.Region.fromId("eu"));
        assertThrows(IllegalArgumentException.class, () -> new TuyaCloud(TuyaCloud.Region.CHINA, ACCESS_ID, " "));
    }

    /** The error of a token request answered with {@code body}. */
    private static TuyaCloud.TuyaCloudException failure(String body) {
        return assertThrows(TuyaCloud.TuyaCloudException.class, () -> cloud(new FakeHttp().answer(body)).listDevices());
    }
}
