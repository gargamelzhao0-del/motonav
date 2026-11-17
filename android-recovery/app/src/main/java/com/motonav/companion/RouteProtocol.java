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
    public Command helloWithPreview() { return finish(begin(17,3),true); }
    public Command helloWithBasemap() { return finish(begin(17,5),true); }
    public Command helloWithMapUpdates() { return finish(begin(17,8),true); }
    public Command helloWithDetails() { return finish(begin(17,11),true); }
    public Command helloWithPosition() { return finish(begin(17,13),true); }
    public Command helloWithLabels() { return finish(begin(17,15),true); }
    public Command helloWithSurfaces() { return finish(begin(17,17),true); }
    public Command helloWithLabelSources() { return finish(begin(17,19),true); }
    public Command labels(Basemap.Scene scene,int offset) {
        return labels(scene,offset,false);
    }
    public Command labels(Basemap.Scene scene,int offset,boolean sources) {
        if(!sources&&scene.labelSources()!=1)throw new IllegalArgumentException("unnegotiated label source");
        int total=scene.labels().size();
        if(offset<0||offset>total||offset==total&&total!=0)throw new IllegalArgumentException("label offset");
        int count=Math.min(sources?3:4,total-offset),length=sources?29:28;
        for(int i=offset;i<offset+count;i++)length+=6+scene.labels().get(i).name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(length>MAX_BYTES)throw new IllegalArgumentException("label frame size");
        ByteBuffer b=begin(length,sources?18:14).putInt(scene.revision()).putInt(scene.update())
                .put((byte)total).put((byte)offset).put((byte)count);
        if(sources)b.put((byte)scene.labelSources());
        for(int i=offset;i<offset+count;i++) {
            var label=scene.labels().get(i);byte[] name=label.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            b.put((byte)label.kind()).putShort((short)label.point().x()).putShort((short)label.point().y())
                    .put((byte)name.length).put(name);
        }
        return finish(b,false);
    }
    public Command details(int revision,int navigationSequence,NavigationDetails details) {
        if(revision<=0||navigationSequence<=0||details==null)throw new IllegalArgumentException("details reference");
        byte[] current=details.currentRoad().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] next=details.nextRoad().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return finish(begin(31+current.length+next.length,10).putInt(revision).putInt(navigationSequence)
                .putShort((short)details.etaMinute()).putShort((short)details.speedLimit())
                .put((byte)current.length).put((byte)next.length).put(current).put(next),false);
    }
    public Command focus(Basemap.Focus focus) {
        return focus(focus,false,0);
    }
    public Command focusWithPosition(Basemap.Focus focus,long nowMs) {
        return focus(focus,true,nowMs);
    }
    private Command focus(Basemap.Focus focus,boolean positions,long nowMs) {
        var route=focus.route();int count=route.points().size();
        if(count<2||count>64||route.revision()<=0||focus.reason()<0||focus.reason()>7
                ||Math.abs((long)route.x())>32000||Math.abs((long)route.y())>32000||route.heading()<-1||route.heading()>3599
                ||focus.totalMeters()<-1||focus.totalMeters()>50000000||focus.totalSeconds()<-1||focus.totalSeconds()>31536000)
            throw new IllegalArgumentException("local focus");
        ByteBuffer b=begin((positions?42:37)+4*count,positions?12:6).put((byte)focus.reason()).put((byte)count).putInt(route.revision())
                .putShort((short)route.x()).putShort((short)route.y()).putShort((short)route.heading())
                .putInt(focus.totalMeters()).putInt(focus.totalSeconds());
        if(positions){
            var position=focus.position();long delivery=nowMs+3000;
            if(position==null||!position.fresh(nowMs)||!position.fresh(delivery))position=Basemap.Position.unknown();
            b.put((byte)position.quality()).putShort((short)position.accuracyMeters())
                    .putShort((short)(position.quality()==0?0:delivery-position.measuredMs()));
        }
        for(var p:route.points()) {
            if(Math.abs((long)p.x())>32000||Math.abs((long)p.y())>32000)throw new IllegalArgumentException("local point");
            b.putShort((short)p.x()).putShort((short)p.y());
        }
        return finish(b,false);
    }
    public Command basemap(Basemap.Scene scene,int offset) {
        return basemap(scene,offset,false);
    }
    public Command basemap(Basemap.Scene scene,int offset,boolean updates) {
        int total=scene.items().size();
        if(!updates&&total>192)throw new IllegalArgumentException("legacy map budget");
        if(offset<0||offset>total||(offset==total&&total!=0))throw new IllegalArgumentException("map offset");
        int count=Math.min(19,total-offset);
        ByteBuffer b=begin((updates?31:26)+14*count,updates?9:7).putInt(scene.revision());
        if(updates)b.putInt(scene.update());
        b.putShort((short)total).putShort((short)offset).put((byte)count);
        if(updates)b.put((byte)(scene.complete()?1:0));
        for(int i=offset;i<offset+count;i++) {
            var p=scene.items().get(i);b.put((byte)p.kind()).put((byte)0);
            for(var q:java.util.List.of(p.a(),p.b(),p.c()))b.putShort((short)q.x()).putShort((short)q.y());
        }
        return finish(b,false);
    }
    public Command plan(DeviceRoute.Preview preview) {
        if(preview==null || preview.revision()<=0 || preview.waitingReason()<0 || preview.waitingReason()>7
                || preview.points().size()<2 || preview.points().size()>64)throw new IllegalArgumentException("route preview");
        for(DeviceRoute.Point p:preview.points())if(Math.abs((long)p.x())>1024 || Math.abs((long)p.y())>1024)
            throw new IllegalArgumentException("overview bounds");
        ByteBuffer b=begin(23+4*preview.points().size(),4).put((byte)preview.waitingReason())
                .put((byte)preview.points().size()).putInt(preview.revision());
        for(DeviceRoute.Point p:preview.points())b.putShort((short)p.x()).putShort((short)p.y());
        return finish(b,false);
    }
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
