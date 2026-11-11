import com.motonav.companion.AmapKeySession;
public class AmapKeySessionTests {
 public static void main(String[] x){int checks=0;AmapKeySession s=new AmapKeySession();for(int i=0;i<100;i++){if(s.configured())throw new AssertionError();checks++;if(!s.replace("0123456789abcdef0123456789abcdef"))throw new AssertionError();checks++;if(!s.configured()||!s.sdkValue().equals("0123456789abcdef0123456789abcdef"))throw new AssertionError();checks++;if(s.replace("invalid")||s.configured()||!s.sdkValue().isEmpty())throw new AssertionError();checks++;s.replace("ABCDEF0123456789ABCDEF0123456789");s.clear();s.clear();if(s.configured()||!s.sdkValue().isEmpty())throw new AssertionError();checks++;}System.out.println("AmapKeySessionTests: "+checks+" checks passed");}
}
