package com.motonav.companion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded reader for public MVT geometry; no rendering, Android or networking dependencies. */
public final class VectorTile {
    private VectorTile() { }
    public record Point(int x,int y) { }
    public record Feature(String layer,String kind,int type,int extent,List<List<Point>> paths,String name) {
        public Feature(String layer,String kind,int type,int extent,List<List<Point>> paths){this(layer,kind,type,extent,paths,"");}
    }
    public static List<Feature> decode(byte[] bytes) {
        if(bytes.length>4*1024*1024)throw new IllegalArgumentException("tile size");
        Reader tile=new Reader(bytes,0,bytes.length);List<Feature> result=new ArrayList<>();int[] budget={250000};
        while(tile.more()) { int tag=tile.tag();if(tag==26)layer(tile.message(),result,budget);else tile.skip(tag); }
        return List.copyOf(result);
    }
    private static void layer(Reader reader,List<Feature> output,int[] budget) {
        String name="";int extent=4096,version=0;List<String> keys=new ArrayList<>(),values=new ArrayList<>();List<Reader> features=new ArrayList<>();
        while(reader.more()) {
            int tag=reader.tag();
            switch(tag) {
                case 10 -> name=reader.string();
                case 18 -> {if(features.size()>=20000)throw new IllegalArgumentException("feature limit");features.add(reader.message());}
                case 26 -> {if(keys.size()>=8192)throw new IllegalArgumentException("key limit");keys.add(reader.string());}
                case 34 -> {if(values.size()>=65536)throw new IllegalArgumentException("value limit");Reader value=reader.message();String text="";
                    while(value.more()){int t=value.tag();if(t==10)text=value.string();else value.skip(t);}values.add(text);}
                case 40 -> extent=reader.integer();
                case 120 -> version=reader.integer();
                default -> reader.skip(tag);
            }
        }
        if(!List.of("streets","buildings","water_polygons","land","sites","street_labels",
                "place_labels","pois","public_transport","water_polygons_labels","water_lines_labels").contains(name))return;
        if(extent<256||extent>65536||version!=2)throw new IllegalArgumentException("tile schema");
        for(Reader feature:features) {
            int type=0;Reader tags=null,geometry=null;
            while(feature.more()) {
                int tag=feature.tag();
                if(tag==18)tags=feature.message();else if(tag==24)type=feature.integer();else if(tag==34)geometry=feature.message();else feature.skip(tag);
            }
            String kind="",localName="",englishName="",chineseName="",reference="",category="";
            if(tags!=null)while(tags.more()) {
                int key=tags.integer(),value=tags.integer();
                if(key>=keys.size()||value>=values.size())throw new IllegalArgumentException("tag index");
                String text=values.get(value);
                switch(keys.get(key)) {
                    case "kind" -> kind=text;
                    case "name" -> localName=text;
                    case "name_zh","name:zh" -> chineseName=text;
                    case "name_en" -> englishName=text;
                    case "ref" -> reference=text;
                    case "amenity","leisure","tourism","shop","historic","man_made" -> {
                        if(category.isEmpty())category=text;
                    }
                    default -> { }
                }
            }
            if(kind.isEmpty())kind=category;
            String label=!chineseName.isBlank()?chineseName:!localName.isBlank()?localName:
                    !englishName.isBlank()?englishName:reference;
            if(geometry==null||(type!=1&&type!=2&&type!=3))continue;
            List<List<Point>> paths=paths(geometry,type,extent,budget);
            if(!paths.isEmpty())output.add(new Feature(name,kind,type,extent,paths,NavigationDetails.clean(label)));
            if(output.size()>20000)throw new IllegalArgumentException("feature limit");
        }
    }
    private static List<List<Point>> paths(Reader data,int type,int extent,int[] budget) {
        List<List<Point>> paths=new ArrayList<>();ArrayList<Point> path=null;int x=0,y=0,points=0;boolean closed=false;
        while(data.more()) {
            int command=data.integer(),id=command&7,count=command>>>3;
            if(count==0||count>65536)throw new IllegalArgumentException("geometry command");
            if(id==7) {
                if(type!=3||count!=1||path==null||path.size()<3||closed)throw new IllegalArgumentException("close path");
                closed=true;continue;
            }
            if(id!=1&&id!=2 || id==1&&count!=1&&type!=1 || id==2&&(type==1||path==null||closed))throw new IllegalArgumentException("geometry order");
            for(int i=0;i<count;i++) {
                if(++points>200000||--budget[0]<0)throw new IllegalArgumentException("geometry limit");
                int dx=data.integer(),dy=data.integer();
                x=Math.addExact(x,(dx>>>1)^-(dx&1));y=Math.addExact(y,(dy>>>1)^-(dy&1));
                if(Math.abs((long)x)>extent*256L||Math.abs((long)y)>extent*256L)throw new IllegalArgumentException("geometry coordinate");
                if(id==1) {
                    if(type==3&&path!=null&&!closed)throw new IllegalArgumentException("open polygon");
                    path=new ArrayList<>();paths.add(path);closed=false;
                }
                path.add(new Point(x,y));
            }
        }
        if(type==3&&path!=null&&!closed)throw new IllegalArgumentException("open polygon");
        return paths;
    }
    private static final class Reader {
        final byte[] data;final int end;int at;
        Reader(byte[] data,int at,int end){this.data=data;this.at=at;this.end=end;}
        boolean more(){return at<end;}
        long varint(){long n=0;for(int i=0;i<10;i++){if(!more())throw new IllegalArgumentException("truncated varint");int b=data[at++]&255;if(i==9&&(b&254)!=0)throw new IllegalArgumentException("varint overflow");n|=(long)(b&127)<<(7*i);if((b&128)==0)return n;}throw new IllegalArgumentException("varint overflow");}
        int integer(){long n=varint();if(n<0||n>Integer.MAX_VALUE)throw new IllegalArgumentException("integer overflow");return (int)n;}
        int tag(){int t=integer();if((t>>>3)==0)throw new IllegalArgumentException("zero field");return t;}
        Reader message(){int size=integer();if(size>end-at)throw new IllegalArgumentException("truncated message");Reader r=new Reader(data,at,at+size);at+=size;return r;}
        String string(){Reader r=message();if(r.end-r.at>16384)throw new IllegalArgumentException("string size");return new String(data,r.at,r.end-r.at,StandardCharsets.UTF_8);}
        void skip(int tag){switch(tag&7){case 0->varint();case 1->advance(8);case 2->{Reader ignored=message();}case 5->advance(4);default->throw new IllegalArgumentException("wire type");}}
        void advance(int n){if(n>end-at)throw new IllegalArgumentException("truncated field");at+=n;}
    }
}
