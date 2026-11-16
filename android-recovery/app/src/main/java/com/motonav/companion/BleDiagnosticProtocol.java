package com.motonav.companion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** DIAG1: fixed fields, no coordinates, arbitrary strings or navigation mutations. */
public final class BleDiagnosticProtocol {
    public static final int FRAME_BYTES = 88, FRAGMENT_BYTES = 16;
    public static final long UNKNOWN = 0xffff_ffffL;
    public record Observation(int flags, int phase, int reason, int mode, long trustedFixAge,
            long mapFixAge, long sdkFixAge, long instructionAge, long accuracyDm) { }
    public record Transmission(long atMs, int sequence, int phoneMode, int wireMode, int protocol,
            int sourcePoints, int wirePoints, int reason, long fixAge, long instructionAge) { }
    private BleDiagnosticProtocol() { }

    public static long age(long nowMs, long observedMs) {
        return observedMs <= 0 || nowMs < observedMs ? UNKNOWN : Math.min(UNKNOWN - 1, nowMs - observedMs);
    }
    public static Transmission navigation(NavigationFrame source, int sourcePoints, int wireMode,
            int wirePoints, int protocol, int sequence, long nowMs) {
        long fixAge = age(nowMs, source.fixTimeMs()), instructionAge = age(nowMs, source.instructionTimeMs());
        int reason = 0;
        if (wireMode == NavigationFrame.WAITING) {
            if (source.mode() != NavigationFrame.LIVE) reason = 1;
            else if (!source.liveAt(nowMs)) reason = 5;
            else {
                boolean fix = fixAge + 3000 > NavigationFrame.MAX_AGE_MS;
                boolean instruction = instructionAge + 3000 > NavigationFrame.MAX_AGE_MS;
                reason = fix && instruction ? 4 : fix ? 2 : instruction ? 3 : 5;
            }
        }
        return new Transmission(nowMs, sequence, source.mode(), wireMode, protocol,
                sourcePoints, wirePoints, reason, fixAge, instructionAge);
    }
    public static byte[] snapshot(int session, int sequence, long now, Observation o, Transmission t,
            int protocol, int ackSequence, long ackLatency, long dropped, long markers, int version, boolean busy) {
        if (session <= 0 || sequence <= 0 || o == null || o.phase() < 0 || o.phase() > 5 || o.reason() < 0 || o.reason() > 6
                || o.flags() < 0 || o.flags() > 15 || o.mode() < 1 || o.mode() > 4 || (protocol != 3 && protocol != 4))
            throw new IllegalArgumentException("diagnostic_snapshot");
        ByteBuffer b = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)'D').put((byte)'1').putShort((short)FRAME_BYTES).putInt(session).putInt(sequence).putInt((int)now)
            .putInt(t == null ? 0 : (int)t.atMs()).putInt(t == null ? 0 : t.sequence()).putInt(ackSequence)
            .putInt((int)ackLatency).putInt((int)(t == null ? UNKNOWN : t.fixAge()))
            .putInt((int)(t == null ? UNKNOWN : t.instructionAge())).putInt((int)o.trustedFixAge())
            .putInt((int)o.mapFixAge()).putInt((int)o.sdkFixAge()).putInt((int)o.instructionAge()).putInt((int)o.accuracyDm())
            .putInt((int)Math.min(UNKNOWN,dropped)).putInt((int)Math.min(UNKNOWN,markers)).putInt(version)
            .putShort((short)(o.flags() | (busy ? 16 : 0))).put((byte)o.phase()).put((byte)o.reason())
            .put((byte)(t == null ? 0 : t.phoneMode())).put((byte)(t == null ? 0 : t.wireMode())).put((byte)protocol)
            .put((byte)(t == null ? 0 : t.sourcePoints())).put((byte)(t == null ? 0 : t.wirePoints()))
            .put((byte)(t == null ? 0 : t.reason())).put((byte)o.mode()).put((byte)0);
        CRC32 crc = new CRC32(); crc.update(b.array(),0,FRAME_BYTES-4); b.putInt((int)crc.getValue());
        if (b.position() != FRAME_BYTES) throw new IllegalStateException("diagnostic_length");
        return b.array();
    }
    public static byte[] fragment(byte[] frame, int offset) {
        if (frame == null || frame.length != FRAME_BYTES || offset < 0 || offset >= FRAME_BYTES || offset % FRAGMENT_BYTES != 0)
            throw new IllegalArgumentException("diagnostic_fragment");
        int length = Math.min(FRAGMENT_BYTES,FRAME_BYTES-offset);
        byte[] packet = new byte[length+4];
        packet[0]=(byte)0xd1; packet[1]=frame[8]; packet[2]=(byte)offset; packet[3]=(byte)length;
        System.arraycopy(frame,offset,packet,4,length);
        return packet;
    }
}
