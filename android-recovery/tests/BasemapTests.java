package com.motonav.companion;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class BasemapTests {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static DeviceRoute.Point p(int x,int y){return new DeviceRoute.Point(x,y);}
    static byte[] bytes(int... values){byte[] out=new byte[values.length];for(int i=0;i<values.length;i++)out[i]=(byte)values[i];return out;}
    static byte[] message(int tag,byte[] value)throws IOException{var out=new ByteArrayOutputStream();out.write(tag);int size=value.length;while(size>127){out.write((size&127)|128);size>>>=7;}out.write(size);out.write(value);return out.toByteArray();}
    static byte[] join(byte[]... values)throws IOException{var out=new ByteArrayOutputStream();for(var v:values)out.write(v);return out.toByteArray();}
    static byte[] tile(byte[] geometry)throws IOException{
        var feature=join(message(18,bytes(0,0)),bytes(24,2),message(34,geometry));
        return message(26,join(message(10,"streets".getBytes()),message(18,feature),message(26,"kind".getBytes()),
                message(34,message(10,"residential".getBytes())),bytes(40,128,32,120,2)));
    }
    public static void main(String[] args)throws Exception{
        var origin=new DeviceRoute.Coordinate(51.5,-.12);
        Basemap.Coordinates offset=c->new DeviceRoute.Coordinate(c.latitude()+.002,c.longitude()+.004);
        var gps=Basemap.gpsOrigin(offset.toMap(origin),offset);
        check(Math.abs(gps.latitude()-origin.latitude())<1e-7&&Math.abs(gps.longitude()-origin.longitude())<1e-7,"inverse SDK conversion");
        for(double latitude:new double[]{-80,-30,0,30,51,80})check(Basemap.visibleTiles(new DeviceRoute.Coordinate(latitude,0)).size()<=9,"visible tile bound");
        var parsed=VectorTile.decode(tile(bytes(9,0,0,18,20,0,0,20)));
        check(parsed.size()==1&&parsed.get(0).paths().get(0).size()==3,"MVT delta decoding");
        check(parsed.get(0).paths().get(0).get(2).equals(new VectorTile.Point(10,10)),"MVT axes");
        for(byte[] bad:List.of(bytes(0),bytes(26,10,1),tile(bytes(9,0,0,255)),tile(bytes(10,0,0)))) {
            boolean rejected=false;try{VectorTile.decode(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"malformed MVT rejected");
        }
        var concave=List.of(p(0,0),p(100,0),p(100,100),p(50,50),p(0,100));
        check(LocalMapBuilder.triangulate(concave).size()==3,"concave polygon tessellated");
        DeviceRoute route=new DeviceRoute();route.replace(List.of(new DeviceRoute.Coordinate(30,120),new DeviceRoute.Coordinate(30.003,120),new DeviceRoute.Coordinate(30.1,120)));
        var local=route.localFrame();check(local!=null&&local.x()==0&&local.y()==0,"local start without live GPS");
        var moved=route.frame(30.001,120,30);check(route.localFrame().equals(moved),"GPS loss retains last camera");
        var retainedOrigin=route.origin();check(route.frame(31,120,0)==null&&route.origin().equals(retainedOrigin),"failed recenter cannot move retained map origin");
        route.clear();check(route.localFrame()==null,"stop removes local camera");
        var frame=new DeviceRoute.Frame(1,List.of(p(0,-200),p(0,240),p(250,240),p(250,700)),0,0,0);
        List<Basemap.Primitive> items=new ArrayList<>();
        for(int i=0;i<23;i++)items.add(new Basemap.Primitive(i%3,p(-400+i*30,-400),p(-400+i*30,700),p(0,0)));
        var map=new Basemap.Scene(1,items);RouteProtocol protocol=new RouteProtocol(123);
        List<RouteProtocol.Command> commands=new ArrayList<>();commands.add(protocol.helloWithBasemap());
        commands.add(protocol.snapshot(NavigationFrame.status(1),null,100000));
        commands.add(protocol.focus(new Basemap.Focus(frame,origin,1,5000,600)));
        commands.add(protocol.basemap(map,0));commands.add(protocol.basemap(map,19));
        commands.add(protocol.snapshot(new NavigationFrame(2,3,120,4000,360,36,100000,100000),frame,100000));
        commands.add(protocol.snapshot(NavigationFrame.status(1),null,104002));
        commands.add(protocol.snapshot(NavigationFrame.status(4),null,104003));
        var encoded=new StringBuilder();for(var command:commands){check(command.bytes().length<=304,"bounded radio frame");encoded.append(HexFormat.of().formatHex(command.bytes())).append('\n');}
        if(args.length>0)Files.writeString(Path.of(args[0]),encoded);
        RouteProtocol progressive=new RouteProtocol(123);
        var early=new Basemap.Scene(1,1,false,items);var completeMap=new Basemap.Scene(1,2,true,items);
        var updates=List.of(progressive.helloWithMapUpdates(),progressive.snapshot(NavigationFrame.status(1),null,100000),
                progressive.focus(new Basemap.Focus(frame,origin,1,5000,600)),progressive.basemap(early,0,true),progressive.basemap(early,19,true),
                progressive.basemap(completeMap,0,true),progressive.basemap(completeMap,19,true),
                progressive.snapshot(new NavigationFrame(2,3,120,4000,360,36,100000,100000),frame,100000),
                progressive.snapshot(NavigationFrame.status(1),null,104002),progressive.snapshot(NavigationFrame.status(4),null,104003));
        var updateWire=new StringBuilder();for(var command:updates){check(command.bytes().length<=304,"bounded progressive frame");updateWire.append(HexFormat.of().formatHex(command.bytes())).append('\n');}
        if(args.length>0)Files.writeString(Path.of(args[0]).resolveSibling("nav4-map-updates.frames"),updateWire);
        // A budget may omit a complete building, but must not take only one
        // triangle from its footprint. Exercise dense MVT multipolygon data.
        var syntheticTile=new Basemap.Tile(14,8186,5448);
        var syntheticOrigin=Basemap.coordinate(syntheticTile,4096,2048,2048);
        List<List<VectorTile.Point>> buildings=new ArrayList<>();
        for(int i=0;i<400;i++){int x=1800+(i%20)*20,y=1800+(i/20)*20;buildings.add(List.of(new VectorTile.Point(x,y),new VectorTile.Point(x+10,y),new VectorTile.Point(x+10,y+10),new VectorTile.Point(x,y+10)));}
        var grouped=new LocalMapBuilder(syntheticOrigin,c->c);grouped.add(syntheticTile,List.of(new VectorTile.Feature("buildings","",3,4096,buildings)));
        var completeBuildings=grouped.finish(1).items();check(!completeBuildings.isEmpty()&&completeBuildings.size()%2==0,"building triangles kept as complete objects");
        for(int i=0;i<completeBuildings.size();i+=2){var a=completeBuildings.get(i);var b=completeBuildings.get(i+1);long shared=List.of(a.a(),a.b(),a.c()).stream().filter(v->List.of(b.a(),b.b(),b.c()).contains(v)).count();check(shared==2,"each selected building retains both triangles");}
        if(args.length>1) {
            var tile=new Basemap.Tile(14,8186,5448);var decoded=VectorTile.decodeDetailed(Files.readAllBytes(Path.of(args[1])));var features=decoded.features();
            var center=Basemap.coordinate(tile,4096,800,3000);var builder=new LocalMapBuilder(center,c->c);builder.add(tile,decoded);var scene=builder.finish(1);
            System.out.println("REAL TILE: features="+features.size()+" primitives="+scene.items().size()+" kinds="+scene.items().stream().map(Basemap.Primitive::kind).collect(java.util.stream.Collectors.groupingBy(k->k,java.util.stream.Collectors.counting())));
            check(scene.items().size()>50,"actual public tile produces map detail");
            if(args.length>2){
                var radio=new RouteProtocol(123);var lines=new ArrayList<String>();lines.add(HexFormat.of().formatHex(radio.helloWithLabels().bytes()));
                lines.add(HexFormat.of().formatHex(radio.snapshot(NavigationFrame.status(1),null,100000).bytes()));
                // Route for visual QA only, following a real decoded street path.
                var street=features.stream().filter(f->f.type()==2&&f.layer().equals("streets")).flatMap(f->f.paths().stream())
                    .filter(p->p.size()>=4&&p.size()<=64&&p.stream().anyMatch(q->Math.hypot(q.x()-800,q.y()-3000)<600)).findFirst().orElseThrow();
                List<DeviceRoute.Point> points=new ArrayList<>();for(var q:street){var c=Basemap.coordinate(tile,4096,q.x(),q.y());points.add(p((int)Math.round((c.longitude()-center.longitude())*111319.49079327358*Math.cos(Math.toRadians(center.latitude()))),(int)Math.round((c.latitude()-center.latitude())*111319.49079327358)));}
                int nearest=0;for(int i=1;i<points.size()-1;i++)if(Math.hypot(points.get(i).x(),points.get(i).y())<Math.hypot(points.get(nearest).x(),points.get(nearest).y()))nearest=i;
                var pose=points.get(nearest);var ahead=points.get(nearest+1);
                int bearing=(int)Math.round(Math.toDegrees(Math.atan2(ahead.x()-pose.x(),ahead.y()-pose.y()))*10);
                var view=new DeviceRoute.Frame(1,points,pose.x(),pose.y(),Math.floorMod(bearing,3600));
                var focus=new Basemap.Focus(view,center,1,5000,600);
                var navigation=new NavigationFrame(2,3,120,4000,360,36,100000,100000);
                var visibleBuilder=new LocalMapBuilder(center,c->c,MapViewport.of(focus,navigation));
                visibleBuilder.add(tile,decoded);
                scene=visibleBuilder.finish(1);
                System.out.println("REAL CAMERA: primitives="+scene.items().size()+" labels="+scene.labels().size()+" kinds="+scene.items().stream().map(Basemap.Primitive::kind).collect(java.util.stream.Collectors.groupingBy(k->k,java.util.stream.Collectors.counting())));
                System.out.println("REAL QUALITY: "+visibleBuilder.quality(1,1,0,0,scene));
                lines.add(HexFormat.of().formatHex(radio.focus(focus).bytes()));
                for(int i=0;i<scene.items().size();i+=19)lines.add(HexFormat.of().formatHex(radio.basemap(scene,i,true).bytes()));
                for(int i=0;i<Math.max(1,scene.labels().size());i+=4)lines.add(HexFormat.of().formatHex(radio.labels(scene,i).bytes()));
                var live=radio.snapshot(navigation,view,100000);
                lines.add(HexFormat.of().formatHex(live.bytes()));
                lines.add(HexFormat.of().formatHex(radio.details(1,live.sequence(),new NavigationDetails("测试道路","下一路段",686,-1)).bytes()));
                lines.add(HexFormat.of().formatHex(radio.snapshot(NavigationFrame.status(1),null,104002).bytes()));
                lines.add(HexFormat.of().formatHex(radio.snapshot(NavigationFrame.status(4),null,104003).bytes()));
                Files.write(Path.of(args[2]),lines);
            }
        }
        System.out.println("Basemap: "+checks+" decoder, coordinates, local focus, geometry and radio checks passed");
    }
}
