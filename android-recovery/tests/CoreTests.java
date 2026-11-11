import com.motonav.companion.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class CoreTests {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("Check "+checks);}
    static void equal(double a,double b){check(Math.abs(a-b)<.001);}
    public static void main(String[] args){
        Simulation s=new Simulation();equal(s.position().x(),0);equal(s.position().y(),0);check(s.position().turn().equals("右转"));
        s.advance(500);check(s.distanceMm==0);s.running=true;s.advance(500);check(s.distanceMm==6000);
        s.running=false;s.advance(500);check(s.distanceMm==6000);
        s.reset();s.distanceMm=240000;equal(s.position().x(),0);equal(s.position().y(),240);check(s.position().turn().equals("左转"));
        s.distanceMm=420000;equal(s.position().x(),180);equal(s.position().y(),240);check(s.position().turn().equals("左转"));
        s.reset();s.running=true;for(int i=0;i<300;i++)s.advance(500);check(s.distanceMm==1800000);check(!s.running);equal(s.position().x(),240);equal(s.position().y(),960);equal(s.position().turnDistance(),0);
        s.reset();s.running=true;s.advance(100000);check(s.distanceMm==12000);s.advance(-1);check(s.distanceMm==12000);
        Protocol p=new Protocol();check(p.hello().wire().equals("H,1,1\n"));check(p.route().wire().equals("R,1,2,1\n"));check(p.position(0,12000,true).wire().equals("P,1,3,0,12000,1\n"));check(p.stop().wire().equals("X,1,4\n"));
        check(Protocol.acknowledged("A,4,OK",4));check(!Protocol.acknowledged("A,3,OK",4));check(!Protocol.acknowledged("A,4,ERR",4));p.reset();check(p.hello().sequence()==1);
        for(int distance=0;distance<=1800000;distance+=6000){
            String wire=p.position(distance,12000,false).wire();StringBuilder joined=new StringBuilder();
            for(byte[] chunk:Protocol.chunks(wire)){check(chunk.length<=20&&chunk.length>0);joined.append(new String(chunk,StandardCharsets.US_ASCII));}
            check(joined.toString().equals(wire));
            s.distanceMm=distance;Simulation.Position pos=s.position();check(Double.isFinite(pos.x())&&Double.isFinite(pos.y()));check(pos.turnDistance()>=0);
        }
        boolean rejected=false;try{p.position(-1,12000,false);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        rejected=false;try{p.position(0,60001,false);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        System.out.println("PASS: "+checks+" checks (simulation, boundaries, protocol, ACK matching, MTU-23 chunking)");
    }
}
