package com.motonav.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Converts real provider geometry into a small scene; never synthesizes streets. */
public final class LocalMapBuilder {
    private final DeviceRoute.Coordinate origin;
    private final Basemap.Coordinates conversion;
    private final double longitudeScale;
    private record Group(List<Basemap.Primitive> items,double score,int cell) { }
    private final PriorityQueue<Group> lines=new PriorityQueue<>(Comparator.comparingDouble(Group::score).reversed()),
            areas=new PriorityQueue<>(Comparator.comparingDouble(Group::score).reversed());
    private double focusX,focusY;
    public LocalMapBuilder(DeviceRoute.Coordinate origin,Basemap.Coordinates conversion) {
        this.origin=origin;this.conversion=conversion;longitudeScale=111319.49079327358*Math.cos(Math.toRadians(origin.latitude()));
    }
    public LocalMapBuilder(Basemap.Focus focus,Basemap.Coordinates conversion) {
        this(focus.origin(),conversion);
        double heading=Math.toRadians(Math.max(0,focus.route().heading())/10.0);
        focusX=focus.route().x()+Math.sin(heading)*80;focusY=focus.route().y()+Math.cos(heading)*80;
    }
    private void keep(List<Basemap.Primitive> items,boolean road) {
        if(items.isEmpty()||items.size()>96)return;
        int left=2048,right=-2048,bottom=2048,top=-2048;
        double score=Double.POSITIVE_INFINITY;
        for(var item:items) {
            for(var p:road?List.of(item.a(),item.b()):List.of(item.a(),item.b(),item.c())) {
                left=Math.min(left,p.x());right=Math.max(right,p.x());bottom=Math.min(bottom,p.y());top=Math.max(top,p.y());
            }
            if(road)score=Math.min(score,distance(item,focusX,focusY));
        }
        if(!road) {
            double dx=Math.max(Math.max(left-focusX,focusX-right),0),dy=Math.max(Math.max(bottom-focusY,focusY-top),0);
            score=dx*dx+dy*dy;
        }
        if(score>420*420)return;
        int kind=items.get(0).kind();if(road)score*=kind==2?.3:kind==1?.6:1;
        int cellX=Math.max(-2,Math.min(2,(int)Math.floor(((left+right)*.5-focusX)/120)));
        int cellY=Math.max(-2,Math.min(2,(int)Math.floor(((bottom+top)*.5-focusY)/120)));
        var queue=road?lines:areas;queue.add(new Group(List.copyOf(items),score,(cellY+2)*5+cellX+2));
        if(queue.size()>256)queue.poll();
    }
    public void add(Basemap.Tile tile,List<VectorTile.Feature> features) {
        for(var feature:features) {
            int kind=kind(feature);if(kind<0)continue;
            for(int ring=0;ring<feature.paths().size();ring++) {
                var path=feature.paths().get(ring);
                // MVT may combine thousands of separate buildings in one
                // feature. Skip only an outer ring with holes, not its peers.
                if(feature.type()==3&&(tileArea(path)<=0||ring+1<feature.paths().size()&&tileArea(feature.paths().get(ring+1))<0))continue;
                if(path.size()<2||!near(tile,feature.extent(),path))continue;
                if(kind<3) {
                    if(path.size()>4096)continue;
                    List<DeviceRoute.Point> road=new ArrayList<>();for(var p:path)road.add(point(tile,feature.extent(),p));
                    road=DeviceRoute.simplify(road,2);
                    DeviceRoute.Point previous=road.get(0);
                    List<Basemap.Primitive> group=new ArrayList<>();
                    for(int i=1;i<road.size();i++) {
                        var next=road.get(i);
                        var clipped=clip(previous,next);
                        if(clipped!=null)group.add(new Basemap.Primitive(kind,clipped[0],clipped[1],new DeviceRoute.Point(0,0)));
                        previous=next;
                    }
                    keep(group,true);
                } else if(path.size()<=4096) {
                    List<DeviceRoute.Point> points=new ArrayList<>();
                    for(var p:path) {
                        var q=point(tile,feature.extent(),p);
                        if(points.isEmpty()||!points.get(points.size()-1).equals(q))points.add(q);
                    }
                    if(points.size()>1&&points.get(0).equals(points.get(points.size()-1)))points.remove(points.size()-1);
                    if(points.size()>24) {
                        points.add(points.get(0));
                        List<DeviceRoute.Point> reduced=DeviceRoute.simplify(points,2);
                        if(reduced.size()>65)reduced=DeviceRoute.simplify(points,4);
                        if(reduced.size()>65)reduced=DeviceRoute.simplify(points,8);
                        if(reduced.size()>65)continue;
                        points=new ArrayList<>(reduced);points.remove(points.size()-1);
                    }
                    // Only bounded polygons can enter the radio scene. Large
                    // water polygons are triangulated then clipped, not cropped to a rectangle.
                    List<Basemap.Primitive> group=new ArrayList<>();
                    for(var triangle:triangulate(points))addClipped(kind,triangle,group);
                    keep(group,false);
                }
            }
        }
    }
    public Basemap.Scene finish(int revision) {
        return finish(revision,Basemap.MAX_ITEMS);
    }
    public Basemap.Scene finish(int revision,int limit) {
        if(limit<2||limit>Basemap.MAX_ITEMS)throw new IllegalArgumentException("map budget");
        // Retain whole objects, with coverage across cells before spending the
        // remaining detail budget. Never cut a building or street in half to fit.
        List<Basemap.Primitive> selected=new ArrayList<>();
        select(lines,selected,limit/2);select(areas,selected,limit);
        return new Basemap.Scene(revision,selected);
    }
    private static void select(PriorityQueue<Group> source,List<Basemap.Primitive> output,int limit) {
        List<Group> candidates=new ArrayList<>(source);candidates.sort(Comparator.comparingDouble(Group::score));
        boolean[] cells=new boolean[25];java.util.Set<Group> chosen=new java.util.HashSet<>();
        for(int pass=0;pass<2;pass++)for(var group:candidates) {
            if(chosen.contains(group)||pass==0&&cells[group.cell()]||output.size()+group.items().size()>limit)continue;
            output.addAll(group.items());chosen.add(group);cells[group.cell()]=true;
        }
    }
    private static double distance(Basemap.Primitive p,double focusX,double focusY) {
        // Prefer nearby geometry within independent road and surface budgets.
        double x=(p.a().x()+p.b().x()+(p.kind()>=3?p.c().x():0))/(p.kind()>=3?3.0:2.0);
        double y=(p.a().y()+p.b().y()+(p.kind()>=3?p.c().y():0))/(p.kind()>=3?3.0:2.0);
        if(p.kind()<3) {
            double dx=p.b().x()-p.a().x(),dy=p.b().y()-p.a().y(),length=dx*dx+dy*dy;
            double t=length==0?0:Math.max(0,Math.min(1,-((p.a().x()-focusX)*dx+(p.a().y()-focusY)*dy)/length));
            x=p.a().x()+t*dx;y=p.a().y()+t*dy;
        }
        x-=focusX;y-=focusY;
        return x*x+y*y;
    }
    private static int kind(VectorTile.Feature f) {
        return switch(f.layer()) {
            case "streets" -> switch(f.kind()) {
                case "motorway","trunk" -> 2;
                case "primary","secondary","tertiary","primary_link","secondary_link","tertiary_link","motorway_link","trunk_link" -> 1;
                case "residential","unclassified","living_street","service","pedestrian","road","track" -> 0;
                default -> -1;
            };
            case "buildings" -> 3;
            case "water_polygons" -> 4;
            case "land","sites" -> List.of("park","forest","wood","grass","garden","recreation_ground","scrub","meadow").contains(f.kind())?5:-1;
            default -> -1;
        };
    }
    private boolean near(Basemap.Tile tile,int extent,List<VectorTile.Point> path) {
        int x0=Integer.MAX_VALUE,y0=x0,x1=Integer.MIN_VALUE,y1=x1;
        for(var p:path){x0=Math.min(x0,p.x());x1=Math.max(x1,p.x());y0=Math.min(y0,p.y());y1=Math.max(y1,p.y());}
        var a=conversion.toMap(Basemap.coordinate(tile,extent,x0,y0));
        var b=conversion.toMap(Basemap.coordinate(tile,extent,x1,y1));
        double left=(a.longitude()-origin.longitude())*longitudeScale,right=(b.longitude()-origin.longitude())*longitudeScale;
        double top=(a.latitude()-origin.latitude())*111319.49079327358,bottom=(b.latitude()-origin.latitude())*111319.49079327358;
        return left<=900&&right>=-900&&bottom<=900&&top>=-900;
    }
    private DeviceRoute.Point point(Basemap.Tile tile,int extent,VectorTile.Point p) {
        var coordinate=conversion.toMap(Basemap.coordinate(tile,extent,p.x(),p.y()));
        double x=(coordinate.longitude()-origin.longitude())*longitudeScale,y=(coordinate.latitude()-origin.latitude())*111319.49079327358;
        if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>100000||Math.abs(y)>100000)throw new IllegalArgumentException("projected geometry");
        return new DeviceRoute.Point((int)Math.round(x),(int)Math.round(y));
    }
    private static double tileArea(List<VectorTile.Point> points) {
        double area=0;for(int i=0;i<points.size();i++){var a=points.get(i);var b=points.get((i+1)%points.size());area+=(double)a.x()*b.y()-(double)b.x()*a.y();}return area;
    }
    private static double cross(DeviceRoute.Point a,DeviceRoute.Point b,DeviceRoute.Point c){return (double)(b.x()-a.x())*(c.y()-a.y())-(double)(b.y()-a.y())*(c.x()-a.x());}
    static List<List<DeviceRoute.Point>> triangulate(List<DeviceRoute.Point> input) {
        List<List<DeviceRoute.Point>> triangles=new ArrayList<>();ArrayList<DeviceRoute.Point> p=new ArrayList<>(input);
        if(p.size()<3)return triangles;
        double area=0;for(int i=0;i<p.size();i++){var a=p.get(i);var b=p.get((i+1)%p.size());area+=(double)a.x()*b.y()-(double)b.x()*a.y();}
        if(area==0)return triangles;if(area<0)java.util.Collections.reverse(p);
        while(p.size()>3) {
            boolean cut=false;
            for(int i=0;i<p.size();i++) {
                var a=p.get((i+p.size()-1)%p.size());var b=p.get(i);var c=p.get((i+1)%p.size());double turn=cross(a,b,c);
                if(turn==0){p.remove(i);cut=true;break;}if(turn<0)continue;
                boolean inside=false;
                for(var v:p)if(v!=a&&v!=b&&v!=c&&cross(a,b,v)>=0&&cross(b,c,v)>=0&&cross(c,a,v)>=0){inside=true;break;}
                if(!inside){triangles.add(List.of(a,b,c));p.remove(i);cut=true;break;}
            }
            if(!cut)return List.of(); // malformed/self-intersecting polygon
        }
        if(p.size()==3&&cross(p.get(0),p.get(1),p.get(2))!=0)triangles.add(List.copyOf(p));return triangles;
    }
    private void addClipped(int kind,List<DeviceRoute.Point> triangle,List<Basemap.Primitive> group) {
        List<DeviceRoute.Point> p=triangle;
        for(int side=0;side<4;side++) {
            List<DeviceRoute.Point> next=new ArrayList<>();
            if(p.isEmpty())return;
            var a=p.get(p.size()-1);boolean aIn=inside(a,side);
            for(var b:p){boolean bIn=inside(b,side);if(aIn!=bIn)next.add(intersection(a,b,side));if(bIn)next.add(b);a=b;aIn=bIn;}p=next;
        }
        for(int i=1;i+1<p.size();i++)if(cross(p.get(0),p.get(i),p.get(i+1))!=0)group.add(new Basemap.Primitive(kind,p.get(0),p.get(i),p.get(i+1)));
    }
    private static boolean inside(DeviceRoute.Point p,int side){return switch(side){case 0->p.x()>=-900;case 1->p.x()<=900;case 2->p.y()>=-900;default->p.y()<=900;};}
    private static DeviceRoute.Point intersection(DeviceRoute.Point a,DeviceRoute.Point b,int side) {
        double boundary=(side&1)==0?-900:900;
        double t=side<2?(boundary-a.x())/(b.x()-a.x()):(boundary-a.y())/(b.y()-a.y());
        return new DeviceRoute.Point((int)Math.round(a.x()+(b.x()-a.x())*t),(int)Math.round(a.y()+(b.y()-a.y())*t));
    }
    private static DeviceRoute.Point[] clip(DeviceRoute.Point a,DeviceRoute.Point b) {
        double dx=b.x()-a.x(),dy=b.y()-a.y(),lo=0,hi=1;
        double[] p={-dx,dx,-dy,dy},q={a.x()+900,900-a.x(),a.y()+900,900-a.y()};
        for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return null;continue;}double t=q[i]/p[i];if(p[i]<0){if(t>hi)return null;lo=Math.max(lo,t);}else{if(t<lo)return null;hi=Math.min(hi,t);}}
        var x=new DeviceRoute.Point((int)Math.round(a.x()+lo*dx),(int)Math.round(a.y()+lo*dy));
        var y=new DeviceRoute.Point((int)Math.round(a.x()+hi*dx),(int)Math.round(a.y()+hi*dy));
        return x.equals(y)?null:new DeviceRoute.Point[]{x,y};
    }
}
