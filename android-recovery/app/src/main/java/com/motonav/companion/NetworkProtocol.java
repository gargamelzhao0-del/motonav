package com.motonav.companion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * BLE v2 encoder for bounded synthetic local networks. A transfer stages B/N/E/T
 * before C commits it on the device. Encoding does not imply device acceptance:
 * the caller must await each ACK and retain its current map until C is acknowledged.
 */
public final class NetworkProtocol {
    public static final int MAX_PAYLOAD_BYTES = 2048;
    public static final int MAX_SPEED_MMPS = 60_000;
    private int sequence;

    /** Only reset when establishing a new transport session. */
    public synchronized void reset() { sequence = 0; }

    public synchronized Protocol.Command hello() {
        int n = reserve(1);
        Protocol.Command command = command(n, "H,2," + n + "\n");
        sequence = n;
        return command;
    }

    /**
     * Ordered, immutable B,N*,E*,T*,C commands. Map IDs must be fresh relative to
     * the device's committed map; use restart(mapId) to replay an existing map.
     * All frame construction succeeds before sequence numbers are consumed.
     */
    public synchronized List<Protocol.Command> transfer(LocalNetwork network) {
        requireNetwork(network);
        String body = canonicalBody(network);
        int payload = checkedPayloadLength(body);
        long checksum = checksum(network, body, payload);
        int count = network.nodes().size() + network.edges().size() + network.route().size() + 2;
        int n = reserve(count);
        List<Protocol.Command> commands = new ArrayList<>(count);
        commands.add(command(n, "B,2," + n + "," + metadata(network, payload) + "\n"));
        n++;
        for (int i = 0; i < network.nodes().size(); i++, n++) {
            LocalNetwork.Node node = network.nodes().get(i);
            commands.add(command(n, "N,2," + n + "," + i + "," + node.x() + "," + node.y() + "\n"));
        }
        for (int i = 0; i < network.edges().size(); i++, n++) {
            LocalNetwork.Edge edge = network.edges().get(i);
            commands.add(command(n, "E,2," + n + "," + i + "," + edge.a() + "," + edge.b() + "\n"));
        }
        for (int i = 0; i < network.route().size(); i++, n++) {
            commands.add(command(n, "T,2," + n + "," + i + "," + network.route().get(i) + "\n"));
        }
        commands.add(command(n, "C,2," + n + "," + checksum + "\n"));
        sequence = n;
        return Collections.unmodifiableList(commands);
    }

    public synchronized Protocol.Command restart(int mapId) {
        requireMapId(mapId);
        int n = reserve(1);
        Protocol.Command command = command(n, "R,2," + n + "," + mapId + "\n");
        sequence = n;
        return command;
    }

    /** The caller must additionally ensure distanceMm does not exceed this map's route. */
    public synchronized Protocol.Command position(int mapId, int distanceMm, int speedMmps,
                                                   boolean paused) {
        requireMapId(mapId);
        if (distanceMm < 0 || distanceMm > LocalNetwork.MAX_LENGTH_MM
                || speedMmps < 0 || speedMmps > MAX_SPEED_MMPS) {
            throw new IllegalArgumentException("Position or speed is outside protocol bounds");
        }
        int n = reserve(1);
        Protocol.Command command = command(n, "P,2," + n + "," + mapId + ","
                + distanceMm + "," + speedMmps + "," + (paused ? 1 : 0) + "\n");
        sequence = n;
        return command;
    }

    /** Preferred overload when the selected network is available. */
    public synchronized Protocol.Command position(LocalNetwork network, int distanceMm,
                                                   int speedMmps, boolean paused) {
        requireNetwork(network);
        if (distanceMm > network.lengthMm()) throw new IllegalArgumentException("Position exceeds route");
        return position(network.mapId(), distanceMm, speedMmps, paused);
    }

    public synchronized Protocol.Command stop() {
        int n = reserve(1);
        Protocol.Command command = command(n, "X,2," + n + "\n");
        sequence = n;
        return command;
    }

    /** Canonical ASCII body: LF-terminated N lines, then E lines, then T lines. */
    public static String canonicalBody(LocalNetwork network) {
        requireNetwork(network);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < network.nodes().size(); i++) {
            LocalNetwork.Node node = network.nodes().get(i);
            body.append("N,").append(i).append(',').append(node.x()).append(',').append(node.y()).append('\n');
        }
        for (int i = 0; i < network.edges().size(); i++) {
            LocalNetwork.Edge edge = network.edges().get(i);
            body.append("E,").append(i).append(',').append(edge.a()).append(',').append(edge.b()).append('\n');
        }
        for (int i = 0; i < network.route().size(); i++) {
            body.append("T,").append(i).append(',').append(network.route().get(i)).append('\n');
        }
        return body.toString();
    }

    public static int payloadLength(LocalNetwork network) {
        return checkedPayloadLength(canonicalBody(network));
    }

    /** Unsigned IEEE CRC-32 of canonical B metadata followed by the canonical body. */
    public static long checksum(LocalNetwork network) {
        String body = canonicalBody(network);
        return checksum(network, body, checkedPayloadLength(body));
    }

    private static long checksum(LocalNetwork network, String body, int payload) {
        CRC32 crc = new CRC32();
        byte[] bytes = ("B," + metadata(network, payload) + "\n" + body).getBytes(StandardCharsets.US_ASCII);
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    private static String metadata(LocalNetwork network, int payload) {
        return network.mapId() + "," + network.coordinateVersion() + "," + network.nodes().size()
                + "," + network.edges().size() + "," + network.route().size() + "," + payload;
    }

    private static int checkedPayloadLength(String body) {
        int length = body.getBytes(StandardCharsets.US_ASCII).length;
        if (length < 1 || length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Payload length");
        return length;
    }

    private int reserve(int count) {
        if (sequence > Integer.MAX_VALUE - count) {
            throw new IllegalStateException("Reconnect to reset sequence");
        }
        return sequence + 1;
    }

    private static Protocol.Command command(int sequence, String wire) {
        Protocol.chunks(wire); // Enforce the same ASCII, LF and <=95-byte framing as BLE v1.
        return new Protocol.Command(sequence, wire);
    }

    private static void requireMapId(int mapId) {
        if (mapId <= 0) throw new IllegalArgumentException("Map ID must be positive");
    }

    private static void requireNetwork(LocalNetwork network) {
        if (network == null) throw new IllegalArgumentException("Network is required");
    }
}
