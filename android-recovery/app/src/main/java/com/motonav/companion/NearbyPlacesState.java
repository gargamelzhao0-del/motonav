package com.motonav.companion;

import java.util.List;
import java.util.LinkedHashMap;

/** Bounded in-memory catalog and refresh/backoff policy, with no SDK or Android dependency. */
public final class NearbyPlacesState {
    private record Entry(NearbyPlace place,long at) {}
    private List<Entry> places=List.of();
    private DeviceRoute.Coordinate center;
    private long attempted=-60000,received;
    private boolean complete;
    public boolean shouldRequest(DeviceRoute.Coordinate point,long now) {
        if(now-attempted<60000)return false;
        return center==null||!complete||NearbyPlace.distance(center,point)>300||now-received>=300000;
    }
    public void started(long now){attempted=now;}
    public void accept(DeviceRoute.Coordinate point,List<NearbyPlace> result,int successes,long now) {
        if(successes==0)return;
        var unique=new LinkedHashMap<String,Entry>();
        for(var place:result)if(NearbyPlace.distance(point,place.coordinate())<=1300&&unique.size()<75)unique.putIfAbsent(place.id(),new Entry(place,now));
        // A partial retry must not erase still-relevant names received earlier.
        if(successes<3&&center!=null&&now-received<900000&&NearbyPlace.distance(center,point)<300)
            for(var entry:places)if(unique.size()<75&&now-entry.at()<900000)unique.putIfAbsent(entry.place().id(),entry);
        places=List.copyOf(unique.values());center=point;received=now;complete=successes==3;
    }
    public List<NearbyPlace> visible(DeviceRoute.Coordinate point,long now) {
        return center!=null&&now-received<900000&&NearbyPlace.distance(center,point)<1000
            ?places.stream().filter(e->now-e.at()<900000).map(Entry::place).toList():List.of();
    }
    public void clear(){places=List.of();center=null;received=0;complete=false;}
}
