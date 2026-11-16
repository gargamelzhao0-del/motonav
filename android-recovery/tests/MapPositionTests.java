import com.motonav.companion.*;
import java.nio.file.*;
import java.util.*;

public class MapPositionTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var points=List.of(new DeviceRoute.Point(0,0),new DeviceRoute.Point(0,400));
        var route=new DeviceRoute.Frame(1,points,12,34,-1);
        var approximate=new Basemap.Focus(route,new DeviceRoute.Coordinate(30,120),1,5000,600,new Basemap.Position(1,550,99500));
        var precise=new Basemap.Focus(route,new DeviceRoute.Coordinate(30,120),4,5000,600,new Basemap.Position(2,8,101000));
        var unknown=new Basemap.Focus(route,new DeviceRoute.Coordinate(30,120),1,5000,600);
        var p=new RouteProtocol(123);var frames=new ArrayList<RouteProtocol.Command>();
        frames.add(p.helloWithPosition());frames.add(p.snapshot(NavigationFrame.status(1),null,100000));
        frames.add(p.focusWithPosition(approximate,100000));frames.add(p.focusWithPosition(precise,101000));
        frames.add(p.focusWithPosition(unknown,101001));frames.add(p.snapshot(NavigationFrame.status(4),null,101002));
        check(frames.get(2).bytes().length==50&&frames.get(2).bytes()[4]==12,"bounded atomic focus and position");
        check(p.focusWithPosition(approximate,112000).bytes()[33]==0,"transport budget cannot revive an expired measurement");
        check(p.focusWithPosition(precise,100000).bytes()[33]==0,"delivery budget cannot legitimize a future measurement");
        var max=new Basemap.Focus(new DeviceRoute.Frame(2,Collections.nCopies(64,new DeviceRoute.Point(0,0)),0,0,-1),approximate.origin(),1,0,0,approximate.position());
        check(p.focusWithPosition(max,100000).bytes().length<=RouteProtocol.MAX_BYTES,"maximum route fits negotiated frame budget");
        for(var value:List.of(new long[]{3,5,100},new long[]{2,550,100},new long[]{1,0,100},new long[]{1,10001,100},new long[]{0,1,0})){
            boolean rejected=false;try{new Basemap.Position((int)value[0],(int)value[1],value[2]);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"invalid quality/accuracy rejected");
        }
        var output=new StringBuilder();for(var frame:frames)output.append(HexFormat.of().formatHex(frame.bytes())).append('\n');
        if(args.length>0)Files.writeString(Path.of(args[0]),output);
        System.out.println("PASS: independent map position quality, age, unknowns and wire bounds");
    }
}
