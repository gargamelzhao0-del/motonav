package com.motonav.companion;

import java.util.List;
import java.util.ArrayList;

/** One owner of published scene generations; independent geometry and names may finish in any order. */
public final class MapSceneComposer {
    private Basemap.Scene previousBase,current;
    private List<NearbyPlace> previousPlaces=List.of();
    private MapViewport previousView;
    private boolean previousCapability;
    private int update,selectedPlaces;
    public int selectedPlaces(){return selectedPlaces;}
    public Basemap.Scene compose(Basemap.Scene base,Basemap.Focus focus,NavigationFrame navigation,
                                List<NearbyPlace> places,boolean attributed) {
        if(base==null||focus==null||base.revision()!=focus.route().revision())return null;
        if(!attributed)places=List.of();
        var view=MapViewport.of(focus,navigation,previousView);
        if(base==previousBase&&places.equals(previousPlaces)&&attributed==previousCapability&&!view.refreshFrom(previousView))return current;
        var extras=new ArrayList<Basemap.Label>();
        for(var place:places){var label=place.label(focus.origin());if(label!=null)extras.add(label);}
        List<Basemap.Label> labels=base.labels();selectedPlaces=0;
        if(!extras.isEmpty()){
            var selector=new LocalMapLabels(view.detailView());
            for(var label:base.labels())selector.addNamed(label);
            for(var label:extras)selector.addNamed(label);
            labels=selector.finish();
            for(var label:labels)if(extras.contains(label))selectedPlaces++;
        }
        int sources=selectedPlaces>0?3:1;
        previousBase=base;previousPlaces=List.copyOf(places);previousView=view;previousCapability=attributed;
        if(current!=null&&current.revision()==base.revision()&&current.complete()==base.complete()
                &&current.items().equals(base.items())&&current.labels().equals(labels)&&current.labelSources()==sources)return current;
        current=new Basemap.Scene(base.revision(),++update,base.complete(),base.items(),labels,sources);
        return current;
    }
    public void clear(){previousBase=current=null;previousView=null;previousPlaces=List.of();selectedPlaces=0;}
}
