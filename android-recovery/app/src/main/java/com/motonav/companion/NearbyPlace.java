package com.motonav.companion;

/** A named SDK result in GCJ-02, kept only in the current navigation session. */
public record NearbyPlace(String id,String name,String typeCode,DeviceRoute.Coordinate coordinate) {
    public NearbyPlace {
        name=NavigationDetails.clean(name);
        if(id==null||id.isBlank()||name.isBlank()||typeCode==null||coordinate==null
                ||!Double.isFinite(coordinate.latitude())||!Double.isFinite(coordinate.longitude())
                ||Math.abs(coordinate.latitude())>85||Math.abs(coordinate.longitude())>180)
            throw new IllegalArgumentException("nearby place");
    }
    public Basemap.Label label(DeviceRoute.Coordinate origin) {
        double y=(coordinate.latitude()-origin.latitude())*111319.49079327358;
        double x=(coordinate.longitude()-origin.longitude())*111319.49079327358*Math.cos(Math.toRadians(origin.latitude()));
        if(Math.abs(x)>2048||Math.abs(y)>2048)return null;
        // Named residential/business areas are captions, not point-symbol pins.
        int kind=typeCode.startsWith("12")?1:typeCode.startsWith("0101")?3:
            typeCode.startsWith("1509")?4:typeCode.startsWith("05")?5:
            typeCode.startsWith("09")?6:typeCode.startsWith("15")?7:
            typeCode.startsWith("1101")?8:2;
        return new Basemap.Label(kind,new DeviceRoute.Point((int)Math.round(x),(int)Math.round(y)),name);
    }
    public static DeviceRoute.Coordinate center(Basemap.Focus focus) {
        var origin=focus.origin();
        return new DeviceRoute.Coordinate(origin.latitude()+focus.route().y()/111319.49079327358,
            origin.longitude()+focus.route().x()/(111319.49079327358*Math.cos(Math.toRadians(origin.latitude()))));
    }
    public static double distance(DeviceRoute.Coordinate a,DeviceRoute.Coordinate b) {
        return Math.hypot((a.latitude()-b.latitude())*111319.49079327358,
            (a.longitude()-b.longitude())*111319.49079327358*Math.cos(Math.toRadians(a.latitude())));
    }
}
