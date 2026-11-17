package com.motonav.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded whole-object selection with independent context and road budgets. */
final class MapDetailSelector {
    private record Group(List<Basemap.Primitive> items,double score,int cell,long order) { }
    private static final Comparator<Group> ORDER=Comparator.comparingDouble(Group::score).thenComparingLong(Group::order);
    private final List<PriorityQueue<Group>> groups=new ArrayList<>();
    private final MapViewport view;
    private long order;
    MapDetailSelector(MapViewport view){this.view=view;for(int i=0;i<4;i++)groups.add(new PriorityQueue<>(ORDER.reversed()));}
    void add(List<Basemap.Primitive> items) {
        if(items.isEmpty()||items.size()>96)return;
        int kind=items.get(0).kind(),bucket=kind<3?0:kind==3?1:kind==4?2:3;
        double l=Double.POSITIVE_INFINITY,r=-l,b=l,t=-l;
        for(var item:items)for(var p:item.kind()<3?List.of(item.a(),item.b()):List.of(item.a(),item.b(),item.c())) {
            var q=view.project(p.x(),p.y());l=Math.min(l,q.x());r=Math.max(r,q.x());b=Math.min(b,q.y());t=Math.max(t,q.y());
        }
        // Keep offscreen neighbors for short camera movement; score and cells
        // are in screen space so far visible buildings are not all discarded.
        if(r< -100||l>566||t<30||b>566)return;
        double area=Math.max(0,Math.min(r,490)-Math.max(l,-24))*Math.max(0,Math.min(t,490)-Math.max(b,64));
        if(kind>=3&&area<2)return;
        double cx=Math.max(0,Math.min(465,(l+r)*.5)),cy=Math.max(0,Math.min(465,(b+t)*.5));
        double dx=Math.max(Math.max(l-233,233-r),0),dy=Math.max(Math.max(b-260,260-t),0);
        double score=(dx*dx+dy*dy+100)/Math.sqrt(Math.min(area,4096)+64);
        if(kind<3)score*=kind==2?.45:kind==1?.7:1;
        int cell=(int)(cy/94)*5+(int)(cx/94);
        var queue=groups.get(bucket);queue.add(new Group(List.copyOf(items),score,cell,order++));
        if(queue.size()>(bucket<2?256:64))queue.poll();
    }
    List<Basemap.Primitive> finish(int limit) {
        List<Basemap.Primitive> output=new ArrayList<>();Set<Group> chosen=new HashSet<>();
        // Background context cannot be crowded out by hundreds of buildings.
        select(2,output,chosen,limit/8,limit/4);
        select(3,output,chosen,output.size()+limit/12,0);
        int context=output.size();select(0,output,chosen,Math.min(limit,context+limit*3/8),0);
        select(1,output,chosen,limit,0);
        // Spare capacity is reusable; never split a polygon merely to fill it.
        select(0,output,chosen,limit,0);select(3,output,chosen,limit,0);select(2,output,chosen,limit,0);
        return output;
    }
    private void select(int bucket,List<Basemap.Primitive> out,Set<Group> chosen,int limit,int firstAllowance) {
        var candidates=new ArrayList<>(groups.get(bucket));candidates.sort(ORDER);
        boolean[] cells=new boolean[25];boolean added=false;
        for(int pass=0;pass<2;pass++)for(var group:candidates) {
            if(chosen.contains(group)||pass==0&&cells[group.cell()])continue;
            int ceiling=!added?Math.max(limit,firstAllowance):limit;
            if(out.size()+group.items().size()>ceiling)continue;
            out.addAll(group.items());chosen.add(group);cells[group.cell()]=true;added=true;
        }
    }
}
