package com.motonav.companion;

/** Pure deterministic local-metre simulator. No GPS or real-road input. */
public final class Simulation {
    public static final double[][] ROUTE={{0,0},{0,240},{180,240},{180,480},{-120,480},{-120,720},{240,720},{240,960}};
    public static final int TOTAL_MM=1800000;
    public static final int SPEED_MMPS=12000;
    private double[][] points;
    private int[] segmentMm;
    private int total;
    public int distanceMm;
    public boolean running;
    public Simulation(){setRoute(ROUTE);}
    public int totalMm(){return total;}
    public double[][] route(){double[][] copy=new double[points.length][2];for(int i=0;i<points.length;i++)copy[i]=points[i].clone();return copy;}
    public void setRoute(double[][] supplied){
        if(supplied==null||supplied.length<2||supplied.length>16)throw new IllegalArgumentException("Route point count");
        double[][] next=new double[supplied.length][2];int[] lengths=new int[supplied.length-1];long sum=0;
        for(int i=0;i<supplied.length;i++){
            if(supplied[i]==null||supplied[i].length!=2||!Double.isFinite(supplied[i][0])||!Double.isFinite(supplied[i][1]))throw new IllegalArgumentException("Route coordinates");
            next[i]=supplied[i].clone();
            if(i>0){long mm=Math.round(Math.hypot(next[i][0]-next[i-1][0],next[i][1]-next[i-1][1])*1000);if(mm<=0||mm>2000000)throw new IllegalArgumentException("Segment length");lengths[i-1]=(int)mm;sum+=mm;}
        }
        if(sum>10000000)throw new IllegalArgumentException("Route length");
        points=next;segmentMm=lengths;total=(int)sum;reset();
    }
    public void reset(){distanceMm=0;running=false;}
    public void advance(long elapsedMs){if(running&&elapsedMs>0)distanceMm=(int)Math.min(total,distanceMm+Math.min(elapsedMs,1000)*(long)SPEED_MMPS/1000);if(distanceMm==total)running=false;}
    public Position position(){
        int remaining=Math.max(0,Math.min(total,distanceMm));
        for(int i=0;i<points.length-1;i++){
            int length=segmentMm[i];if(remaining>=length&&i<points.length-2){remaining-=length;continue;}
            double dx=points[i+1][0]-points[i][0],dy=points[i+1][1]-points[i][1],t=Math.min(1,remaining/(double)length),heading=Math.atan2(dx,dy);String turn="终点";
            if(i<points.length-2){double next=Math.atan2(points[i+2][0]-points[i+1][0],points[i+2][1]-points[i+1][1]);double delta=Math.atan2(Math.sin(next-heading),Math.cos(next-heading));turn=Math.abs(delta)<Math.toRadians(5)?"直行":delta>0?"右转":"左转";}
            return new Position(points[i][0]+dx*t,points[i][1]+dy*t,heading,Math.max(0,length-remaining)/1000.0,turn);
        }
        throw new IllegalStateException("route");
    }
    public record Position(double x,double y,double heading,double turnDistance,String turn){}
}
