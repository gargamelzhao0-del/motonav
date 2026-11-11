import com.motonav.companion.NavigationFrame;
import com.motonav.companion.NavigationProtocol;
import com.motonav.companion.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class NavigationProtocolTests {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    private static void checkWire(Protocol.Command command) {
        String wire = command.wire();
        check(wire.length() <= 95 && wire.endsWith("\n"));
        String body = wire.substring(0, wire.lastIndexOf(','));
        CRC32 crc = new CRC32(); crc.update(body.getBytes(StandardCharsets.US_ASCII));
        check(Long.parseLong(wire.substring(wire.lastIndexOf(',') + 1).trim(), 16) == crc.getValue());
        StringBuilder assembled = new StringBuilder();
        for (byte[] chunk : Protocol.chunks(wire)) {
            check(chunk.length <= 20);
            assembled.append(new String(chunk, StandardCharsets.US_ASCII));
        }
        check(assembled.toString().equals(wire));
    }
    public static void main(String[] args) throws Exception {
        NavigationProtocol protocol = new NavigationProtocol(123);
        NavigationFrame live = new NavigationFrame(NavigationFrame.LIVE, 3, 250, 12000, 1000, 50, 1000, 1100);
        check(live.liveAt(2000));
        check(live.at(16001).mode() == NavigationFrame.WAITING);
        check(live.at(999).mode() == NavigationFrame.WAITING);
        check(new NavigationFrame(2, 3, -1, 12000, 1000, 50, 1000, 1100).at(2000).mode() == 1);
        check(new NavigationFrame(2, 3, 250, 12000, 1000, 401, 1000, 1100).at(2000).mode() == 1);
        List<Protocol.Command> frames = List.of(protocol.hello(), protocol.snapshot(live, 2000),
                protocol.snapshot(live, 16001), protocol.snapshot(NavigationFrame.status(NavigationFrame.ARRIVED), 17000));
        for (Protocol.Command frame : frames) checkWire(frame);
        check(frames.get(1).wire().contains(",2,3,250,12000,1000,50,4000,3900,"));
        check(frames.get(2).wire().contains(",1,0,-1,-1,-1,-1,0,0,"));
        // Repeated transmission cannot refresh the same source data, including the transport reserve.
        check(protocol.snapshot(live, 13001).wire().contains(",1,0,-1,-1,-1,-1,0,0,"));
        for (int icon = 0; icon <= 255; icon++) {
            NavigationFrame largest = new NavigationFrame(2, icon, 50000000, 50000000, 31536000, 400, 1, 1);
            checkWire(new NavigationProtocol(Integer.MAX_VALUE).snapshot(largest, 100));
        }
        protocol.reset(); check(protocol.hello().sequence() == 1);
        if (args.length > 0) {
            StringBuilder fixture = new StringBuilder();
            for (Protocol.Command frame : frames) fixture.append(frame.wire());
            Files.writeString(Path.of(args[0]), fixture, StandardCharsets.US_ASCII);
        }
        System.out.println("NavigationProtocol: " + checks + " checks passed");
    }
}
