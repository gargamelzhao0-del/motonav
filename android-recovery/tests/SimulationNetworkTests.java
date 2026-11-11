import com.motonav.companion.*;
public final class SimulationNetworkTests {
    private static int checks;
    private static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    private static void equal(double a,double b){check(Math.abs(a-b)<0.001);}
    public static void main(String[] args){
        Simulation s=new Simulation();check(s.totalMm()==1800000);
        LocalNetwork detour=LocalNetwork.demo(21,true);s.setRoute(detour.points());check(s.totalMm()==2040000);check(!s.running&&s.distanceMm==0);
        double[][] returned=s.route();returned[0][0]=999;equal(s.position().x(),0);
        s.running=true;for(int i=0;i<340;i++){s.advance(500);check(s.distanceMm<=s.totalMm());Simulation.Position p=s.position();check(Double.isFinite(p.x())&&Double.isFinite(p.y())&&p.turnDistance()>=0);}
        check(s.distanceMm==2040000&&!s.running);equal(s.position().x(),240);equal(s.position().y(),960);
        s.reset();s.distanceMm=360000;check(s.position().turn().equals("直行"));
        int old=s.totalMm();boolean rejected=false;try{s.setRoute(new double[][]{{0,0},{0,0}});}catch(IllegalArgumentException e){rejected=true;}check(rejected&&s.totalMm()==old);equal(s.position().x(),-120);
        rejected=false;try{s.setRoute(new double[][]{{0,0},{Double.NaN,1}});}catch(IllegalArgumentException e){rejected=true;}check(rejected&&s.totalMm()==old);
        double[][] diagonal={{0,0},{1,1},{2,2}};s.setRoute(diagonal);diagonal[1][0]=999;check(s.totalMm()==2828);s.distanceMm=1414;equal(s.position().x(),1);equal(s.position().y(),1);check(s.position().turn().equals("终点"));s.distanceMm=2828;equal(s.position().x(),2);
        s.setRoute(Simulation.ROUTE);check(s.totalMm()==1800000&&s.distanceMm==0&&!s.running);
        System.out.println("PASS: "+checks+" dynamic-route simulation checks (detour, straight, arrival, rounded boundaries, copy isolation, validation rollback)");
    }
}
