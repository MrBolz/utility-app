package io.github.mrbolz.utilityapp.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class MdnsBrowserTest {
    private static Dns.Record record(String name, int type) {
        return new Dns.Record(name, type);
    }

    @Test
    public void assemblesServicesFromSeparateAnswers() {
        MdnsBrowser.State state = new MdnsBrowser.State();
        Dns.Record ptr = record("_ipp._tcp.local", Dns.TYPE_PTR);
        ptr.target = "Office Printer._ipp._tcp.local";
        Dns.Record srv = record("Office Printer._ipp._tcp.local", Dns.TYPE_SRV);
        srv.target = "printer.local";
        srv.port = 631;
        Dns.Record txt = record("Office Printer._ipp._tcp.local", Dns.TYPE_TXT);
        txt.txt.put("ty", "Brother HL-L2350DW");
        Dns.Record a = record("printer.local", Dns.TYPE_A);
        a.address = "192.168.1.30";

        state.add(ptr);
        assertTrue(hasQuestion(state.nextQuestions(), "Office Printer._ipp._tcp.local", Dns.TYPE_ANY));
        state.add(srv);
        assertTrue(hasQuestion(state.nextQuestions(), "printer.local", Dns.TYPE_A));
        state.add(txt);
        state.add(a);

        MdnsBrowser.Result result = state.result();
        MdnsBrowser.Service service = result.servicesByIp.get("192.168.1.30").get(0);
        assertEquals("Office Printer", service.name);
        assertEquals("_ipp._tcp", service.type);
        assertEquals(631, service.port);
        assertEquals("Brother HL-L2350DW", service.txt.get("ty"));
        assertEquals("printer.local", result.hostnamesByIp.get("192.168.1.30"));
    }

    @Test
    public void learnsServiceTypesAndAsksEachQuestionOnce() {
        MdnsBrowser.State state = new MdnsBrowser.State();
        Dns.Record types = record("_services._dns-sd._udp.local", Dns.TYPE_PTR);
        types.target = "_custom._tcp.local";
        state.add(types);
        assertTrue(hasQuestion(state.nextQuestions(), "_custom._tcp.local", Dns.TYPE_PTR));
        assertTrue(state.nextQuestions().isEmpty());
    }

    @Test
    public void readsReverseLookups() {
        MdnsBrowser.State state = new MdnsBrowser.State();
        Dns.Record ptr = record("42.1.168.192.in-addr.arpa", Dns.TYPE_PTR);
        ptr.target = "Johns-MacBook.local";
        state.add(ptr);
        assertEquals("Johns-MacBook.local", state.result().hostnamesByIp.get("192.168.1.42"));
    }

    private static boolean hasQuestion(List<Dns.Question> questions, String name, int type) {
        return questions.stream().anyMatch(q -> q.name.equalsIgnoreCase(name) && q.type == type);
    }
}
