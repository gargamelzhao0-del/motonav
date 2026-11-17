package com.motonav.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded, provider-derived names; selection does not consume the geometry budget. */
public final class LocalMapLabels {
    private record Candidate(Basemap.Label label,double score) { }
    private final PriorityQueue<Candidate> candidates=new PriorityQueue<>(
            Comparator.comparingDouble(Candidate::score).reversed());
    private final double focusX,focusY;

    public LocalMapLabels(double focusX,double focusY){this.focusX=focusX;this.focusY=focusY;}

    public void add(VectorTile.Feature feature,List<DeviceRoute.Point> path) {
        int kind=kind(feature);
        if(kind<0||path.isEmpty())return;
        String name=feature.name().isBlank()?categoryName(feature):feature.name();
        if(name.isBlank())return;
        // Place a street name at the nearest point of its actual polyline.
        // Point features keep their surveyed/provider position.
        DeviceRoute.Point point=path.get(0);double nearest=distance(point);
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);
            double dx=b.x()-a.x(),dy=b.y()-a.y(),length=dx*dx+dy*dy;
            double t=length==0?0:Math.max(0,Math.min(1,((focusX-a.x())*dx+(focusY-a.y())*dy)/length));
            var p=new DeviceRoute.Point((int)Math.round(a.x()+t*dx),(int)Math.round(a.y()+t*dy));
            if(distance(p)<nearest){point=p;nearest=distance(p);}
        }
        if(nearest>650*650||Math.abs(point.x())>2048||Math.abs(point.y())>2048)return;
        var label=new Basemap.Label(kind,point,name);
        double priority=kind==3||kind==6?.6:kind==0?.8:1;
        candidates.add(new Candidate(label,nearest*priority));
        if(candidates.size()>256)candidates.poll();
    }

    public List<Basemap.Label> finish() {
        var sorted=new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::score)
                .thenComparing(c->c.label().name()).thenComparingInt(c->c.label().point().x())
                .thenComparingInt(c->c.label().point().y()));
        List<Basemap.Label> result=new ArrayList<>();
        // Give roads, places and POIs their own initial share before filling.
        int[] counts=new int[3];int[] quota={8,4,12};boolean[][] cells=new boolean[3][25];
        for(int pass=0;pass<3;pass++)for(var candidate:sorted) {
            var label=candidate.label();int group=Math.min(2,label.kind());
            if(result.size()==Basemap.MAX_LABELS)break;
            int cx=Math.max(-2,Math.min(2,(int)Math.floor((label.point().x()-focusX)/160)));
            int cy=Math.max(-2,Math.min(2,(int)Math.floor((label.point().y()-focusY)/160)));
            int cell=(cy+2)*5+cx+2;
            if(pass<2&&counts[group]>=quota[group]||pass==0&&cells[group][cell])continue;
            boolean duplicate=result.stream().anyMatch(v->{
                double distance=Math.hypot(v.point().x()-label.point().x(),v.point().y()-label.point().y());
                return v.kind()==label.kind()&&(v.name().equals(label.name())&&(label.kind()<2||distance<80)
                        ||label.kind()>=2&&distance<15);
            });
            if(!duplicate){result.add(label);counts[group]++;cells[group][cell]=true;}
        }
        return List.copyOf(result);
    }

    private double distance(DeviceRoute.Point p){return Math.pow(p.x()-focusX,2)+Math.pow(p.y()-focusY,2);}
    private static int kind(VectorTile.Feature feature) {
        if(feature.layer().equals("street_labels")&&feature.type()==2)return 0;
        if(feature.layer().equals("place_labels")&&feature.type()==1)return 1;
        if(feature.layer().equals("public_transport")&&feature.type()==1)return 7;
        if(feature.layer().startsWith("water_")&&feature.layer().endsWith("_labels"))return 2;
        if(!feature.layer().equals("pois")||feature.type()!=1)return -1;
        return switch(feature.kind()) {
            case "fuel" -> 3;
            case "parking","motorcycle_parking" -> 4;
            case "restaurant","cafe","fast_food","food_court" -> 5;
            case "hospital","clinic","pharmacy","doctors" -> 6;
            case "park","garden","nature_reserve" -> 8;
            default -> 2;
        };
    }
    private static String categoryName(VectorTile.Feature feature) {
        if(feature.layer().equals("public_transport"))return "车站";
        if(!feature.layer().equals("pois"))return "";
        return switch(feature.kind()) {
            case "fuel" -> "加油站";
            case "charging_station" -> "充电站";
            case "parking","motorcycle_parking" -> "停车场";
            case "restaurant","cafe","fast_food","food_court" -> "餐饮";
            case "hospital" -> "医院";
            case "clinic","doctors" -> "诊所";
            case "pharmacy" -> "药房";
            case "park","garden" -> "园区";
            case "nature_reserve" -> "自然保护区";
            default -> "";
        };
    }
}
