package com.motonav.companion;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class MapLabelTests {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static DeviceRoute.Point p(int x,int y){return new DeviceRoute.Point(x,y);}
    static byte[] varint(int value){var b=new ByteArrayOutputStream();do{int part=value&127;value>>>=7;b.write(part|(value!=0?128:0));}while(value!=0);return b.toByteArray();}
    static byte[] join(byte[]... values){var b=new ByteArrayOutputStream();for(var v:values)b.writeBytes(v);return b.toByteArray();}
    static byte[] message(int tag,byte[] value){return join(varint(tag),varint(value.length),value);}
    static byte[] string(int tag,String value){return message(tag,value.getBytes(StandardCharsets.UTF_8));}
    static byte[] tile(String layer,int type,byte[] geometry){
        byte[] feature=join(message(18,new byte[]{0,0,1,1,2,2}),new byte[]{24,(byte)type},message(34,geometry));
        return message(26,join(string(10,layer),message(18,feature),string(26,"name"),string(26,"amenity"),string(26,"name_en"),
                message(34,string(10,"人民医院")),message(34,string(10,"hospital")),message(34,string(10,"Hospital")),
                new byte[]{40,(byte)128,32,120,2}));
    }
    public static void main(String[] args)throws Exception{
        var point=VectorTile.decode(tile("pois",1,join(new byte[]{9},varint(4096),varint(4096)))).get(0);
        check(point.name().equals("人民医院")&&point.kind().equals("hospital"),"Retain local name and POI category");
        check(point.paths().get(0).size()==1,"Decode MVT point geometry");
        var multi=VectorTile.decode(tile("pois",1,new byte[]{17,0,0,2,2})).get(0);
        check(multi.paths().size()==2,"Multipoint MoveTo count is supported");
        boolean rejected=false;try{VectorTile.decode(tile("pois",1,new byte[]{9,0,0,10,2,2}));}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"Point geometry rejects LineTo");
        var tile=new Basemap.Tile(14,8186,5448);var origin=Basemap.coordinate(tile,4096,2048,2048);
        var builder=new LocalMapBuilder(origin,c->c);builder.add(tile,List.of(point));
        var parsed=builder.finish(1);
        check(parsed.labels().size()==1&&parsed.labels().get(0).kind()==6&&parsed.labels().get(0).point().equals(p(0,0)),"True POI coordinate survives conversion");
        check(parsed.items().isEmpty(),"POI is separate from road/building budget");
        var unnamed=new LocalMapLabels(0,0);
        unnamed.add(new VectorTile.Feature("pois","fuel",1,4096,List.of()),List.of(p(30,20)));
        unnamed.add(new VectorTile.Feature("pois","charging_station",1,4096,List.of()),List.of(p(60,20)));
        check(unnamed.finish().stream().anyMatch(v->v.kind()==3&&v.name().equals("加油站")),"Verified unnamed POI retains its category");
        check(unnamed.finish().stream().anyMatch(v->v.kind()==2&&v.name().equals("充电站")),"Charging station is not a fuel pump");
        var selection=new LocalMapLabels(0,0);
        var road=new VectorTile.Feature("street_labels","residential",2,4096,List.of(),"人民路");
        selection.add(road,List.of(p(-100,50),p(100,50)));selection.add(road,List.of(p(-100,51),p(100,51)));
        selection.add(point,List.of(p(80,80)));selection.add(point,List.of(p(81,80)));
        selection.add(point,List.of(p(3000,0)));
        check(selection.finish().size()==2,"Tile duplicates and distant POIs removed");
        check(selection.finish().stream().filter(v->v.kind()==0).findFirst().orElseThrow().point().equals(p(0,50)),"Street anchor stays on the actual polyline");
        for(int i=0;i<300;i++)selection.add(new VectorTile.Feature("pois","parking",1,4096,List.of(),"停车场"+i),List.of(p(i%20*30-300,i/20*30+80)));
        check(selection.finish().size()==Basemap.MAX_LABELS,"Dense selection stays bounded");
        var labels=List.of(new Basemap.Label(0,p(-120,100),"中山路"),new Basemap.Label(1,p(100,200),"东城区"),
                new Basemap.Label(3,p(150,110),"加油站"),new Basemap.Label(4,p(-140,20),"停车场"),
                new Basemap.Label(6,p(160,250),"人民医院"));
        var geometry=List.of(new Basemap.Primitive(0,p(0,0),p(0,400),p(0,0)));
        var scene=new Basemap.Scene(1,1,true,geometry,labels);
        var route=new DeviceRoute.Frame(1,List.of(p(0,0),p(0,400)),0,0,0);
        var wire=new RouteProtocol(123);var frames=new ArrayList<RouteProtocol.Command>();
        frames.add(wire.helloWithLabels());
        frames.add(wire.snapshot(NavigationFrame.status(1),null,100000));
        frames.add(wire.focusWithPosition(new Basemap.Focus(route,origin,1,5000,600,new Basemap.Position(1,42,100000)),100000));
        frames.add(wire.basemap(scene,0,true));frames.add(wire.labels(scene,0));frames.add(wire.labels(scene,4));
        var replacement=new Basemap.Scene(1,2,true,geometry,List.of());
        frames.add(wire.basemap(replacement,0,true));frames.add(wire.labels(replacement,0));
        frames.add(wire.snapshot(NavigationFrame.status(4),null,101000));
        var lines=new ArrayList<String>();
        for(var frame:frames){check(frame.bytes().length<=RouteProtocol.MAX_BYTES,"Bounded label frames");lines.add(HexFormat.of().formatHex(frame.bytes()));}
        if(args.length>0)Files.writeString(Path.of(args[0]),String.join("\n",lines)+"\n");
        String longName="中".repeat(100)+"\u202e";
        check(new Basemap.Label(2,p(0,0),longName).name().getBytes(StandardCharsets.UTF_8).length==63,"UTF-8 truncation stays on character boundaries");
        var longest=Collections.nCopies(4,new Basemap.Label(2,p(0,0),longName));
        check(new RouteProtocol(123).labels(new Basemap.Scene(1,1,true,List.of(),longest),0).bytes().length==304,"Four maximum-length names fit the unchanged frame limit");
        if(args.length>1){
            var actual=new LocalMapBuilder(Basemap.coordinate(tile,4096,800,3000),c->c);
            actual.add(tile,VectorTile.decode(Files.readAllBytes(Path.of(args[1]))));
            var result=actual.finish(1);check(!result.labels().isEmpty(),"Actual public MVT contains selected names");
            System.out.println("Actual tile: "+result.items().size()+" primitives, "+result.labels().size()+" names");
            for(var label:result.labels())System.out.println(label.kind()+" "+label.name()+" "+label.point());
        }
        System.out.println("PASS: "+checks+" map name, POI, selection and wire checks");
    }
}
