import com.motonav.companion.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

public final class BleDiagnosticProtocolTests {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("DIAG1 check "+checks);}
    public static void main(String[] args) throws Exception {
        List<String> fixtures=new ArrayList<>();
        var o=new BleDiagnosticProtocol.Observation(14,5,0,2,100,200,300,400,125);
        var route=new DeviceRoute.Frame(1,List.of(new DeviceRoute.Point(0,0),new DeviceRoute.Point(100,100)),0,0,900);
        for(int protocol:new int[]{3,4})for(int age:new int[]{0,12000,12001,15000,15001}){
            var source=new NavigationFrame(2,3,100,2000,300,30,50000-age,50000-age);
            int seq,wireMode,points;
            if(protocol==4){var command=new RouteProtocol(123).snapshot(source,route,50000);seq=command.sequence();wireMode=command.bytes()[13]&255;points=command.bytes()[37]&255;}
            else{var command=new NavigationProtocol(123).snapshot(source,50000);seq=command.sequence();wireMode=Integer.parseInt(command.wire().split(",")[4]);points=0;}
            var t=BleDiagnosticProtocol.navigation(source,2,wireMode,points,protocol,seq,50000);
            byte[] b=BleDiagnosticProtocol.snapshot(7654,fixtures.size()+1,50001,o,t,protocol,seq,1,0,2,22,false);
            check(b.length==88&&b[76]==2&&(b[77]&255)==wireMode&&b[80]==points);
            check(b[81]==(age<=12000?0:age<=15000?4:5));
            CRC32 crc=new CRC32();crc.update(b,0,84);check(crc.getValue()==Integer.toUnsignedLong(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(84)));
            byte[] restored=new byte[88];int fragments=0;
            for(int offset=0;offset<88;offset+=16){byte[] part=BleDiagnosticProtocol.fragment(b,offset);check(part.length<=20&&part[0]==(byte)0xd1&&part[2]==offset);System.arraycopy(part,4,restored,offset,part.length-4);fragments++;}
            check(fragments==6&&Arrays.equals(b,restored));
            fixtures.add(HexFormat.of().formatHex(b));
        }
        var waiting=new BleDiagnosticProtocol.Observation(3,5,1,1,BleDiagnosticProtocol.UNKNOWN,20,30,40,BleDiagnosticProtocol.UNKNOWN);
        byte[] idle=BleDiagnosticProtocol.snapshot(123,1,100,waiting,null,4,1,BleDiagnosticProtocol.UNKNOWN,0,0,22,true);
        check(idle[76]==0&&idle[77]==0&&idle[82]==1&&(idle[72]&255)==19);
        fixtures.add(HexFormat.of().formatHex(idle));
        check(BleDiagnosticProtocol.age(100,0)==BleDiagnosticProtocol.UNKNOWN);
        check(BleDiagnosticProtocol.age(100,101)==BleDiagnosticProtocol.UNKNOWN);
        check(BleDiagnosticProtocol.age(50000,1)==49999);
        Files.write(Path.of(args[0]),fixtures);
        System.out.println("PASS: "+checks+" DIAG1 encoder checks, actual NAV3/NAV4 age boundaries, "+fixtures.size()+" wire fixtures");
    }
}
