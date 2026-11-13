import com.motonav.companion.NaviFixTracker;
public final class NaviFixTests {
    static int checks;
    static void check(boolean value) { if(!value)throw new AssertionError("check "+checks); checks++; }
    public static void main(String[] args) {
        NaviFixTracker tracker=new NaviFixTracker(); long wall=1700000000000L, now=100000000000L;
        for(int second=0;second<1800;second++) {
            long n=now+second*1000000000L,w=wall+second*1000;
            check(tracker.offer(true,n,w,0,w-100,30,120,8,36,90));
            check(tracker.current(n).timeNanos()==n-100000000L);
            check(!tracker.offer(true,n+100000000L,w+100,0,w-100,30,120,8,36,90));
            check(tracker.current(n+100000000L).timeNanos()==n-100000000L);
        }
        long n=now+1800000000000L,w=wall+1800000;
        check(!tracker.offer(false,n,w,0,w,30,120,8,36,90));
        check(!tracker.offer(true,n,w,1,w,30,120,8,36,90));
        check(!tracker.offer(true,n,w,0,w,30,120,550,36,90));
        check(!tracker.offer(true,n,w,0,w,Double.NaN,120,8,36,90));
        check(!tracker.offer(true,n,w,0,w+1,30,120,8,36,90));
        check(!tracker.offer(true,n,w,0,w-15001,30,120,8,36,90));
        check(tracker.current(n+15000000000L)==null);
        check(tracker.offer(true,n+16000000000L,w+16000,0,w+16000,30,120,8,0,Float.NaN));
        check(tracker.current(n+16000000000L).speedKmh()==-1);
        tracker.clear(); check(tracker.current(n+16000000000L)==null);
        System.out.println("NaviFix: "+checks+" checks; 30-minute deterministic GPS stream and expiry/recovery passed");
    }
}
