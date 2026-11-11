import com.motonav.companion.JourneyState;
public class JourneyStateTests {
 static int n;static void check(boolean x){n++;if(!x)throw new AssertionError(n);}
 public static void main(String[]args){
  JourneyState s=new JourneyState();check(s.begin(false,true)<0);check(s.begin(true,false)<0);
  long a=s.begin(true,true);check(a>0);check(s.begin(true,true)<0);check(!s.ready(a-1,100));check(s.ready(a,100));check(!s.ready(a,101));
  check(s.start(true,60100,60));check(!s.start(true,60100,0));check(s.started(a));check(!s.started(a));check(s.active(a));s.cancel();check(!s.active(a));check(!s.ready(a,2));
  for(double moved:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,61}){long t=s.begin(true,true);check(s.ready(t,10));check(!s.start(true,20,moved));check(s.phase()==JourneyState.Phase.IDLE);}
  long t=s.begin(true,true);check(s.ready(t,100));check(!s.start(true,60101,0));t=s.begin(true,true);check(s.ready(t,100));check(!s.start(false,101,0));
  check(JourneyState.usableFix(true,false,10,100,99));check(!JourneyState.usableFix(false,false,10,100,99));check(!JourneyState.usableFix(true,true,10,100,99));check(!JourneyState.usableFix(true,false,101,100,99));check(!JourneyState.usableFix(true,false,Float.NaN,100,99));
  check(JourneyState.displacement("150")==150);check(JourneyState.displacement("0")==0);check(JourneyState.displacement("-1")==0);check(JourneyState.displacement("10001")==0);check(JourneyState.displacement("bad")==0);
  check(JourneyState.coordinate(30,120));check(!JourneyState.coordinate(Double.NaN,120));check(!JourneyState.coordinate(91,120));check(!JourneyState.coordinate(30,181));check(JourneyState.routeError(16).contains("权限不足"));
  check(JourneyState.maneuver(0).equals("请核对地图路线"));check(JourneyState.maneuver(1).equals("请核对地图路线"));check(JourneyState.maneuver(999).equals("请核对地图路线"));check(JourneyState.maneuver(2).equals("左转"));check(JourneyState.maneuver(3).equals("右转"));check(JourneyState.maneuver(9).equals("直行"));
  for(int i=0;i<1000;i++){long token=s.begin(true,true);s.cancel();check(!s.ready(token,i));check(!s.started(token));}
  guidanceRecovery();
  System.out.println("JourneyState: "+n+" checks passed");
 }
 private static void guidanceRecovery() {
  JourneyState state = new JourneyState();
  long token = state.begin(true, true);
  check(state.ready(token, 1000));
  check(state.start(true, 1500, 0));
  check(state.started(token));
  state.startGuidance(2000);
  check(state.guidance(2500, 2_400_000_000L, true) == JourneyState.Guidance.LIVE);

  check(state.guidance(3000, 0, false) == JourneyState.Guidance.WAITING);
  check(state.guidance(3500, 2_900_000_000L, true) == JourneyState.Guidance.WAITING);
  check(state.guidance(3700, 3_600_000_000L, false) == JourneyState.Guidance.WAITING);
  check(state.guidance(3800, 3_600_000_000L, true) == JourneyState.Guidance.LIVE);
  check(state.active(token));

  check(state.guidance(19_000, 18_900_000_000L, false) == JourneyState.Guidance.WAITING);
  check(state.guidance(63_999, 0, false) == JourneyState.Guidance.WAITING);
  check(state.guidance(64_000, 0, false) == JourneyState.Guidance.WAITING);
  check(state.guidance(120_000, 119_900_000_000L, true) == JourneyState.Guidance.LIVE);
  state.cancel();
  check(!state.active(token));
 }
}
