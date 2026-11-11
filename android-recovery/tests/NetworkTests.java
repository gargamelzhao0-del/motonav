import com.motonav.companion.LocalNetwork;
import com.motonav.companion.NetworkProtocol;
import com.motonav.companion.Protocol;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

/** Pure Java model and wire tests; no Android, network, location SDK or keys. */
public final class NetworkTests {
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(Runnable action, String label) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid input: " + label);
    }

    private static void immutable(Runnable action, String label) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Expected immutable: " + label);
    }

    private static LocalNetwork network(int[][] nodes, int[][] edges, int... route) {
        return new LocalNetwork(123, LocalNetwork.LOCAL_METERS_V1, nodes, edges, route);
    }

    private static LocalNetwork simple() {
        return network(new int[][] {{0, 0}, {3, 4}}, new int[][] {{0, 1}}, 0, 1);
    }

    private static void modelBounds() {
        int[][] nodes = {{0, 0}, {3, 4}};
        int[][] edges = {{0, 1}};
        int[] route = {0, 1};
        LocalNetwork valid = simple();
        check(valid.lengthMm() == 5000, "3-4-5 route length");
        check(valid.mapId() == 123 && valid.coordinateVersion() == 1, "Metadata");
        check(new LocalNetwork(Integer.MAX_VALUE, 1, nodes, edges, route).mapId() == Integer.MAX_VALUE,
                "Largest map ID");
        rejects(() -> new LocalNetwork(0, 1, nodes, edges, route), "Zero map ID");
        rejects(() -> new LocalNetwork(-1, 1, nodes, edges, route), "Negative map ID");
        rejects(() -> new LocalNetwork(1, 0, nodes, edges, route), "Zero coordinate version");
        rejects(() -> new LocalNetwork(1, 2, nodes, edges, route), "Unknown coordinate version");
        rejects(() -> network(new int[][] {{0, 0}}, edges, route), "One node");
        rejects(() -> network(new int[17][2], edges, route), "17 nodes");
        rejects(() -> network(nodes, new int[0][2], route), "No edges");
        rejects(() -> network(nodes, new int[25][2], route), "25 edges");
        rejects(() -> network(nodes, edges, 0), "One route node");
        rejects(() -> network(nodes, edges, new int[17]), "17 route nodes");
        rejects(() -> network(null, edges, route), "Null nodes");
        rejects(() -> network(nodes, null, route), "Null edges");
        rejects(() -> network(nodes, edges, (int[]) null), "Null route");
        rejects(() -> network(new int[][] {null, {1, 1}}, edges, route), "Null node row");
        rejects(() -> network(new int[][] {{0}, {1, 1}}, edges, route), "Short node row");
        rejects(() -> network(new int[][] {{0, 0, 0}, {1, 1}}, edges, route), "Long node row");
        rejects(() -> network(nodes, new int[][] {null}, route), "Null edge row");
        rejects(() -> network(nodes, new int[][] {{0}}, route), "Short edge row");
        rejects(() -> network(nodes, new int[][] {{0, 1, 2}}, route), "Long edge row");
        rejects(() -> new LocalNetwork.Node(-2001, 0), "Low x");
        rejects(() -> new LocalNetwork.Node(2001, 0), "High x");
        rejects(() -> new LocalNetwork.Node(0, -2001), "Low y");
        rejects(() -> new LocalNetwork.Node(0, 2001), "High y");
        rejects(() -> new LocalNetwork.Node(Integer.MIN_VALUE, Integer.MAX_VALUE), "Integer extremes");
        check(network(new int[][] {{-2000, -2000}, {-2000, 0}}, edges, route).lengthMm() == 2_000_000,
                "Coordinate lower bounds and maximum edge");
        check(network(new int[][] {{2000, 2000}, {2000, 0}}, edges, route).lengthMm() == 2_000_000,
                "Coordinate upper bounds");
        check(network(new int[][] {{0, 0}, {1200, 1600}}, edges, route).lengthMm() == 2_000_000,
                "Diagonal maximum edge");
        rejects(() -> network(new int[][] {{0, 0}, {2000, 1}}, edges, route),
                "Edge infinitesimally longer than bound even if rounded mm would match");
        rejects(() -> network(new int[][] {{-2000, 0}, {2000, 0}}, edges, route), "Long edge");
        rejects(() -> network(new int[][] {{0, 0}, {0, 0}}, edges, route), "Duplicate coordinates");
        rejects(() -> network(nodes, new int[][] {{0, 0}}, route), "Self edge");
        rejects(() -> network(nodes, new int[][] {{-1, 1}}, route), "Negative edge endpoint");
        rejects(() -> network(nodes, new int[][] {{0, 2}}, route), "Out of range edge endpoint");
        rejects(() -> network(nodes, new int[][] {{0, 1}, {0, 1}}, route), "Duplicate directed edge");
        rejects(() -> network(nodes, new int[][] {{0, 1}, {1, 0}}, route), "Duplicate undirected edge");
        rejects(() -> network(nodes, edges, -1, 1), "Negative route endpoint");
        rejects(() -> network(nodes, edges, 0, 2), "Out of range route endpoint");
        rejects(() -> network(nodes, edges, 0, 0), "Repeated consecutive route node");
        rejects(() -> network(new int[][] {{0, 0}, {1, 0}, {2, 0}}, edges, 0, 2), "Missing route edge");
        check(network(nodes, edges, 1, 0).lengthMm() == 5000, "Reverse route uses undirected edge");
        check(network(new int[][] {{0, 0}, {1, 1}}, edges, 0, 1, 0).lengthMm() == 2828,
                "Round each segment separately, not total");
        int[][] longNodes = {{0, 0}, {2000, 0}};
        check(network(longNodes, edges, 0, 1, 0, 1, 0, 1).lengthMm() == 10_000_000,
                "Exact 10 km route limit");
        rejects(() -> network(longNodes, edges, 0, 1, 0, 1, 0, 1, 0), "Route above 10 km");
        rejects(() -> new LocalNetwork(1, 1, Arrays.asList(new LocalNetwork.Node(0, 0), null),
                valid.edges(), valid.route()), "Null node element");
        rejects(() -> new LocalNetwork(1, 1, valid.nodes(), Arrays.asList((LocalNetwork.Edge) null),
                valid.route()), "Null edge element");
        rejects(() -> new LocalNetwork(1, 1, valid.nodes(), valid.edges(), Arrays.asList(0, null)),
                "Null route element");
    }

    private static void defensiveCopies() {
        int[][] nodes = {{0, 0}, {3, 4}};
        int[][] edges = {{0, 1}};
        int[] route = {0, 1};
        LocalNetwork arrays = network(nodes, edges, route);
        long crc = NetworkProtocol.checksum(arrays);
        nodes[0][0] = 100;
        nodes[1] = null;
        edges[0][1] = 0;
        route[0] = 1;
        check(arrays.nodes().get(0).x() == 0 && arrays.edges().get(0).b() == 1
                && arrays.route().get(0) == 0, "Arrays defensively copied");
        List<LocalNetwork.Node> nodeList = new ArrayList<>(arrays.nodes());
        List<LocalNetwork.Edge> edgeList = new ArrayList<>(arrays.edges());
        List<Integer> routeList = new ArrayList<>(arrays.route());
        LocalNetwork lists = new LocalNetwork(123, 1, nodeList, edgeList, routeList);
        nodeList.clear(); edgeList.clear(); routeList.clear();
        check(lists.nodes().size() == 2 && lists.edges().size() == 1 && lists.route().size() == 2,
                "Lists defensively copied");
        immutable(() -> lists.nodes().clear(), "nodes");
        immutable(() -> lists.edges().set(0, new LocalNetwork.Edge(1, 0)), "edges");
        immutable(() -> lists.route().add(0), "route");
        double[][] points = lists.points();
        points[0][0] = 999;
        points[1] = null;
        check(lists.points()[0][0] == 0 && lists.points()[1][0] == 3, "points deep copied");
        check(NetworkProtocol.checksum(arrays) == crc && NetworkProtocol.checksum(lists) == crc,
                "Input and result mutations cannot alter checksum");
    }

    private static void demoRoutes() {
        LocalNetwork direct = LocalNetwork.demo(1, false), detour = LocalNetwork.demo(2, true);
        check(direct.nodes().size() == 10 && direct.edges().size() == 10, "Demo graph counts");
        check(direct.nodes().equals(detour.nodes()) && direct.edges().equals(detour.edges()), "Shared graph");
        check(direct.route().equals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7)), "Original eight-point route");
        check(detour.route().equals(Arrays.asList(0, 8, 9, 1, 2, 3, 4, 5, 6, 7)), "Distinct detour");
        check(direct.lengthMm() == 1_800_000 && detour.lengthMm() == 2_040_000, "Demo lengths");
        check(Arrays.equals(direct.points()[0], detour.points()[0]), "Same origin");
        check(Arrays.equals(direct.points()[7], detour.points()[9]), "Same destination");
        check(direct.points().length == 8 && detour.points().length == 10, "Route-only point copy");
        check(!NetworkProtocol.canonicalBody(direct).equals(NetworkProtocol.canonicalBody(detour)),
                "Selected route is part of payload");
    }

    private static void goldenWire() {
        LocalNetwork map = simple();
        NetworkProtocol protocol = new NetworkProtocol();
        check(NetworkProtocol.canonicalBody(map).equals(
                "N,0,0,0\nN,1,3,4\nE,0,0,1\nT,0,0\nT,1,1\n"), "Canonical body golden");
        check(NetworkProtocol.payloadLength(map) == 36, "Golden payload byte length");
        // Independently computed with Python zlib.crc32, IEEE CRC-32.
        check(NetworkProtocol.checksum(map) == 2_119_567_987L, "Golden CRC");
        check(protocol.hello().wire().equals("H,2,1\n"), "Hello v2");
        List<Protocol.Command> commands = protocol.transfer(map);
        String[] expected = {"B,2,2,123,1,2,1,2,36\n", "N,2,3,0,0,0\n", "N,2,4,1,3,4\n",
                "E,2,5,0,0,1\n", "T,2,6,0,0\n", "T,2,7,1,1\n", "C,2,8,2119567987\n"};
        check(commands.size() == expected.length, "Transfer command count");
        for (int i = 0; i < expected.length; i++) {
            check(commands.get(i).wire().equals(expected[i]), "Golden command " + i);
            check(commands.get(i).sequence() == i + 2, "Contiguous command sequence");
        }
        immutable(() -> commands.clear(), "Transfer list");
        check(protocol.restart(123).wire().equals("R,2,9,123\n"), "Restart committed map");
        check(protocol.position(map, 5000, 60000, true).wire().equals("P,2,10,123,5000,60000,1\n"),
                "Position includes map ID and pause");
        check(protocol.position(123, 0, 0, false).wire().equals("P,2,11,123,0,0,0\n"), "Position lower bounds");
        check(protocol.stop().wire().equals("X,2,12\n"), "Stop v2");
        check(Protocol.acknowledged("A,12,OK", 12), "Compatible ACK");
        protocol.reset();
        check(protocol.hello().sequence() == 1, "Sequence reset");
        List<Protocol.Command> again = protocol.transfer(map);
        check(again.get(again.size() - 1).wire().equals(expected[6]), "CRC independent of session");
        long checksum = NetworkProtocol.checksum(map);
        check(NetworkProtocol.checksum(new LocalNetwork(124, 1, map.nodes(), map.edges(), map.route())) != checksum,
                "Map ID is protected by CRC");
        check(NetworkProtocol.checksum(network(new int[][] {{0, 0}, {3, 5}}, new int[][] {{0, 1}}, 0, 1)) != checksum,
                "Coordinate is protected by CRC");
        check(NetworkProtocol.checksum(network(new int[][] {{0, 0}, {3, 4}}, new int[][] {{1, 0}}, 0, 1)) != checksum,
                "Exact edge encoding is protected by CRC");
        check(NetworkProtocol.checksum(network(new int[][] {{0, 0}, {3, 4}}, new int[][] {{0, 1}}, 1, 0)) != checksum,
                "Route is protected by CRC");
    }

    private static void protocolBounds() throws Exception {
        NetworkProtocol protocol = new NetworkProtocol();
        rejects(() -> protocol.transfer(null), "Null network transfer");
        rejects(() -> NetworkProtocol.canonicalBody(null), "Null canonical network");
        rejects(() -> protocol.restart(0), "Zero restart ID");
        rejects(() -> protocol.restart(-1), "Negative restart ID");
        rejects(() -> protocol.position(0, 0, 0, false), "Zero position ID");
        rejects(() -> protocol.position(-1, 0, 0, false), "Negative position ID");
        rejects(() -> protocol.position(1, -1, 0, false), "Negative distance");
        rejects(() -> protocol.position(1, 10_000_001, 0, false), "Distance above bound");
        rejects(() -> protocol.position(1, 0, -1, false), "Negative speed");
        rejects(() -> protocol.position(1, 0, 60_001, false), "Speed above bound");
        rejects(() -> protocol.position(simple(), 5001, 0, false), "Distance beyond selected route");
        rejects(() -> protocol.position((LocalNetwork) null, 0, 0, false), "Null position network");
        check(protocol.position(Integer.MAX_VALUE, 10_000_000, 60_000, false).sequence() == 1,
                "Invalid calls never consume sequence; maximum values accepted");
        Field sequence = NetworkProtocol.class.getDeclaredField("sequence");
        sequence.setAccessible(true);
        sequence.setInt(protocol, Integer.MAX_VALUE - 1);
        check(protocol.stop().sequence() == Integer.MAX_VALUE, "Maximum sequence accepted");
        boolean exhausted = false;
        try { protocol.hello(); } catch (IllegalStateException expected) { exhausted = true; }
        check(exhausted, "Sequence cannot wrap");
        sequence.setInt(protocol, Integer.MAX_VALUE - 6);
        exhausted = false;
        try { protocol.transfer(simple()); } catch (IllegalStateException expected) { exhausted = true; }
        check(exhausted && sequence.getInt(protocol) == Integer.MAX_VALUE - 6,
                "Whole transaction reserve fails atomically");
        sequence.setInt(protocol, Integer.MAX_VALUE - 7);
        List<Protocol.Command> last = protocol.transfer(simple());
        check(last.get(last.size() - 1).sequence() == Integer.MAX_VALUE, "Transaction can end at max sequence");
        for (Protocol.Command command : last) chunks(command);
        protocol.reset();
        check(protocol.hello().wire().equals("H,2,1\n"), "Reconnect after exhaustion");
    }

    private static void chunks(Protocol.Command command) {
        byte[] ascii = command.wire().getBytes(StandardCharsets.US_ASCII);
        check(ascii.length <= 95 && command.wire().endsWith("\n"), "Frame byte and LF bound");
        check(StandardCharsets.US_ASCII.newEncoder().canEncode(command.wire()), "ASCII frame");
        StringBuilder reconstructed = new StringBuilder();
        for (byte[] chunk : Protocol.chunks(command.wire())) {
            check(chunk.length > 0 && chunk.length <= 20, "ATT MTU-23 chunk");
            reconstructed.append(new String(chunk, StandardCharsets.US_ASCII));
        }
        check(reconstructed.toString().equals(command.wire()), "Lossless BLE chunk assembly");
        String[] fields = command.wire().trim().split(",");
        check(Integer.parseInt(fields[2]) == command.sequence(), "Wire and command sequence match");
    }

    /** Decode transmitted fields rather than reusing the encoder's canonical helpers. */
    private static void roundTrip(LocalNetwork source) {
        NetworkProtocol protocol = new NetworkProtocol();
        chunks(protocol.hello());
        List<Protocol.Command> commands = protocol.transfer(source);
        String[] begin = commands.get(0).wire().trim().split(",");
        int mapId = Integer.parseInt(begin[3]);
        int version = Integer.parseInt(begin[4]);
        int[][] nodes = new int[Integer.parseInt(begin[5])][2];
        int[][] edges = new int[Integer.parseInt(begin[6])][2];
        int[] route = new int[Integer.parseInt(begin[7])];
        int payloadLength = Integer.parseInt(begin[8]);
        StringBuilder body = new StringBuilder();
        int expectedSequence = 2;
        int nodeCursor = 0, edgeCursor = 0, routeCursor = 0;
        for (Protocol.Command command : commands) {
            chunks(command);
            check(command.sequence() == expectedSequence++, "Round trip contiguous sequence");
            String[] fields = command.wire().trim().split(",");
            if (fields[0].equals("N")) {
                check(edgeCursor == 0 && routeCursor == 0, "Nodes before edges/route");
                check(Integer.parseInt(fields[3]) == nodeCursor, "Ordered node index");
                nodes[nodeCursor++] = new int[] {Integer.parseInt(fields[4]), Integer.parseInt(fields[5])};
            } else if (fields[0].equals("E")) {
                check(nodeCursor == nodes.length && routeCursor == 0, "Edges after nodes/before route");
                check(Integer.parseInt(fields[3]) == edgeCursor, "Ordered edge index");
                edges[edgeCursor++] = new int[] {Integer.parseInt(fields[4]), Integer.parseInt(fields[5])};
            } else if (fields[0].equals("T")) {
                check(nodeCursor == nodes.length && edgeCursor == edges.length, "Route after nodes/edges");
                check(Integer.parseInt(fields[3]) == routeCursor, "Ordered route index");
                route[routeCursor++] = Integer.parseInt(fields[4]);
            } else continue;
            body.append(fields[0]);
            for (int i = 3; i < fields.length; i++) body.append(',').append(fields[i]);
            body.append('\n');
        }
        check(nodeCursor == nodes.length && edgeCursor == edges.length && routeCursor == route.length,
                "All declared records transmitted");
        check(body.toString().getBytes(StandardCharsets.US_ASCII).length == payloadLength
                && payloadLength > 0 && payloadLength <= 2048, "Transmitted payload length");
        String canonical = "B," + String.join(",", Arrays.copyOfRange(begin, 3, begin.length)) + "\n" + body;
        CRC32 crc = new CRC32();
        crc.update(canonical.getBytes(StandardCharsets.US_ASCII));
        String[] commit = commands.get(commands.size() - 1).wire().trim().split(",");
        check(commit[0].equals("C") && Long.parseLong(commit[3]) == crc.getValue(), "Commit matches reconstructed CRC");
        LocalNetwork decoded = new LocalNetwork(mapId, version, nodes, edges, route);
        check(decoded.nodes().equals(source.nodes()) && decoded.edges().equals(source.edges())
                && decoded.route().equals(source.route()) && decoded.lengthMm() == source.lengthMm(),
                "End-to-end graph, route and millimetres");
        chunks(protocol.restart(mapId));
        chunks(protocol.position(decoded, decoded.lengthMm(), 60_000, true));
        chunks(protocol.stop());
    }

    private static void maximumAndRandomGraphs() {
        int[][] nodes = new int[16][2];
        List<int[]> edges = new ArrayList<>();
        int[] route = new int[16];
        for (int i = 0; i < 16; i++) {
            nodes[i] = new int[] {-2000 + i * 100, -2000};
            route[i] = i;
            if (i > 0) edges.add(new int[] {i - 1, i});
        }
        for (int i = 2; edges.size() < 24; i++) edges.add(new int[] {0, i});
        LocalNetwork maximum = new LocalNetwork(Integer.MAX_VALUE, 1, nodes, edges.toArray(new int[0][]), route);
        check(maximum.nodes().size() == 16 && maximum.edges().size() == 24 && maximum.route().size() == 16,
                "All count upper bounds accepted");
        roundTrip(maximum);
        Random random = new Random(0x4d4f544fL);
        for (int trial = 0; trial < 100; trial++) {
            int count = 2 + random.nextInt(15);
            int[][] generatedNodes = new int[count][2];
            int[][] generatedEdges = new int[count - 1][2];
            int[] generatedRoute = new int[count];
            for (int i = 0; i < count; i++) {
                generatedNodes[i] = new int[] {-1000 + i * 100, -1000 + random.nextInt(201)};
                generatedRoute[i] = i;
                if (i > 0) generatedEdges[i - 1] = random.nextBoolean()
                        ? new int[] {i - 1, i} : new int[] {i, i - 1};
            }
            roundTrip(new LocalNetwork(trial + 1, 1, generatedNodes, generatedEdges, generatedRoute));
        }
    }

    public static void main(String[] args) throws Exception {
        modelBounds();
        defensiveCopies();
        demoRoutes();
        goldenWire();
        protocolBounds();
        roundTrip(LocalNetwork.demo(1, false));
        roundTrip(LocalNetwork.demo(2, true));
        maximumAndRandomGraphs();
        System.out.println("PASS: " + checks + " checks (local network bounds, immutable graph, two demo routes,"
                + " v2 golden CRC, atomic sequence, round-trip transfer, BLE chunks)");
    }
}
