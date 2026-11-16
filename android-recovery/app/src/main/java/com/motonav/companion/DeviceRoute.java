package com.motonav.companion;

import java.util.ArrayList;
import java.util.List;

/** Bounded consecutive route window. Both route and position use SDK GCJ-02 coordinates. */
public final class DeviceRoute {
    public record Coordinate(double latitude, double longitude) { }
    public record Point(int x, int y) { }
    public record Frame(int revision, List<Point> points, int x, int y, int heading) {
        public Frame { points = List.copyOf(points); }
    }
    /** Planned geometry in a uniformly scaled overview space; contains no vehicle position. */
    public record Preview(int revision, List<Point> points, int waitingReason) {
        public Preview { points = List.copyOf(points); }
    }
    private List<Coordinate> route = List.of();
    private List<Point> window = List.of();
    private double originLat, originLon, longitudeScale;
    private int revision;
    private List<Point> overview = List.of();
    private int overviewRevision;
    private Frame lastFrame;
    private Coordinate lastOrigin;
    public void replace(List<Coordinate> coordinates) {
        route = List.copyOf(coordinates); window = List.of(); lastFrame=null;lastOrigin=null;
        overview = overview(route);
        overviewRevision = overview.isEmpty() || revision == Integer.MAX_VALUE ? 0 : ++revision;
    }
    public void clear() { route = List.of(); window = List.of(); overview = List.of(); overviewRevision = 0; lastFrame=null;lastOrigin=null; }
    /** A fixed metre view, retained through GPS loss. Never fit the whole journey. */
    public Frame localFrame() {
        if(lastFrame==null && route.size()>=2)frame(route.get(0).latitude(),route.get(0).longitude(),-1);
        return lastFrame;
    }
    public Coordinate origin() { return lastOrigin==null?new Coordinate(originLat,originLon):lastOrigin; }
    public Preview preview(int waitingReason) {
        if (waitingReason < 0 || waitingReason > 7) throw new IllegalArgumentException("waiting reason");
        return overviewRevision == 0 ? null : new Preview(overviewRevision, overview, waitingReason);
    }
    private static List<Point> overview(List<Coordinate> route) {
        if (route.size() < 2) return List.of();
        double minLat=Double.POSITIVE_INFINITY,maxLat=-minLat,minLon=minLat,maxLon=-minLat;
        for (Coordinate p : route) {
            if (!valid(p)) return List.of();
            minLat=Math.min(minLat,p.latitude());maxLat=Math.max(maxLat,p.latitude());
            minLon=Math.min(minLon,p.longitude());maxLon=Math.max(maxLon,p.longitude());
        }
        double middleLat=(minLat+maxLat)/2,middleLon=(minLon+maxLon)/2;
        double longitudeScale=Math.cos(Math.toRadians(middleLat));
        double extent=Math.max(maxLat-minLat,(maxLon-minLon)*longitudeScale);
        if (!(extent > 0)) return List.of();
        // Cache once when planning finishes. Bound simplification work and keep
        // both endpoints even on long routes; this is an overview, not a live fix.
        int samples=Math.min(4096,route.size());
        List<Point> points=new ArrayList<>(samples);
        for(int i=0;i<samples;i++) {
            Coordinate p=route.get((int)((long)i*(route.size()-1)/(samples-1)));
            Point q=new Point((int)Math.round((p.longitude()-middleLon)*longitudeScale*2000/extent),
                    (int)Math.round((p.latitude()-middleLat)*2000/extent));
            if(points.isEmpty() || !points.get(points.size()-1).equals(q))points.add(q);
        }
        if(points.size()<2)return List.of();
        List<Point> reduced=simplify(points,2);
        if(reduced.size()>64) {
            double low=2,high=3000;
            for(int pass=0;pass<16;pass++) {
                double tolerance=(low+high)/2;List<Point> candidate=simplify(points,tolerance);
                if(candidate.size()>64)low=tolerance;else{high=tolerance;reduced=candidate;}
            }
        }
        return List.copyOf(reduced);
    }
    private static boolean valid(Coordinate p) {
        return p != null && Double.isFinite(p.latitude()) && Math.abs(p.latitude()) <= 85
                && Double.isFinite(p.longitude()) && Math.abs(p.longitude()) <= 180;
    }
    private Point project(Coordinate p) {
        return new Point((int)Math.round((p.longitude()-originLon)*longitudeScale),
                (int)Math.round((p.latitude()-originLat)*111319.49079327358));
    }
    public Frame frame(double latitude, double longitude, float bearing) {
        Coordinate position = new Coordinate(latitude,longitude);
        if (!valid(position) || route.size()<2 || revision==Integer.MAX_VALUE) return null;
        if (window.isEmpty() || Math.hypot(project(position).x(),project(position).y())>250) {
            window=List.of();
            originLat=latitude; originLon=longitude; longitudeScale=111319.49079327358*Math.cos(Math.toRadians(latitude));
            int nearest=-1; double distance=Double.MAX_VALUE;
            for(int i=0;i<route.size()-1;i++) {
                if(!valid(route.get(i)) || !valid(route.get(i+1))) return null;
                Point a=project(route.get(i)),b=project(route.get(i+1));
                double dx=(double)b.x()-a.x(),dy=(double)b.y()-a.y(),length=dx*dx+dy*dy;
                double t=length==0?0:Math.max(0,Math.min(1,-(a.x()*dx+a.y()*dy)/length));
                double d=Math.hypot(a.x()+t*dx,a.y()+t*dy);
                if(d<distance) { distance=d; nearest=i; }
            }
            if(nearest<0 || distance>1000) { window=List.of(); return null; }
            int from=nearest,to=nearest+1;
            double behind=0,ahead=0;
            while(from>0 && behind<500) { behind+=distance(project(route.get(from)),project(route.get(from-1))); from--; }
            while(to<route.size()-1 && ahead<1000) { ahead+=distance(project(route.get(to)),project(route.get(to+1))); to++; }
            if(to-from>4096)return null;
            List<Point> points=new ArrayList<>();
            for(int i=from;i<=to;i++) {
                Point p=project(route.get(i));
                if(Math.abs((long)p.x())>32000 || Math.abs((long)p.y())>32000) { window=List.of(); return null; }
                if(points.isEmpty() || !points.get(points.size()-1).equals(p)) points.add(p);
            }
            if(points.size()<2) { window=List.of(); return null; }
            // Preserve order and endpoints. Never increase the shape error past eight metres.
            List<Point> reduced=simplify(points,2);
            if(reduced.size()>64) reduced=simplify(points,4);
            if(reduced.size()>64) reduced=simplify(points,8);
            if(reduced.size()>64) { window=List.of(); return null; }
            window=List.copyOf(reduced); revision++;
        }
        Point p=project(position);
        int heading=Float.isFinite(bearing)&&bearing>=0&&bearing<360?Math.round(bearing*10)%3600:-1;
        lastFrame=new Frame(revision,window,p.x(),p.y(),heading);
        lastOrigin=new Coordinate(originLat,originLon);
        return lastFrame;
    }
    private static double distance(Point a,Point b) { return Math.hypot((double)a.x()-b.x(),(double)a.y()-b.y()); }
    static List<Point> simplify(List<Point> input,double tolerance) {
        boolean[] keep=new boolean[input.size()]; keep[0]=keep[keep.length-1]=true;
        ArrayList<int[]> ranges=new ArrayList<>(); ranges.add(new int[]{0,input.size()-1});
        while(!ranges.isEmpty()) {
            int[] range=ranges.remove(ranges.size()-1); int a=range[0],b=range[1],furthest=-1;
            Point start=input.get(a),end=input.get(b);
            double dx=(double)end.x()-start.x(),dy=(double)end.y()-start.y(),length=dx*dx+dy*dy,max=tolerance;
            for(int i=a+1;i<b;i++) {
                Point p=input.get(i); double px=(double)p.x()-start.x(),py=(double)p.y()-start.y();
                double t=length==0?0:Math.max(0,Math.min(1,(px*dx+py*dy)/length));
                double d=Math.hypot(px-t*dx,py-t*dy);
                if(d>max) { max=d; furthest=i; }
            }
            if(furthest>=0) { keep[furthest]=true; ranges.add(new int[]{a,furthest}); ranges.add(new int[]{furthest,b}); }
        }
        List<Point> out=new ArrayList<>(); for(int i=0;i<input.size();i++)if(keep[i])out.add(input.get(i)); return out;
    }
}
