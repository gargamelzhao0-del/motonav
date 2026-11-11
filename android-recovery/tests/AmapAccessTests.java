import com.motonav.companion.AmapAccess;
public class AmapAccessTests {
 public static void main(String[] args){int checks=0;String[] keys={null,""," ","请输入Key","not-a-key","1234567890123456789012345678901g","0123456789abcdef0123456789abcdef"};for(int i=0;i<keys.length;i++)for(boolean consent:new boolean[]{false,true})for(boolean foreground:new boolean[]{false,true}){boolean expected=i==keys.length-1&&consent&&foreground;if(AmapAccess.canInitialize(keys[i],consent,foreground)!=expected)throw new AssertionError();checks++;}if(!AmapAccess.navigationStatus().contains("未启用"))throw new AssertionError();System.out.println("AmapAccessTests: "+(++checks)+" checks passed");}
}
