package com.motonav.companion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** NAV4 is an atomic, bounded binary snapshot on a separately negotiated GATT service. */
public final class RouteProtocol {
    public static final int MAX_BYTES=304;
    public record Command(int sequence, byte[] bytes, boolean hello) { }
    private final int session;
    private int sequence;
    public RouteProtocol(int session) { if(session<=0)throw new IllegalArgumentException("session"); this.session=session; }
    public void reset() { sequence=0; }
    private ByteBuffer begin(int length,int type) {
        if(sequence==Integer.MAX_VALUE)throw new IllegalStateException("Reconnect to reset sequence");
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).put((byte)'M').put((byte)'4')
                .putShort((short)length).put((byte)type).putInt(++sequence).putInt(session);
    }
    private Command finish(ByteBuffer data,boolean hello) {
        CRC32 crc=new CRC32(); crc.update(data.array(),0,data.capacity()-4); data.putInt((int)crc.getValue());
        if(data.position()!=data.capacity())throw new IllegalStateException("frame length");
        return new Command(sequence,data.array(),hello);
    }
    public Command hello() { return finish(begin(17,1),true); }
    public Command snapshot(NavigationFrame source,DeviceRoute.Frame route,long nowMs) {
        long delivery=nowMs+3000; NavigationFrame frame=source.at(delivery);
        boolean live=frame.mode()==NavigationFrame.LIVE;
        if(!live)route=null;
        if(route!=null) {
            if(route.revision()<=0||route.points().size()<2||route.points().size()>64
                    ||Math.abs((long)route.x())>32000||Math.abs((long)route.y())>32000||route.heading()<-1||route.heading()>3599)
                throw new IllegalArgumentException("map bounds");
            for(DeviceRoute.Point p:route.points())if(Math.abs((long)p.x())>32000||Math.abs((long)p.y())>32000)
                throw new IllegalArgumentException("point bounds");
        }
        int count=route==null?0:route.points().size();
        ByteBuffer b=begin(48+4*count,2).put((byte)frame.mode()).put((byte)frame.icon())
                .putInt(frame.turnMeters()).putInt(frame.remainingMeters()).putInt(frame.remainingSeconds()).putShort((short)frame.speedKmh())
                .putShort((short)(live?NavigationFrame.age(delivery,frame.fixTimeMs()):0))
                .putShort((short)(live?NavigationFrame.age(delivery,frame.instructionTimeMs()):0))
                .putInt(route==null?0:route.revision()).put((byte)count)
                .putShort((short)(route==null?0:route.x())).putShort((short)(route==null?0:route.y()))
                .putShort((short)(route==null?-1:route.heading()));
        if(route!=null)for(DeviceRoute.Point p:route.points())b.putShort((short)p.x()).putShort((short)p.y());
        return finish(b,false);
    }
}
