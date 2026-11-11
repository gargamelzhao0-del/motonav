package com.motonav.companion;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/** Version 3 carries real guidance, isolated from the v1/v2 simulation protocols. */
public final class NavigationProtocol {
    private final int session;
    private int sequence;

    public NavigationProtocol(int session) {
        if (session <= 0) throw new IllegalArgumentException("session");
        this.session = session;
    }

    public void reset() { sequence = 0; }

    private int next() {
        if (sequence == Integer.MAX_VALUE) throw new IllegalStateException("Reconnect to reset sequence");
        return ++sequence;
    }

    public Protocol.Command hello() {
        int seq = next();
        return command(seq, "H,3," + seq + "," + session);
    }

    public Protocol.Command snapshot(NavigationFrame source, long nowMs) {
        // Reserve the transport's three-second timeout so receiving a slow frame cannot renew its source age.
        long deliveryTime = nowMs + 3000;
        NavigationFrame frame = source.at(deliveryTime);
        boolean live = frame.mode() == NavigationFrame.LIVE;
        int seq = next();
        return command(seq, "N,3," + seq + "," + session + "," + frame.mode()
                + "," + frame.icon() + "," + frame.turnMeters() + "," + frame.remainingMeters()
                + "," + frame.remainingSeconds() + "," + frame.speedKmh()
                + "," + (live ? NavigationFrame.age(deliveryTime, frame.fixTimeMs()) : 0)
                + "," + (live ? NavigationFrame.age(deliveryTime, frame.instructionTimeMs()) : 0));
    }

    private static Protocol.Command command(int seq, String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        String wire = body + "," + String.format(Locale.ROOT, "%08X", crc.getValue()) + "\n";
        Protocol.chunks(wire); // The encoder and transport share the same length limit.
        return new Protocol.Command(seq, wire);
    }
}
