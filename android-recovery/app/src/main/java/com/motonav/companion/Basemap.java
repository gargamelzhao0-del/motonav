package com.motonav.companion;

import java.util.List;

/** Provider-neutral, bounded local geometry. Coordinates share the route's metre origin. */
public final class Basemap {
    private Basemap() { }
    public static final int MAX_ITEMS=384;
    public static final int MAX_LABELS=24;
    /** Road classes 0..2 and waterway 8 are centerlines; kinds 3..7 are areas. */
    public static boolean lineKind(int kind){return kind>=0&&(kind<3||kind==8);}
    /** 0 road, 1 place, 2 landmark, 3 fuel, 4 parking, 5 food, 6 medical, 7 transit, 8 park. */
    public record Label(int kind,DeviceRoute.Point point,String name) {
        public Label {
            name=NavigationDetails.clean(name);
            if(kind<0||kind>8||point==null||Math.abs((long)point.x())>2048
                    ||Math.abs((long)point.y())>2048||name.isEmpty())throw new IllegalArgumentException("map label");
        }
    }
    public record Primitive(int kind,DeviceRoute.Point a,DeviceRoute.Point b,DeviceRoute.Point c) {
        public Primitive {
            if(kind<0||kind>8)throw new IllegalArgumentException("map kind");
            for(var p:List.of(a,b,c))if(Math.abs((long)p.x())>2048||Math.abs((long)p.y())>2048)
                throw new IllegalArgumentException("map coordinate");
            if(lineKind(kind) && (c.x()!=0||c.y()!=0))throw new IllegalArgumentException("line tail");
        }
    }
    public record Scene(int revision,int update,boolean complete,List<Primitive> items,List<Label> labels,int labelSources) {
        public Scene(int revision,int update,boolean complete,List<Primitive> items,List<Label> labels){this(revision,update,complete,items,labels,1);}
        public Scene(int revision,List<Primitive> items){this(revision,1,true,items,List.of());}
        public Scene(int revision,int update,boolean complete,List<Primitive> items){this(revision,update,complete,items,List.of());}
        public Scene {
            if(revision<=0||update<=0||items.size()>MAX_ITEMS||labels.size()>MAX_LABELS||labelSources<1||labelSources>3)throw new IllegalArgumentException("map scene");
            items=List.copyOf(items);labels=List.copyOf(labels);
        }
    }
    /** A measured map position is independent of turn-by-turn guidance readiness. */
    public record Position(int quality,int accuracyMeters,long measuredMs) {
        public Position {
            if(quality<0||quality>2||quality==0&&(accuracyMeters!=0||measuredMs!=0)
                    ||quality>0&&(accuracyMeters<1||accuracyMeters>10000||measuredMs<=0)
                    ||quality==2&&accuracyMeters>100)throw new IllegalArgumentException("map position");
        }
        public static Position unknown(){return new Position(0,0,0);}
        public boolean fresh(long now){return quality>0&&now>=measuredMs&&now-measuredMs<15000;}
    }
    public record Focus(DeviceRoute.Frame route,DeviceRoute.Coordinate origin,int reason,int totalMeters,int totalSeconds,Position position) {
        public Focus(DeviceRoute.Frame route,DeviceRoute.Coordinate origin,int reason,int totalMeters,int totalSeconds){
            this(route,origin,reason,totalMeters,totalSeconds,Position.unknown());
        }
    }
    public record Tile(int zoom,int x,int y) {
        public Tile { if(zoom<0||zoom>14||x<0||y<0||x>=(1<<zoom)||y>=(1<<zoom))throw new IllegalArgumentException("tile"); }
        public String key(){return zoom+"/"+x+"/"+y;}
    }
    public interface Coordinates {
        DeviceRoute.Coordinate toMap(DeviceRoute.Coordinate gps);
    }
    public static DeviceRoute.Coordinate gpsOrigin(DeviceRoute.Coordinate map,Coordinates conversion) {
        var guess=map;
        for(int i=0;i<6;i++) {
            var projected=conversion.toMap(guess);
            double dy=map.latitude()-projected.latitude(),dx=map.longitude()-projected.longitude();
            if(!Double.isFinite(dx)||!Double.isFinite(dy)||Math.abs(dx)>.1||Math.abs(dy)>.1)
                throw new IllegalArgumentException("coordinate conversion");
            guess=new DeviceRoute.Coordinate(guess.latitude()+dy,guess.longitude()+dx);
            if(Math.max(Math.abs(dx),Math.abs(dy))<1e-7)return guess;
        }
        var finalPoint=conversion.toMap(guess);
        if(Math.max(Math.abs(finalPoint.latitude()-map.latitude()),Math.abs(finalPoint.longitude()-map.longitude()))>1e-6)
            throw new IllegalArgumentException("coordinate conversion did not converge");
        return guess;
    }
    public static DeviceRoute.Coordinate coordinate(Tile tile,int extent,int x,int y) {
        double n=1<<tile.zoom(),longitude=((tile.x()+x/(double)extent)/n)*360-180;
        double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*(tile.y()+y/(double)extent)/n))));
        return new DeviceRoute.Coordinate(latitude,longitude);
    }
    public static List<Tile> visibleTiles(DeviceRoute.Coordinate origin) {
        return visibleTiles(origin,-900,-900,900,900);
    }
    public static List<Tile> visibleTiles(DeviceRoute.Coordinate origin,MapViewport viewport) {
        return visibleTiles(origin,viewport.left,viewport.bottom,viewport.right,viewport.top);
    }
    private static List<Tile> visibleTiles(DeviceRoute.Coordinate origin,int minX,int minY,int maxX,int maxY) {
        double cosine=Math.cos(Math.toRadians(origin.latitude()));
        double radius=Math.max(maxX-minX,maxY-minY)/2.0;
        int zoom=Math.max(0,Math.min(14,(int)Math.floor(Math.log(40075016*cosine/Math.max(1,radius))/Math.log(2))));
        int n=1<<zoom;
        double unit=111319.49079327358;
        int left=(int)Math.floor((origin.longitude()+minX/unit/cosine+180)/360*n),right=(int)Math.floor((origin.longitude()+maxX/unit/cosine+180)/360*n);
        int top=tileY(origin.latitude()+maxY/unit,n),bottom=tileY(origin.latitude()+minY/unit,n);
        java.util.ArrayList<Tile> result=new java.util.ArrayList<>();
        for(int y=Math.max(0,top);y<=Math.min(n-1,bottom);y++)for(int x=left;x<=right;x++)result.add(new Tile(zoom,Math.floorMod(x,n),y));
        if(result.size()>9)throw new IllegalArgumentException("visible map bounds");
        // The containing tile is visible first; outer tiles can refine it later.
        double centerX=(origin.longitude()+180)/360*n,centerY=tileY(origin.latitude(),n)+.5;
        result.sort(java.util.Comparator.comparingDouble(t->Math.pow(t.x()+.5-centerX,2)+Math.pow(t.y()+.5-centerY,2)));
        return List.copyOf(result);
    }
    private static int tileY(double latitude,int n) {
        double r=Math.toRadians(Math.max(-85,Math.min(85,latitude)));
        return (int)Math.floor((1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n);
    }
}
