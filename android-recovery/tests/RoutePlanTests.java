import com.motonav.companion.*;
import java.nio.file.*;
import java.util.*;

public final class RoutePlanTests {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        DeviceRoute route=new DeviceRoute();
        route.replace(List.of(new DeviceRoute.Coordinate(39.98,116.48),new DeviceRoute.Coordinate(39.983,116.48),
                new DeviceRoute.Coordinate(39.983,116.484),new DeviceRoute.Coordinate(39.987,116.484)));
        var weak=route.preview(1);
        check(weak!=null && weak.points().size()==4,"route exists before any GPS frame");
        check(weak.points().get(0).y()==-1000 && weak.points().get(3).y()==1000,"entire route fits overview");
        var map=route.frame(39.982,116.48,0);
        check(route.preview(0).revision()==weak.revision(),"live camera updates cannot change planned geometry revision");
        RouteProtocol protocol=new RouteProtocol(17);
        List<RouteProtocol.Command> frames=new ArrayList<>();
        frames.add(protocol.helloWithPreview());
        frames.add(protocol.plan(weak));
        frames.add(protocol.snapshot(NavigationFrame.status(1),null,100000));
        frames.add(protocol.snapshot(new NavigationFrame(2,3,120,4000,360,36,100000,100000),map,100000));
        frames.add(protocol.plan(route.preview(0)));
        frames.add(protocol.snapshot(NavigationFrame.status(1),null,104002));
        frames.add(protocol.plan(route.preview(1)));
        frames.add(protocol.snapshot(NavigationFrame.status(4),null,104004));
        route.replace(List.of(new DeviceRoute.Coordinate(39.98,116.48),new DeviceRoute.Coordinate(39.981,116.481)));
        frames.add(protocol.plan(route.preview(1)));
        frames.add(protocol.snapshot(NavigationFrame.status(1),null,104006));
        frames.add(protocol.snapshot(NavigationFrame.status(3),null,104007));
        for(var frame:frames)RouteProtocolTests.verify(frame);
        StringBuilder fixture=new StringBuilder();
        for(var frame:frames)fixture.append(HexFormat.of().formatHex(frame.bytes())).append('\n');
        if(args.length>0)Files.writeString(Path.of(args[0]),fixture);
        route.clear();check(route.preview(1)==null,"stop removes preview");
        List<DeviceRoute.Coordinate> longRoute=new ArrayList<>();
        for(int i=0;i<20000;i++)longRoute.add(new DeviceRoute.Coordinate(30+i*0.00001,120+0.005*Math.sin(i*0.1)));
        route.replace(longRoute);var bounded=route.preview(1);
        check(bounded.points().size()<=64 && bounded.points().size()>=2,"long winding route bounded to transport capacity");
        check(bounded.points().get(0).y()==-1000 && bounded.points().get(bounded.points().size()-1).y()==1000,"long route keeps both endpoints");
        for(var point:bounded.points())check(Math.abs(point.x())<=1000 && Math.abs(point.y())<=1000,"overview bounds");
        route.replace(List.of(new DeviceRoute.Coordinate(Double.NaN,120),new DeviceRoute.Coordinate(30,120)));
        check(route.preview(1)==null,"invalid coordinates cannot produce a plan");
        System.out.println("Route plan: "+checks+" GPS-independent, bounded geometry checks passed");
    }
}
