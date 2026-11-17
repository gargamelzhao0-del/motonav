package com.motonav.companion;

import java.util.ArrayList;
import java.util.List;

/** Converts real provider geometry into a small scene; never synthesizes streets. */
public final class LocalMapBuilder {
    private final DeviceRoute.Coordinate origin;
    private final Basemap.Coordinates conversion;
    private final double longitudeScale;
    private final MapViewport viewport;
    private final MapDetailSelector selection;
    private final LocalMapLabels labels;
    private final boolean surfaces;
    private int sourceFeatures,sourceRoads,sourceBuildings,sourceLabels,ignoredFeatures,oversizedGroups;
    public LocalMapBuilder(DeviceRoute.Coordinate origin,Basemap.Coordinates conversion) {
        this(origin,conversion,new MapViewport(0,0,0,false,0));
    }
    public LocalMapBuilder(Basemap.Focus focus,Basemap.Coordinates conversion) {
        this(focus.origin(),conversion,new MapViewport(focus.route().x(),focus.route().y(),focus.route().heading(),false,0));
    }
    public LocalMapBuilder(DeviceRoute.Coordinate origin,Basemap.Coordinates conversion,MapViewport viewport) {
        this(origin,conversion,viewport,false);
    }
    public LocalMapBuilder(DeviceRoute.Coordinate origin,Basemap.Coordinates conversion,MapViewport viewport,boolean surfaces) {
        this.origin=origin;this.conversion=conversion;this.viewport=viewport;
        this.surfaces=surfaces;
        longitudeScale=111319.49079327358*Math.cos(Math.toRadians(origin.latitude()));
        selection=new MapDetailSelector(viewport.detailView());
        labels=new LocalMapLabels(viewport.detailView());
    }
    public void add(Basemap.Tile tile,VectorTile.Decoded decoded) {
        int skipped=decoded.sourceFeatures()-decoded.features().size();
        sourceFeatures+=skipped;ignoredFeatures+=skipped;
        add(tile,decoded.features());
    }
    public void add(Basemap.Tile tile,List<VectorTile.Feature> features) {
        for(var feature:features) {
            sourceFeatures++;
            if(feature.layer().equals("streets"))sourceRoads++;
            if(feature.layer().equals("buildings"))for(var ring:feature.paths())if(tileArea(ring)>0)sourceBuildings++;
            if(LocalMapLabels.supported(feature))sourceLabels++;
            if(effectiveKind(feature)<0&&!LocalMapLabels.supported(feature))ignoredFeatures++;
            try{addFeature(tile,feature);}catch(IllegalArgumentException ignored){ignoredFeatures++;}
        }
    }
    private void addFeature(Basemap.Tile tile,VectorTile.Feature feature) {
            if((!feature.name().isEmpty()||feature.layer().equals("pois")||feature.layer().equals("public_transport"))
                    &&(feature.type()==1||feature.type()==2)) {
                for(var path:feature.paths())if(!path.isEmpty()&&path.size()<=4096&&near(tile,feature.extent(),path)) {
                    List<DeviceRoute.Point> points=new ArrayList<>();
                    for(var p:path)points.add(point(tile,feature.extent(),p));
                    labels.add(feature,points);
                }
            }
            int kind=effectiveKind(feature);if(kind<0)return;
            for(int ring=0;ring<feature.paths().size();ring++) {
                var path=feature.paths().get(ring);
                if(feature.type()==3&&tileArea(path)<=0)continue;
                if(path.size()<2||!near(tile,feature.extent(),path))continue;
                if(Basemap.lineKind(kind)) {
                    if(path.size()>4096){oversizedGroups++;continue;}
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
                    // Long streets may extend across many cells. Split only
                    // at existing segment boundaries, never midway through a building.
                    boolean pathContext=kind<3&&List.of("footway","path","cycleway","steps","bridleway").contains(feature.kind());
                    for(int i=0;i<group.size();i+=16)selection.add(group.subList(i,Math.min(i+16,group.size())),pathContext?2.5:1);
                } else if(path.size()<=4096) {
                    List<DeviceRoute.Point> points=ringPoints(tile,feature.extent(),path);
                    List<List<DeviceRoute.Point>> rings=new ArrayList<>();rings.add(points);
                    while(ring+1<feature.paths().size()&&tileArea(feature.paths().get(ring+1))<0)
                        rings.add(ringPoints(tile,feature.extent(),feature.paths().get(++ring)));
                    if(rings.size()==1&&points.size()>24) {
                        points.add(points.get(0));
                        List<DeviceRoute.Point> reduced=DeviceRoute.simplify(points,2);
                        if(reduced.size()>193)reduced=DeviceRoute.simplify(points,4);
                        if(reduced.size()>385)reduced=DeviceRoute.simplify(points,8);
                        if(reduced.size()>385){oversizedGroups++;continue;}
                        points=new ArrayList<>(reduced);points.remove(points.size()-1);
                    }
                    // Even-odd tessellation retains courtyard holes. Clip each
                    // resulting triangle to camera coverage and radio bounds.
                    List<Basemap.Primitive> group=new ArrayList<>();
                    var triangles=rings.size()>1?MapPolygon.withHoles(rings):triangulate(points);
                    if(triangles.isEmpty()){ignoredFeatures++;continue;}
                    for(var triangle:triangles)addClipped(kind,triangle,group);
                    selection.add(group);
                }else oversizedGroups++;
            }
    }
    public MapQuality quality(int required,int loaded,int stale,int failed,Basemap.Scene scene) {
        return new MapQuality(required,loaded,stale,failed,sourceFeatures,sourceRoads,sourceBuildings,sourceLabels,
                ignoredFeatures,selection.candidateItems(),scene.items().size(),selection.oversizedGroups()+oversizedGroups,
                selection.duplicateItems(),labels.candidateCount(),scene.labels().size(),labels.duplicateCount());
    }
    public Basemap.Scene finish(int revision) {
        return finish(revision,Basemap.MAX_ITEMS);
    }
    public Basemap.Scene finish(int revision,int limit) {
        if(limit<2||limit>Basemap.MAX_ITEMS)throw new IllegalArgumentException("map budget");
        return new Basemap.Scene(revision,1,true,selection.finish(limit),labels.finish());
    }
    private static int kind(VectorTile.Feature f) {
        return switch(f.layer()) {
            case "streets" -> switch(f.kind()) {
                case "motorway","trunk" -> 2;
                case "primary","secondary","tertiary","primary_link","secondary_link","tertiary_link","motorway_link","trunk_link" -> 1;
                case "residential","unclassified","living_street","service","pedestrian","road","track",
                     "footway","path","cycleway","steps","bridleway","busway","bus_guideway" -> 0;
                default -> -1;
            };
            case "buildings" -> 3;
            case "ocean","water_polygons" -> 4;
            case "water_lines" -> List.of("river","canal","stream","ditch").contains(f.kind())?8:-1;
            case "land" -> switch(f.kind()) {
                case "park","forest","wood","grass","grassland","garden","recreation_ground","scrub","meadow","heath","fell","village_green",
                     "orchard","vineyard","allotments","plant_nursery","golf_course","farmland","wet_meadow" -> 5;
                case "residential","commercial","retail","industrial","garages","brownfield","railway","farmyard" -> 6;
                case "playground","miniature_golf" -> 7;
                default -> -1;
            };
            case "sites" -> switch(f.kind()) {
                case "school","university","college","hospital","parking","bicycle_parking","motorcycle_parking",
                     "sports_centre","prison","construction" -> 7;
                case "park","garden" -> 5;
                default -> -1;
            };
            default -> -1;
        };
    }
    private int effectiveKind(VectorTile.Feature feature){int value=kind(feature);return !surfaces&&value>5?-1:value;}
    private boolean near(Basemap.Tile tile,int extent,List<VectorTile.Point> path) {
        int x0=Integer.MAX_VALUE,y0=x0,x1=Integer.MIN_VALUE,y1=x1;
        for(var p:path){x0=Math.min(x0,p.x());x1=Math.max(x1,p.x());y0=Math.min(y0,p.y());y1=Math.max(y1,p.y());}
        var a=conversion.toMap(Basemap.coordinate(tile,extent,x0,y0));
        var b=conversion.toMap(Basemap.coordinate(tile,extent,x1,y1));
        double left=(a.longitude()-origin.longitude())*longitudeScale,right=(b.longitude()-origin.longitude())*longitudeScale;
        double top=(a.latitude()-origin.latitude())*111319.49079327358,bottom=(b.latitude()-origin.latitude())*111319.49079327358;
        return viewport.intersects(left,bottom,right,top);
    }
    private List<DeviceRoute.Point> ringPoints(Basemap.Tile tile,int extent,List<VectorTile.Point> path) {
        List<DeviceRoute.Point> points=new ArrayList<>();
        if(path.size()>4096)return points;
        for(var p:path){var q=point(tile,extent,p);if(points.isEmpty()||!points.get(points.size()-1).equals(q))points.add(q);}
        if(points.size()>1&&points.get(0).equals(points.get(points.size()-1)))points.remove(points.size()-1);
        return points;
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
    private int boundary(int side){return switch(side){case 0->viewport.left;case 1->viewport.right;case 2->viewport.bottom;default->viewport.top;};}
    private boolean inside(DeviceRoute.Point p,int side){return switch(side){case 0->p.x()>=viewport.left;case 1->p.x()<=viewport.right;case 2->p.y()>=viewport.bottom;default->p.y()<=viewport.top;};}
    private DeviceRoute.Point intersection(DeviceRoute.Point a,DeviceRoute.Point b,int side) {
        double boundary=boundary(side);
        double t=side<2?(boundary-a.x())/(b.x()-a.x()):(boundary-a.y())/(b.y()-a.y());
        return new DeviceRoute.Point((int)Math.round(a.x()+(b.x()-a.x())*t),(int)Math.round(a.y()+(b.y()-a.y())*t));
    }
    private DeviceRoute.Point[] clip(DeviceRoute.Point a,DeviceRoute.Point b) {
        double dx=b.x()-a.x(),dy=b.y()-a.y(),lo=0,hi=1;
        double[] p={-dx,dx,-dy,dy},q={a.x()-viewport.left,viewport.right-a.x(),a.y()-viewport.bottom,viewport.top-a.y()};
        for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return null;continue;}double t=q[i]/p[i];if(p[i]<0){if(t>hi)return null;lo=Math.max(lo,t);}else{if(t<lo)return null;hi=Math.min(hi,t);}}
        var x=new DeviceRoute.Point((int)Math.round(a.x()+lo*dx),(int)Math.round(a.y()+lo*dy));
        var y=new DeviceRoute.Point((int)Math.round(a.x()+hi*dx),(int)Math.round(a.y()+hi*dy));
        return x.equals(y)?null:new DeviceRoute.Point[]{x,y};
    }
}
