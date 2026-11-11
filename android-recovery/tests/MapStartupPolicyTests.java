import com.motonav.companion.MapStartupPolicy;
public class MapStartupPolicyTests {
 static int n; static void check(boolean value){n++;if(!value)throw new AssertionError(n);}
 public static void main(String[] args){
 for(int bits=0;bits<512;bits++){
 boolean f=(bits&1)!=0,m=(bits&2)!=0,c=(bits&4)!=0,p=(bits&8)!=0,fine=(bits&16)!=0,coarse=(bits&32)!=0,asked=(bits&64)!=0,retry=(bits&128)!=0,rationale=(bits&256)!=0;
 MapStartupPolicy.Action result=MapStartupPolicy.location(f,m,c,p,fine,coarse,asked,retry,rationale);
 if(!f||!m||!c||p)check(result==MapStartupPolicy.Action.WAIT);
 else if(fine)check(result==MapStartupPolicy.Action.LOCATE);
 else if(coarse)check(result==(!retry?MapStartupPolicy.Action.LOCATE:(rationale?MapStartupPolicy.Action.REQUEST:MapStartupPolicy.Action.SHOW_SETTINGS)));
 else if(!asked||retry&&rationale)check(result==MapStartupPolicy.Action.REQUEST);
 else check(result==MapStartupPolicy.Action.SHOW_SETTINGS);
 }
 check(!MapStartupPolicy.fresh(100,0));check(!MapStartupPolicy.fresh(100,101));check(MapStartupPolicy.fresh(15000000001L,1));check(!MapStartupPolicy.fresh(15000000002L,1));
 check(MapStartupPolicy.fixTime(20000000000L,100000,0,99000)==19000000000L);
 check(MapStartupPolicy.fixTime(20000000000L,100000,0,80000)==0);
 check(MapStartupPolicy.fixTime(20000000000L,100000,0,100001)==0);
 check(MapStartupPolicy.fixTime(20000000000L,100000,0,0)==0);
 check(MapStartupPolicy.fixTime(20000000000L,100000,19900000000L,0)==19900000000L);
 check(MapStartupPolicy.fixTime(20000000000L,100000,20000000001L,99000)==0);
 System.out.println("MapStartupPolicy: "+n+" checks passed");
 }
}
