package com.motonav.companion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Protocol {
    private int sequence;
    public void reset() { sequence=0; }
    private int next() { if(sequence==Integer.MAX_VALUE) throw new IllegalStateException("Reconnect to reset sequence"); return ++sequence; }
    public record Command(int sequence,String wire) {}
    public Command hello() { int n=next(); return new Command(n,"H,1,"+n+"\n"); }
    public Command route() { int n=next(); return new Command(n,"R,1,"+n+",1\n"); }
    public Command position(int mm, int speed, boolean paused) {
        if(mm<0||mm>Simulation.TOTAL_MM||speed<0||speed>60000) throw new IllegalArgumentException("Range");
        int n=next(); return new Command(n,"P,1,"+n+","+mm+","+speed+","+(paused?1:0)+"\n");
    }
    public Command stop() { int n=next(); return new Command(n,"X,1,"+n+"\n"); }
    public static List<byte[]> chunks(String wire) {
        if(!wire.endsWith("\n")||wire.length()>95||!StandardCharsets.US_ASCII.newEncoder().canEncode(wire)) throw new IllegalArgumentException("Frame");
        byte[] bytes=wire.getBytes(StandardCharsets.US_ASCII);
        List<byte[]> chunks=new ArrayList<>();
        for(int start=0;start<bytes.length;start+=20) chunks.add(java.util.Arrays.copyOfRange(bytes,start,Math.min(start+20,bytes.length)));
        return chunks;
    }
    public static boolean acknowledged(String line,int sequence) { return line.equals("A,"+sequence+",OK"); }
}
