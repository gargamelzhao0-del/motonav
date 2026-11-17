package com.motonav.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.LinkedHashSet;

/** Bounded whole-object selection with independent context and road budgets. */
final class MapDetailSelector {
    private record Group(List<Basemap.Primitive> items,double score,int cell,long order) { }
    private static final Comparator<Group> ORDER=Comparator.comparingDouble(Group::score).thenComparingLong(Group::order);
    private final List<PriorityQueue<Group>> groups=new ArrayList<>();
    private final MapViewport view;
    private long order;
    private int candidateItems,oversizedGroups,duplicateItems;
    private final java.util.Map<List<Basemap.Primitive>,Group> retained=new java.util.HashMap<>();
    int candidateItems(){return candidateItems;}
    int oversizedGroups(){return oversizedGroups;}
    int duplicateItems(){return duplicateItems;}
    MapDetailSelector(MapViewport view){this.view=view;for(int i=0;i<5;i++)groups.add(new PriorityQueue<>(ORDER.reversed()));}
    void add(List<Basemap.Primitive> items) {
        add(items,1);
    }
    void add(List<Basemap.Primitive> items,double priority) {
        if(items.isEmpty())return;
        // Canonicalize direction/winding only; no coordinate rounding or
        // geometry substitution. Tile buffers often contain identical objects.
        Set<Basemap.Primitive> unique=new LinkedHashSet<>();
        for(var item:items)unique.add(canonical(item));
        duplicateItems+=items.size()-unique.size();
        items=new ArrayList<>(unique);items.sort(PRIMITIVE_ORDER);
        if(items.size()>Basemap.MAX_ITEMS){oversizedGroups++;return;}
        int kind=items.get(0).kind(),bucket=kind<3?0:kind==3?1:kind==4||kind==8?2:kind==5?3:4;
        double l=Double.POSITIVE_INFINITY,r=-l,b=l,t=-l;
        for(var item:items)for(var p:Basemap.lineKind(item.kind())?List.of(item.a(),item.b()):List.of(item.a(),item.b(),item.c())) {
            var q=view.project(p.x(),p.y());l=Math.min(l,q.x());r=Math.max(r,q.x());b=Math.min(b,q.y());t=Math.max(t,q.y());
        }
        // Keep offscreen neighbors for short camera movement; score and cells
        // are in screen space so far visible buildings are not all discarded.
        if(r< -100||l>566||t<30||b>566)return;
        double area=Math.max(0,Math.min(r,490)-Math.max(l,-24))*Math.max(0,Math.min(t,490)-Math.max(b,64));
        if(!Basemap.lineKind(kind)&&area<2)return;
        double cx=Math.max(0,Math.min(465,(l+r)*.5)),cy=Math.max(0,Math.min(465,(b+t)*.5));
        double dx=Math.max(Math.max(l-233,233-r),0),dy=Math.max(Math.max(b-260,260-t),0);
        double score=(dx*dx+dy*dy+100)/Math.sqrt(Math.min(area,4096)+64);
        if(kind<3)score*=kind==2?.45:kind==1?.7:1;
        score*=priority;
        int cell=(int)(cy/94)*5+(int)(cx/94);
        var queue=groups.get(bucket);var previous=retained.get(items);
        if(previous!=null){
            duplicateItems+=items.size();if(previous.score()<=score)return;queue.remove(previous);
        }else candidateItems+=items.size();
        var group=new Group(List.copyOf(items),score,cell,order++);retained.put(group.items(),group);queue.add(group);
        if(queue.size()>(bucket<2?256:64))retained.remove(queue.poll().items());
    }
    List<Basemap.Primitive> finish(int limit) {
        List<Basemap.Primitive> output=new ArrayList<>();Set<Group> chosen=new HashSet<>();
        // Background context cannot be crowded out by hundreds of buildings.
        select(4,output,chosen,limit/12,limit/6);
        select(2,output,chosen,output.size()+limit/8,output.size()+limit/4);
        select(3,output,chosen,output.size()+limit/12,0);
        int context=output.size();select(0,output,chosen,Math.min(limit,context+limit*3/8),0);
        select(1,output,chosen,limit,0);
        // Spare capacity is reusable; never split a polygon merely to fill it.
        select(0,output,chosen,limit,0);select(3,output,chosen,limit,0);select(2,output,chosen,limit,0);select(4,output,chosen,limit,0);
        return output;
    }
    private void select(int bucket,List<Basemap.Primitive> out,Set<Group> chosen,int limit,int firstAllowance) {
        var candidates=new ArrayList<>(groups.get(bucket));candidates.sort(ORDER);
        var existing=new HashSet<>(out);
        boolean[] cells=new boolean[25];boolean added=false;
        for(int pass=0;pass<2;pass++)for(var group:candidates) {
            if(chosen.contains(group)||pass==0&&cells[group.cell()])continue;
            int ceiling=!added?Math.max(limit,firstAllowance):limit;
            // Shared buffered primitives already emitted by another complete
            // object do not consume the radio budget a second time.
            int needed=0;for(var item:group.items())if(!existing.contains(item))needed++;
            if(out.size()+needed>ceiling)continue;
            for(var item:group.items())if(existing.add(item))out.add(item);
            chosen.add(group);cells[group.cell()]=true;added=true;
        }
    }
    private static final Comparator<DeviceRoute.Point> POINT_ORDER=Comparator.comparingInt(DeviceRoute.Point::x).thenComparingInt(DeviceRoute.Point::y);
    private static final Comparator<Basemap.Primitive> PRIMITIVE_ORDER=Comparator.comparingInt(Basemap.Primitive::kind)
            .thenComparing(Basemap.Primitive::a,POINT_ORDER).thenComparing(Basemap.Primitive::b,POINT_ORDER).thenComparing(Basemap.Primitive::c,POINT_ORDER);
    private static Basemap.Primitive canonical(Basemap.Primitive item){
        var points=new ArrayList<>(Basemap.lineKind(item.kind())?List.of(item.a(),item.b()):List.of(item.a(),item.b(),item.c()));
        points.sort(POINT_ORDER);
        return new Basemap.Primitive(item.kind(),points.get(0),points.get(1),Basemap.lineKind(item.kind())?new DeviceRoute.Point(0,0):points.get(2));
    }
}
