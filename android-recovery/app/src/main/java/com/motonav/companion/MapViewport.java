package com.motonav.companion;

/** Ground coverage of the device camera. Pure geometry; no SDK, BLE or renderer. */
public final class MapViewport {
    public record Screen(double x,double y) { }
    public final double x,y,heading,metersPerPixel,pitch,anchor;
    public final int left,right,bottom,top;
    private final double sine,cosine,ps,pc;
    private static final int MARGIN=120,MAX_COORDINATE=1900;
    public MapViewport(double x,double y,int headingTenths,boolean live,int speedKmh) {
        this.x=x;this.y=y;heading=Math.toRadians(Math.max(0,headingTenths)/10.0);
        double speed=Math.max(0,speedKmh);
        metersPerPixel=live?.85+clamp((speed-12)/68,0,1)*1.6:1.13;
        pitch=Math.toRadians(50)*(live?clamp((speed-6)/16,0,1):1);
        anchor=270+36*pitch/Math.toRadians(50);
        sine=Math.sin(heading);cosine=Math.cos(heading);ps=Math.sin(pitch);pc=Math.cos(pitch);
        double l=x,r=x,b=y,t=y;
        // The top 64 rows are the fog/rim region. Keep all remaining screen
        // corners plus movement margin; do not use a speed-independent radius.
        for(double sx:new double[]{-24,490})for(double sy:new double[]{64,490}) {
            double d=anchor-sy,forward=Math.min(1500,d*700/(700*pc-d*ps)*metersPerPixel);
            double scale=700/(700+forward/metersPerPixel*ps);
            double side=(sx-233)*metersPerPixel/scale;
            double px=x+side*cosine+forward*sine,py=y-side*sine+forward*cosine;
            l=Math.min(l,px);r=Math.max(r,px);b=Math.min(b,py);t=Math.max(t,py);
        }
        left=(int)Math.max(-MAX_COORDINATE,Math.floor(l)-MARGIN);
        right=(int)Math.min(MAX_COORDINATE,Math.ceil(r)+MARGIN);
        bottom=(int)Math.max(-MAX_COORDINATE,Math.floor(b)-MARGIN);
        top=(int)Math.min(MAX_COORDINATE,Math.ceil(t)+MARGIN);
    }
    public static MapViewport of(Basemap.Focus focus,NavigationFrame frame) {
        return new MapViewport(focus.route().x(),focus.route().y(),focus.route().heading(),
                frame.mode()==NavigationFrame.LIVE,frame.speedKmh());
    }
    public Screen project(double px,double py) {
        double dx=px-x,dy=py-y,forward=dx*sine+dy*cosine;
        double scale=1/metersPerPixel/Math.max(80.0/700,1+forward/metersPerPixel*ps/700);
        return new Screen(233+(dx*cosine-dy*sine)*scale,anchor-forward*pc*scale);
    }
    public boolean intersects(double l,double b,double r,double t) {
        return l<=right&&r>=left&&b<=top&&t>=bottom;
    }
    public boolean refreshFrom(MapViewport previous) {
        return previous==null||Math.hypot(x-previous.x,y-previous.y)>90
                ||Math.abs(Math.IEEEremainder(heading-previous.heading,Math.PI*2))>Math.toRadians(35)
                ||metersPerPixel>previous.metersPerPixel*1.18||metersPerPixel<previous.metersPerPixel*.82
                ||Math.abs(pitch-previous.pitch)>Math.toRadians(10);
    }
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
