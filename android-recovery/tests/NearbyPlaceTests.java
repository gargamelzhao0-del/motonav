package com.motonav.companion;

import java.nio.file.*;
import java.util.*;

public final class NearbyPlaceTests {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final DeviceRoute.Coordinate ORIGIN=new DeviceRoute.Coordinate(30.54,104.10);
    static NearbyPlace place(String id,String type,int x,int y){
        return new NearbyPlace(id,"测试名称"+id,type,new DeviceRoute.Coordinate(ORIGIN.latitude()+y/111319.49079327358,
            ORIGIN.longitude()+x/(111319.49079327358*Math.cos(Math.toRadians(ORIGIN.latitude())))));
    }
    public static void main(String[] args)throws Exception {
        var estate=place("estate","120302",-90,160);
        check(estate.label(ORIGIN).kind()==1&&estate.label(ORIGIN).point().equals(new DeviceRoute.Point(-90,160)),"SDK GCJ coordinates project once, without a second datum conversion");
        check(place("school","141201",90,140).label(ORIGIN).kind()==2,"School is a named landmark");
        check(place("station","150500",20,80).label(ORIGIN).kind()==7,"Station retains transit symbol");
        check(place("parking","150900",20,80).label(ORIGIN).kind()==4,"Parking does not become a train station");
        check(place("far","12",3000,0).label(ORIGIN)==null,"Out-of-origin bounds are rejected");
        var state=new NearbyPlacesState();check(state.shouldRequest(ORIGIN,0),"First navigation can query immediately");
        state.started(0);state.accept(ORIGIN,List.of(estate,estate,place("far","12",2000,0)),3,1000);
        check(state.visible(ORIGIN,1000).size()==1,"Deduplicate and range-check actual SDK results");
        check(!state.shouldRequest(ORIGIN,60000),"Stationary successful lookup is not repeated every heartbeat");
        var moved=place("center","12",400,0).coordinate();
        check(!state.shouldRequest(moved,59000)&&state.shouldRequest(moved,60000),"Movement refresh respects global 60s request bound");
        state.accept(ORIGIN,List.of(),0,61000);check(state.visible(ORIGIN,61000).size()==1,"A network failure retains usable names");
        state.started(60000);state.accept(ORIGIN,List.of(place("school","14",80,150)),1,61000);
        check(state.visible(ORIGIN,61000).size()==2&&state.shouldRequest(ORIGIN,120000),"Partial retry retains earlier names and retries later");
        check(state.visible(ORIGIN,901001).size()==1,"Partial success cannot extend the lifetime of older results");
        check(state.visible(moved,61000).size()==2&&state.visible(place("remote","12",1400,0).coordinate(),61000).isEmpty(),"Catalog does not follow the user into a different area");
        check(state.visible(ORIGIN,1000000).isEmpty(),"Old names expire");
        var dense=new ArrayList<NearbyPlace>();for(int i=0;i<100;i++)dense.add(place("d"+i,"12",i,150));
        state.accept(ORIGIN,dense,3,1000001);check(state.visible(ORIGIN,1000001).size()==75,"Bound memory independently of SDK result size");
        state.clear();check(state.visible(ORIGIN,1000002).isEmpty(),"STOP clears session catalog");
        var points=List.of(new DeviceRoute.Point(0,-100),new DeviceRoute.Point(0,400));
        var frame=new DeviceRoute.Frame(1,points,0,0,0);
        var focus=new Basemap.Focus(frame,ORIGIN,1,5000,600);
        check(NearbyPlace.distance(NearbyPlace.center(focus),ORIGIN)<.01,"Query follows local focus in GCJ-02");
        var geometry=List.of(new Basemap.Primitive(0,points.get(0),points.get(1),new DeviceRoute.Point(0,0)));
        var original=new Basemap.Label(0,new DeviceRoute.Point(-120,100),"测试道路");
        var base=new Basemap.Scene(1,8,true,geometry,List.of(original));
        var compose=new MapSceneComposer();var waiting=NavigationFrame.status(1);
        var old=compose.compose(base,focus,waiting,List.of(estate),false);
        check(old.labelSources()==1&&old.labels().equals(base.labels()),"Old receiver never receives unattributed SDK names");
        var mixed=compose.compose(base,focus,waiting,List.of(estate),true);
        check(mixed.update()>old.update()&&mixed.items().equals(geometry)&&mixed.labels().contains(original)
            &&mixed.labels().contains(estate.label(ORIGIN))&&mixed.labelSources()==3&&compose.selectedPlaces()==1,"Independent names augment geometry without erasing road captions");
        check(compose.compose(base,focus,waiting,List.of(estate),true)==mixed,"Heartbeat does not allocate a new radio scene");
        var later=compose.compose(new Basemap.Scene(1,9,true,geometry,List.of()),focus,waiting,List.of(estate),true);
        check(later.update()>mixed.update()&&later.labels().contains(estate.label(ORIGIN)),"Late tile completion cannot remove supplementary names");
        compose.clear();check(compose.compose(base,focus,waiting,List.of(),true).update()>later.update(),"Scene generations remain monotonic across navigation restarts");
        var protocol=new RouteProtocol(123);var names=new ArrayList<Basemap.Label>();
        for(int i=0;i<5;i++)names.add(place("n"+i,"12",i*40-80,120).label(ORIGIN));
        var scene=new Basemap.Scene(1,1,true,geometry,names,3);
        boolean rejected=false;try{protocol.labels(scene,0);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Legacy wire cannot silently drop source attribution");
        if(args.length>0){
            protocol=new RouteProtocol(123);var commands=List.of(protocol.helloWithLabelSources(),
                protocol.snapshot(waiting,null,100000),protocol.focus(focus),protocol.basemap(scene,0,true),
                protocol.labels(scene,0,true),protocol.labels(scene,3,true),protocol.snapshot(NavigationFrame.status(4),null,100001));
            var rows=new ArrayList<String>();for(var c:commands){check(c.bytes().length<=304,"S7 keeps existing receiver frame bound");rows.add(HexFormat.of().formatHex(c.bytes()));}
            Files.write(Path.of(args[0]),rows);
        }
        System.out.println("PASS: "+checks+" nearby names, bounded refresh, coordinate, scene composition and S7 checks");
    }
}
