package com.motonav.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Even-odd trapezoid tessellation for polygon interiors, including courtyards. */
final class MapPolygon {
    private record Edge(DeviceRoute.Point a,DeviceRoute.Point b) {
        double x(double y){return a.x()+(y-a.y())*(b.x()-a.x())/(double)(b.y()-a.y());}
    }
    static List<List<DeviceRoute.Point>> withHoles(List<List<DeviceRoute.Point>> rings) {
        int count=rings.stream().mapToInt(List::size).sum();
        if(count>4096||rings.isEmpty()||rings.size()>128)return List.of();
        List<Edge> edges=new ArrayList<>();TreeSet<Integer> levels=new TreeSet<>();
        for(var ring:rings) {
            if(ring.size()<3)return List.of();
            for(int i=0;i<ring.size();i++) {
                var a=ring.get(i);var b=ring.get((i+1)%ring.size());levels.add(a.y());
                if(a.y()!=b.y())edges.add(new Edge(a,b));
            }
        }
        List<List<DeviceRoute.Point>> result=new ArrayList<>();Integer previous=null;
        for(int y:levels) {
            if(previous!=null) {
                int y0=previous;double middle=(y0+y)*.5;List<Edge> active=new ArrayList<>();
                for(var edge:edges)if(middle>Math.min(edge.a.y(),edge.b.y())&&middle<Math.max(edge.a.y(),edge.b.y()))active.add(edge);
                active.sort(Comparator.comparingDouble(e->e.x(middle)));
                if(active.size()%2!=0)return List.of();
                for(int i=0;i<active.size();i+=2) {
                    var l=active.get(i);var r=active.get(i+1);
                    var a=new DeviceRoute.Point((int)Math.round(l.x(y0)),y0);var b=new DeviceRoute.Point((int)Math.round(r.x(y0)),y0);
                    var c=new DeviceRoute.Point((int)Math.round(r.x(y)),y);var d=new DeviceRoute.Point((int)Math.round(l.x(y)),y);
                    if(!a.equals(b))result.add(List.of(a,b,c));
                    if(!c.equals(d))result.add(List.of(a,c,d));
                    if(result.size()>4096)return List.of();
                }
            }
            previous=y;
        }
        return result;
    }
}
