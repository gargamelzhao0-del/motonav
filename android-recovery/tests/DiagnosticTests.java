import com.motonav.companion.DiagnosticEndpoint;
import com.motonav.companion.DiagnosticRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DiagnosticTests {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static void reject(Runnable action) {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted unsafe value");
    }
    public static void main(String[] args) throws Exception {
        String session = "0123456789abcdef0123456789abcdef", token = "a".repeat(43);
        String link = "https://test-diagnostic.trycloudflare.com/#" + token;
        DiagnosticEndpoint parsed = DiagnosticEndpoint.parse(link);
        check(parsed.uploadUrl.equals("https://test-diagnostic.trycloudflare.com/v1/events"));
        check(parsed.token.equals(token));
        for (String bad : new String[]{"http://test.trycloudflare.com/#" + token,
                "https://test.trycloudflare.com.evil.test/#" + token, "https://localhost/#" + token,
                "https://127.0.0.1/#" + token, "https://test.trycloudflare.com/path#" + token,
                "https://test.trycloudflare.com/?token=secret#" + token,
                "https://user@test.trycloudflare.com/#" + token, link + "x", link.replace("#", "#%20"),
                "https://test.trycloudflare.com:443/#" + token}) reject(() -> DiagnosticEndpoint.parse(bad));
        for (String forbidden : new String[]{"latitude", "longitude", "key", "plate", "address", "destination_name", "token", "message"})
            reject(() -> DiagnosticRecord.line(session, 1, 0, "user_mark", forbidden, "secret"));
        reject(() -> DiagnosticRecord.line(session, 1, 0, "unknown_event"));
        reject(() -> DiagnosticRecord.line(session, 1, 0, "user_mark", "reason", "x", "reason", "y"));
        check(DiagnosticRecord.age(20_000_000_000L, 5_000_000_000L) == 15000);
        check(DiagnosticRecord.age(20, 21) == -1);
        check(!DiagnosticRecord.line(session, 1, 0, "user_mark", "reason", "quote\"\nback\\slash").contains("\\n"));
        check(DiagnosticRecord.line(session, 1, 0, "location_callback", "accuracy_m", Float.NaN).contains("-1.0"));
        List<String> records = new ArrayList<>(); long seq = 0;
        for (String event : DiagnosticRecord.EVENTS) {
            List<Object> fields = new ArrayList<>();
            fields.add("owner"); fields.add("map"); fields.add("reason"); fields.add("test_fixture");
            records.add(DiagnosticRecord.line(session, ++seq, seq * 10, event, fields.toArray()));
        }
        for (String field : DiagnosticRecord.NUMBERS)
            records.add(DiagnosticRecord.line(session, ++seq, seq * 10, "location_callback", field, 12.5));
        for (String field : DiagnosticRecord.FLAGS)
            records.add(DiagnosticRecord.line(session, ++seq, seq * 10, "environment", field, true));
        Files.write(Path.of(args[0]), records);
        System.out.println("PASS: " + checks + " diagnostic privacy/endpoint checks; " + records.size() + " Java wire fixtures");
    }
}
