import com.motonav.companion.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class NavigationDetailsTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var details=new NavigationDetails("光华东路", "东三环北路", 686, -1);
        var cleaned=new NavigationDetails("路\u0000\n\u202e名\ud800", "路".repeat(30), -1, -1);
        check(cleaned.currentRoad().equals("路名"),"control/invalid Unicode removed");
        check(cleaned.nextRoad().getBytes(StandardCharsets.UTF_8).length==63,"UTF-8 bounded without splitting a glyph");
        String emoji=new NavigationDetails(null,"😀".repeat(20),0,300).nextRoad();
        check(emoji.getBytes(StandardCharsets.UTF_8).length==60&&emoji.codePointCount(0,emoji.length())==15,"placeholder glyphs stay valid UTF-8");
        for(int[] invalid:List.of(new int[]{1440,-1},new int[]{-2,-1},new int[]{0,0},new int[]{0,301})){
            boolean rejected=false;try{new NavigationDetails("","",invalid[0],invalid[1]);}catch(IllegalArgumentException e){rejected=true;}
            check(rejected,"invalid ETA/limit rejected");
        }
        var protocol=new RouteProtocol(123);var frames=new ArrayList<RouteProtocol.Command>();
        var route=new DeviceRoute.Frame(1,List.of(new DeviceRoute.Point(0,0),new DeviceRoute.Point(0,400)),0,0,0);
        var live=new NavigationFrame(2,3,120,4000,360,36,100000,100000);
        frames.add(protocol.helloWithDetails());
        var nav=protocol.snapshot(live,route,100000);frames.add(nav);
        frames.add(protocol.details(1,nav.sequence(),details));
        frames.add(protocol.snapshot(NavigationFrame.status(1),null,100001));
        nav=protocol.snapshot(live,route,100002);frames.add(nav);
        frames.add(protocol.details(1,nav.sequence(),new NavigationDetails("","",-1,-1)));
        var output=new StringBuilder();for(var f:frames){check(f.bytes().length<=157,"bounded details fixture");output.append(HexFormat.of().formatHex(f.bytes())).append('\n');}
        if(args.length>0)Files.writeString(Path.of(args[0]),output);
        System.out.println("PASS: HUD fields, UTF-8 truncation, unknown values and binary fixtures");
    }
}
