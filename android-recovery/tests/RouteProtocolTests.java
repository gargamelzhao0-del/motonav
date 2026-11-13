import com.motonav.companion.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

public final class RouteProtocolTests {
    static int checks;
    static void check(boolean value,String text) { checks++; if(!value)throw new AssertionError(text); }
    static void verify(RouteProtocol.Command command) {
        byte[] bytes=command.bytes(); ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(bytes.length<=304 && b.getShort(2)==bytes.length,"bounded length");
        CRC32 crc=new CRC32();crc.update(bytes,0,bytes.length-4);
        check(Integer.toUnsignedLong(b.getInt(bytes.length-4))==crc.getValue(),"whole frame CRC");
    }
    public static void main(String[] args) throws Exception {
        DeviceRoute geometry=new DeviceRoute();
        geometry.replace(List.of(new DeviceRoute.Coordinate(39.98,116.48),new DeviceRoute.Coordinate(39.983,116.48),
            new DeviceRoute.Coordinate(39.983,116.484),new DeviceRoute.Coordinate(39.987,116.484)));
        DeviceRoute.Frame map=geometry.frame(39.982,116.48,0);
        check(map!=null && map.points().size()==4,"public GCJ route exported");
        check(map.x()==0 && map.y()==0 && map.heading()==0,"origin and true-north heading");
        check(map.points().get(0).y()<0 && map.points().get(3).x()>0,"north/east axis direction");
        check(geometry.frame(39.9821,116.48,90).revision()==map.revision(),"near movement reuses geometry frame");
        check(geometry.frame(39.985,116.484,90).revision()>map.revision(),"moving window changes revision");
        check(geometry.frame(0,0,0)==null,"off-route position never invents a connection");
        check(geometry.frame(39.982,116.48,0)!=null,"returning from missing map rebuilds valid origin");
        geometry.clear();check(geometry.frame(39.982,116.48,0)==null,"route clear removes map");
        RouteProtocol protocol=new RouteProtocol(17);
        ArrayList<RouteProtocol.Command> frames=new ArrayList<>(); frames.add(protocol.hello());
        NavigationFrame live=new NavigationFrame(2,3,120,4000,360,36,100000,100000);
        frames.add(protocol.snapshot(live,map,100000));
        frames.add(protocol.snapshot(live,map,101000));
        frames.add(protocol.snapshot(live,map,116000));
        frames.add(protocol.snapshot(NavigationFrame.status(4),map,116001));
        List<DeviceRoute.Point> maximum=new ArrayList<>(); for(int i=0;i<64;i++)maximum.add(new DeviceRoute.Point(i*10,i%2==0?20:40));
        RouteProtocol.Command full=protocol.snapshot(new NavigationFrame(2,2,10,4000,360,-1,120000,120000),new DeviceRoute.Frame(9,maximum,0,0,-1),120000);
        verify(full);check(full.bytes().length==304,"maximum snapshot is 304 bytes");
        for(RouteProtocol.Command c:frames)verify(c);
        check(frames.get(3).bytes().length==48 && frames.get(3).bytes()[13]==1,"expired guidance strips geometry atomically");
        check(ByteBuffer.wrap(frames.get(1).bytes()).order(ByteOrder.LITTLE_ENDIAN).getShort(29)==3000,"transport age budget");
        StringBuilder fixture=new StringBuilder();for(RouteProtocol.Command c:frames)fixture.append(HexFormat.of().formatHex(c.bytes())).append('\n');
        fixture.append(HexFormat.of().formatHex(full.bytes())).append('\n');
        if(args.length>0)Files.writeString(Path.of(args[0]),fixture);
        System.out.println("NAV4: "+checks+" route projection, atomic snapshot and CRC checks passed");
    }
}
