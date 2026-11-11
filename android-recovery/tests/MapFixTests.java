import com.motonav.companion.MapStartupPolicy;
public class MapFixTests {
    static int count;
    static void check(boolean value){count++;if(!value)throw new AssertionError("check "+count);}
    public static void main(String[] args){
        check(MapStartupPolicy.validFix(31.2,121.5,8,false));
        check(!MapStartupPolicy.validFix(0,0,8,false));
        check(!MapStartupPolicy.validFix(31,121,8,true));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,181,-181})check(!MapStartupPolicy.validFix(31,bad,8,false));
        for(double bad:new double[]{Double.NaN,91,-91})check(!MapStartupPolicy.validFix(bad,121,8,false));
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,-1})check(!MapStartupPolicy.validFix(31,121,bad,false));
        check(MapStartupPolicy.locationError(32).contains("SHA1"));check(MapStartupPolicy.locationError(7).contains("SHA1"));
        check(MapStartupPolicy.locationError(4).contains("网络"));check(MapStartupPolicy.locationError(12).contains("12"));
        for(boolean permission:new boolean[]{false,true})for(boolean enabled:new boolean[]{false,true}){
            check(MapStartupPolicy.usableSnapshot(permission,enabled,20000000000L,19000000000L)==(permission&&enabled));
            check(!MapStartupPolicy.usableSnapshot(permission,enabled,20000000000L,1));
        }
        System.out.println("MapFix: "+count+" checks passed");
    }
}
