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
    private final MapViewport view;
    private int candidateCount,duplicateCount;

    public LocalMapLabels(double focusX,double focusY){this.focusX=focusX;this.focusY=focusY;view=null;}
    public LocalMapLabels(MapViewport view){this.view=view;focusX=view.x+Math.sin(view.heading)*80;focusY=view.y+Math.cos(view.heading)*80;}
    public int candidateCount(){return candidateCount;}
    public int duplicateCount(){return duplicateCount;}
    public static boolean supported(VectorTile.Feature feature){return kind(feature)>=0;}

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
        if(Math.abs(point.x())>2048||Math.abs(point.y())>2048)return;
        double score=nearest;
        if(view==null){if(nearest>650*650)return;}
        else {
            var screen=view.project(point.x(),point.y());
            double radius=Math.hypot(screen.x()-233,screen.y()-233);
            if(radius>303||screen.y()<64||screen.y()>520)return;
            score=Math.pow(screen.x()-233,2)+Math.pow(screen.y()-250,2);
            if(radius>233||screen.y()<140||screen.y()>375)score=score*4+100000;
        }
        var label=new Basemap.Label(kind,point,name);
        double priority=kind==3||kind==6?.6:kind==0?.8:1;
        var candidate=new Candidate(label,score*priority);
        // Deduplicate before the bounded candidate queue, otherwise repeated
        // tile buffers can evict distinct names before the final selection.
        Candidate duplicate=null;
        for(var previous:candidates)if(duplicate(previous.label(),label)){duplicate=previous;break;}
        if(duplicate!=null){
            duplicateCount++;
            if(duplicate.score()<=candidate.score())return;
            candidates.remove(duplicate);
        }else candidateCount++;
        candidates.add(candidate);
        if(candidates.size()>256)candidates.poll();
    }

    public List<Basemap.Label> finish() {
        var sorted=new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Candidate::score)
                .thenComparing(c->c.label().name()).thenComparingInt(c->c.label().point().x())
                .thenComparingInt(c->c.label().point().y()));
        List<Basemap.Label> result=new ArrayList<>();
        java.util.Set<Candidate> picked=new java.util.HashSet<>();
        // Give roads, places and POIs their own initial share before filling.
        int[] counts=new int[3];int[] quota={8,4,12};boolean[][] cells=new boolean[3][25];
        for(int pass=0;pass<3;pass++)for(var candidate:sorted) {
            var label=candidate.label();int group=Math.min(2,label.kind());
            if(result.size()==Basemap.MAX_LABELS)break;
            if(picked.contains(candidate))continue;
            int cx=Math.max(-2,Math.min(2,(int)Math.floor((label.point().x()-focusX)/160)));
            int cy=Math.max(-2,Math.min(2,(int)Math.floor((label.point().y()-focusY)/160)));
            int cell=(cy+2)*5+cx+2;
            if(view!=null){var p=view.project(label.point().x(),label.point().y());
                cell=Math.max(0,Math.min(4,(int)(p.y()/94)))*5+Math.max(0,Math.min(4,(int)(p.x()/94)));}
            if(pass<2&&counts[group]>=quota[group]||pass==0&&cells[group][cell])continue;
            boolean duplicate=result.stream().anyMatch(v->duplicate(v,label));
            if(!duplicate){result.add(label);picked.add(candidate);counts[group]++;cells[group][cell]=true;}
        }
        return List.copyOf(result);
    }

    private double distance(DeviceRoute.Point p){return Math.pow(p.x()-focusX,2)+Math.pow(p.y()-focusY,2);}
    private static boolean duplicate(Basemap.Label a,Basemap.Label b){
        return a.kind()==b.kind()&&a.name().equals(b.name())&&Math.hypot(a.point().x()-b.point().x(),a.point().y()-b.point().y())<(a.kind()==0?120:15);
    }
    private static int kind(VectorTile.Feature feature) {
        if(feature.layer().equals("street_labels")&&feature.type()==2)return 0;
        if(List.of("street_labels_points","street_polygons_labels","streets_polygons_labels").contains(feature.layer())&&feature.type()==1)return 0;
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
