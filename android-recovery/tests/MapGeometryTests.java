package com.motonav.companion;

import java.util.List;
import java.util.ArrayList;

public final class MapGeometryTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static DeviceRoute.Point p(int x,int y){return new DeviceRoute.Point(x,y);}
    private static double area(List<List<DeviceRoute.Point>> triangles){
        double sum=0;
        for(var t:triangles){var a=t.get(0);var b=t.get(1);var c=t.get(2);sum+=Math.abs((double)(b.x()-a.x())*(c.y()-a.y())-(double)(b.y()-a.y())*(c.x()-a.x()))*.5;}
        return sum;
    }
    public static void main(String[] args) {
        var outer=List.of(p(0,0),p(100,0),p(100,100),p(0,100));
        var hole=List.of(p(30,30),p(30,70),p(70,70),p(70,30));
        var triangles=MapPolygon.withHoles(List.of(outer,hole));
        check(area(triangles)==8400,"courtyard area preserved without filling the hole");
        for(var t:triangles){double x=t.stream().mapToInt(DeviceRoute.Point::x).average().orElseThrow(),y=t.stream().mapToInt(DeviceRoute.Point::y).average().orElseThrow();check(!(x>30&&x<70&&y>30&&y<70),"no triangle centroid in courtyard");}
        var small=List.of(p(10,10),p(20,10),p(20,20),p(10,20));
        check(area(MapPolygon.withHoles(List.of(outer,hole,small)))==8300,"multiple holes");
        var reversed=new ArrayList<>(hole);java.util.Collections.reverse(reversed);
        check(area(MapPolygon.withHoles(List.of(outer,reversed)))==8400,"ring orientation independent after MVT grouping");
        var tile=new Basemap.Tile(14,8186,5448);var origin=Basemap.coordinate(tile,4096,2048,2048);
        var exterior=List.of(new VectorTile.Point(2000,2000),new VectorTile.Point(2200,2000),new VectorTile.Point(2200,2200),new VectorTile.Point(2000,2200));
        var interior=List.of(new VectorTile.Point(2040,2040),new VectorTile.Point(2040,2160),new VectorTile.Point(2160,2160),new VectorTile.Point(2160,2040));
        var solid=new LocalMapBuilder(origin,c->c);solid.add(tile,List.of(new VectorTile.Feature("buildings","",3,4096,List.of(exterior))));
        var courtyard=new LocalMapBuilder(origin,c->c);courtyard.add(tile,List.of(new VectorTile.Feature("buildings","",3,4096,List.of(exterior,interior))));
        var solidTriangles=solid.finish(1).items().stream().map(f->List.of(f.a(),f.b(),f.c())).toList();
        var courtyardTriangles=courtyard.finish(1).items().stream().map(f->List.of(f.a(),f.b(),f.c())).toList();
        check(!courtyardTriangles.isEmpty()&&area(courtyardTriangles)<area(solidTriangles)*.8,"real builder retains MVT courtyard shell with empty interior");
        var driving=new MapViewport(0,0,0,true,60);
        check(Math.abs(driving.project(0,712.56578).y()-140)<.01,"camera footprint matches firmware 50 degree projection");
        check(driving.top>1000&&driving.intersects(-10,700,10,720),"far visible buildings survive old 420m cutoff");
        check(new MapViewport(0,0,0,true,60).refreshFrom(new MapViewport(0,0,0,true,20)),"acceleration requests wider coverage");
        check(!new MapViewport(5,0,20,true,61).refreshFrom(driving),"small changes do not continuously reload");
        check(new MapViewport(0,0,0,true,0).refreshFrom(driving),"stopping selects nearby detail");
        for(int speed:new int[]{0,18,36,60,80})for(int heading:new int[]{0,450,900,1350,1800,2700})for(int latitude:new int[]{-80,-30,0,30,51,80}) {
            var v=new MapViewport(200,-150,heading,true,speed);
            check(v.left>=-1900&&v.right<=1900&&v.bottom>=-1900&&v.top<=1900,"radio range bounded");
            check(Basemap.visibleTiles(new DeviceRoute.Coordinate(latitude,179.999),v).size()<=9,"bounded tiles across antimeridian and latitude");
        }
        MapDetailSelector select=new MapDetailSelector(driving);
        for(int i=0;i<400;i++) {
            int x=(i%20-10)*20,y=(i/20)*20;
            select.add(List.of(new Basemap.Primitive(3,p(x,y),p(x+10,y),p(x,y+10))));
        }
        select.add(List.of(new Basemap.Primitive(4,p(150,100),p(300,100),p(200,300))));
        select.add(List.of(new Basemap.Primitive(5,p(-200,200),p(-100,200),p(-100,400))));
        select.add(List.of(new Basemap.Primitive(1,p(0,-100),p(0,900),p(0,0))));
        for(int limit:new int[]{192,384}) {
            var selected=select.finish(limit);
            check(selected.size()<=limit,"progressive and final budgets");
            for(int kind:new int[]{1,3,4,5})check(selected.stream().anyMatch(f->f.kind()==kind),"context and roads survive dense buildings: "+kind);
            check(selected.equals(select.finish(limit)),"deterministic whole-object selection");
        }
        System.out.println("Map geometry: "+checks+" coverage, courtyard and dense-scene checks passed");
    }
}
