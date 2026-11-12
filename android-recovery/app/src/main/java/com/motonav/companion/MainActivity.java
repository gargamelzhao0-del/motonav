package com.motonav.companion;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Product shell. All in-app map geometry is synthetic; external maps never feed BLE. */
public final class MainActivity extends Activity implements BleLink.Listener {
    private static final int BG=0xff101411,PANEL=0xff1b221d,GREEN=0xffc4fa64,WHITE=0xfff2f3e9,MUTED=0xffa7b0a5,ORANGE=0xffff9b52;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Simulation simulation=new Simulation();
    private final Set<String> discovered=new HashSet<>();
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private BleLink link;
    private LinearLayout root,body,tabs,deviceList,searchResults;
    private TextView statusLine,metrics,turn,ackLine,deviceStatus,logView,locationDetails;
    private Button locationToggle;
    private PhoneLocation phoneLocation;
    private long maximumLocationAgeMs=15000;
    private double maximumLocationAccuracy=50;
    private Button play;
    private CityMap map;
    private int page,ackCount,lastAck;
    private long lastTick,lastAckAt=-1;
    private boolean visible,planned,headingUp,v2Mode,detour;
    private LocalNetwork localNetwork=LocalNetwork.demo(1,false);
    private String linkStatus="未连接设备",query="";
    private final MapProvider provider=new DemoMapProvider();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        headingUp=getPreferences(MODE_PRIVATE).getBoolean("heading_up",false);
        maximumLocationAgeMs=getPreferences(MODE_PRIVATE).getLong("location_age_ms",15000);
        maximumLocationAccuracy=getPreferences(MODE_PRIVATE).getInt("location_accuracy_m",50);
        if(state!=null){v2Mode=state.getBoolean("network_v2",false);detour=state.getBoolean("network_detour",false);localNetwork=LocalNetwork.demo(1,detour);if(v2Mode)simulation.setRoute(localNetwork.points());simulation.distanceMm=Math.min(simulation.totalMm(),state.getInt("distance",0));planned=state.getBoolean("planned",false);page=state.getInt("page",0);}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,bars.top,0,bars.bottom);return insets;});
        LinearLayout header=column();header.setPadding(dp(22),dp(14),dp(22),dp(10));
        LinearLayout title=row();TextView brand=text("MOTO / NAV",24,WHITE);brand.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);title.addView(brand,new LinearLayout.LayoutParams(0,-2,1));TextView badge=text("DEMO 05",11,BG);badge.setPadding(dp(10),dp(5),dp(10),dp(5));badge.setBackground(round(GREEN,4));title.addView(badge);header.addView(title);
        statusLine=text("虚构地图 · 模拟路线 · 不可用于骑行",11,MUTED);header.addView(statusLine);root.addView(header);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);body=column();body.setPadding(dp(20),0,dp(20),dp(18));scroller.addView(body);root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        tabs=row();tabs.setPadding(dp(8),dp(5),dp(8),dp(7));tabs.setBackgroundColor(PANEL);root.addView(tabs);setContentView(root);
        link=new BleLink(this,this);link.configureNetwork(v2Mode?localNetwork:null);phoneLocation=new PhoneLocation(this,this::renderLocation);showPage(page);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(6),0,dp(6));return t;}
    private Button button(String s,boolean primary,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(primary?BG:WHITE);b.setTextSize(14);b.setBackground(round(primary?GREEN:0xff29342c,7));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(5),0,dp(5));b.setLayoutParams(p);b.setOnClickListener(v->action.run());return b;}
    private void note(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void heading(String n,String s){body.addView(text(n,11,GREEN));TextView h=text(s,27,WHITE);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(h);}
    private void copy(String s){body.addView(text(s,13,MUTED));}
    private void showPage(int p){
        page=p;body.removeAllViews();map=null;metrics=null;turn=null;play=null;ackLine=null;deviceList=null;deviceStatus=null;logView=null;searchResults=null;locationDetails=null;locationToggle=null;
        tabs.removeAllViews();String[] labels={"探索","导航","设备","定位","设置"};for(int i=0;i<labels.length;i++){final int target=i;Button b=button(labels[i],i==page,()->showPage(target));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(46),1);params.setMargins(dp(3),0,dp(3),0);tabs.addView(b,params);}
        switch(page){case 0:explore();break;case 1:ride();break;case 2:devices();break;case 3:locationPage();break;default:settings();}
        render();
    }
    private void addMap(int height){map=new CityMap();body.addView(map,new LinearLayout.LayoutParams(-1,dp(height)));}
    private void explore(){
        heading("01 / CITY EXPLORER","下一站，霓虹码头。");
        copy("独立绘制的虚构城区，不是高德或真实地图。");addMap(280);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(WHITE);search.setHintTextColor(MUTED);search.setTextSize(15);search.setHint("搜索演示目的地，例如：码头");search.setText(query);body.addView(search);
        searchResults=column();body.addView(searchResults);populateSearch(query);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();populateSearch(query);}public void afterTextChanged(Editable e){}});
        body.addView(button("打开高德地图 · 独立联网模块",false,()->startActivity(new Intent(this,AmapActivity.class))));
        body.addView(button("在真实地图 App 中搜索…",false,this::externalMap));
        copy("内嵌高德地图可在手机输入 Key 并主动同意；外部搜索交给所选地图 App。本 App 不读取外部导航数据，也不向 ESP32 转发真实路线。");
    }
    private void populateSearch(String q){
        if(searchResults==null)return;searchResults.removeAllViews();
        if(provider.search(q).isEmpty()){searchResults.addView(text("未找到演示地点。试试“码头”或下方真实地图搜索。",13,MUTED));return;}
        searchResults.addView(text("虚构目的地  /  霓虹码头 · NEON WHARF",16,WHITE));
        searchResults.addView(text(String.format(Locale.CHINA,"旧城车库出发 · %s · %.2f km",v2Mode?(detour?"局部路网v2 / 绕行":"局部路网v2 / 标准"):"内置v1路线",simulation.totalMm()/1000000.0),12,MUTED));
        searchResults.addView(button(planned?"查看已选路线":"预览这条模拟路线",true,this::planDemoRoute));
    }
    private void planDemoRoute(){
        try { MapProvider.Route route=provider.routeTo(DemoMapProvider.DESTINATION_ID); if(!provider.isSimulation()||route.routeId()!=1)throw new IllegalStateException("设备协议不支持此路线"); planned=true;showPage(1); }
        catch(IllegalArgumentException|IllegalStateException e){note(e.getMessage());}
    }
    private void ride(){
        heading("02 / ROUTE CONTROL",planned?"旧城车库 → 霓虹码头":"先选择一个目的地");
        if(!planned){copy("请在探索页选择演示目的地。设备页可在断开时选择v1兼容或v2局部路网；真实路线服务未接入。");body.addView(button("探索演示路线",true,()->showPage(0)));return;}
        turn=text("",28,GREEN);turn.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(turn);addMap(300);
        metrics=text("",16,WHITE);body.addView(metrics);
        play=button("开始手机模拟",true,this::toggleSimulation);body.addView(play);
        LinearLayout actions=row();Button reset=button("复位路线",false,this::resetRoute),follow=button("切换朝向",false,()->{headingUp=!headingUp;getPreferences(MODE_PRIVATE).edit().putBoolean("heading_up",headingUp).apply();render();});
        actions.addView(reset,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,dp(48),1);second.setMargins(dp(8),0,0,0);actions.addView(follow,second);body.addView(actions);
        ackLine=text("",12,MUTED);body.addView(ackLine);
        body.addView(button("发送模拟位置到已连接设备",false,()->{if(!link.ready())note("请先在设备页连接 ESP32。没有发送数据。");else if(!link.sendPosition(simulation,null))note("正在等待上一条回执，请稍后。");}));
        body.addView(button("结束这次模拟",false,()->new AlertDialog.Builder(this).setTitle("结束模拟？").setMessage("结束模拟、断开设备，并关闭已开启的真实定位、清除坐标后返回探索页。不会发送真实导航或 SOS。").setNegativeButton("继续查看",null).setPositiveButton("结束",(d,w)->{simulation.reset();planned=false;phoneLocation.stop();link.disconnect();showPage(0);}).show()));
        copy("手机可以独立模拟；只有真实 BLE 连接并收到固件 ACK 才显示设备确认。真实定位在独立面板，不参与这条演示路线；无语音或后台导航。");
    }
    private void toggleSimulation(){
        if(simulation.distanceMm==simulation.totalMm()){note("已到达，请先复位路线。");return;}
        if(link.ready()&&link.busy()){note("等待设备回执后再操作。");return;}
        simulation.running=!simulation.running;lastTick=SystemClock.elapsedRealtime();if(link.ready())link.sendPosition(simulation,null);render();
    }
    private void resetRoute(){
        if(link.ready()&&link.busy()){note("等待设备回执后再复位。");return;}
        simulation.running=false;
        if(link.ready())link.resetRoute(()->{simulation.reset();link.sendPosition(simulation,null);render();});else simulation.reset();render();
    }
    private void devices(){
        heading("03 / DEVICE LINK","把模拟路线送到圆屏。");
        deviceStatus=text(linkStatus,17,GREEN);body.addView(deviceStatus);
        copy("ESP32-S3 · 显式选择兼容协议\nv1兼容固件0.3/0.4；v2必须使用配套0.4固件，无自动降级。");
        body.addView(text(v2Mode?"当前：v2 LOCAL_METERS_V1 / "+(detour?"绕行2.04km":"标准1.80km"):"当前：v1 内置1.80km路线（默认兼容）",14,GREEN));
        body.addView(button(v2Mode?"切回v1兼容模式":"启用v2模拟局部路网",false,()->{
            if(link.sessionOpen()){note("先断开设备再切换协议，避免混入当前事务。");return;}
            if(v2Mode){selectNetworkMode(false,false);return;}
            new AlertDialog.Builder(this).setTitle("确认使用配套0.4固件？").setMessage("v2将上传虚构局部米制路网、路线与校验数据。旧0.3固件不能接收，错误或超时会安全断开，不自动降级。真实GPS不会发送。").setNegativeButton("取消",null).setPositiveButton("启用v2",(d,w)->selectNetworkMode(true,false)).show();
        }));
        if(v2Mode){body.addView(button(detour?"选择标准路线 1.80 km":"选择绕行路线 2.04 km",false,()->{if(link.sessionOpen()){note("先断开设备，再改变局部路网与路线。");return;}selectNetworkMode(true,!detour);}));copy("坐标：LOCAL_METERS_V1，虚构局部米数。10节点 / 10边。逐帧确认，长度和CRC32校验成功提交后才允许位置同步。");}
        body.addView(button("扫描附近设备",true,this::requestScan));deviceList=column();body.addView(deviceList);
        body.addView(button("断开设备并暂停模拟",false,()->{link.disconnect();status("已主动断开；模拟已暂停。");}));
        ackLine=text("",13,MUTED);body.addView(ackLine);
        copy("安全提示：该演示 BLE 未认证、未加密，设备名不是身份凭证。只在受控桌面环境测试。连接会将双方复位到路线起点；v2上传未完成不会宣称已同步。CRC用于完整性，不提供认证或加密。");
        logView=text("",12,MUTED);logView.setTypeface(Typeface.MONOSPACE);body.addView(logView);
    }
    private void selectNetworkMode(boolean enabled,boolean chooseDetour){
        if(link.sessionOpen())return;
        v2Mode=enabled;detour=enabled&&chooseDetour;simulation.running=false;
        localNetwork=LocalNetwork.demo(1,detour);simulation.setRoute(v2Mode?localNetwork.points():Simulation.ROUTE);
        link.configureNetwork(v2Mode?localNetwork:null);status("协议/路线已变更，请重新连接；手机模拟已复位。");showPage(2);
    }
    private void connectSelectedDevice(BluetoothDevice device){
        if(link.sessionOpen()){note("已有连接或连接尝试，请先断开。");return;}
        if(v2Mode){
            android.content.SharedPreferences prefs=getSharedPreferences("protocol_state",MODE_PRIVATE);
            int seed=(int)(System.currentTimeMillis()%2147483646L)+1;
            int last=prefs.getInt("last_map_id",seed),next=last==Integer.MAX_VALUE?1:last+1;
            prefs.edit().putInt("last_map_id",next).apply();
            localNetwork=LocalNetwork.demo(next,detour);simulation.setRoute(localNetwork.points());
            if(!link.configureNetwork(localNetwork)){note("请先关闭已有连接。");return;}
        }else{simulation.setRoute(Simulation.ROUTE);link.configureNetwork(null);}
        link.connect(device);
    }
    private void settings(){
        heading("05 / SYSTEM","风格在外，边界清楚。");
        body.addView(text("地图提供者",18,WHITE));
        copy("● DEMO / 已启用\n本地虚构城区、目的地搜索和固定路线。无需联网或密钥。");
        copy("○ 高德地图 SDK / 已集成，可在手机输入Key\n真实地图页提供普通驾车导航。Android Key 在本机加密保存，需匹配包名和安装签名，并同意隐私说明。此模拟页面保持虚构数据，不发送真实路线。");
        body.addView(button("查看高德接入边界",false,()->new AlertDialog.Builder(this).setTitle("高德接入状态：SDK已集成 / 服务未验证").setMessage("真实地图页已提供地点搜索、自动定位和普通驾车导航，无需填写车牌、排量。普通驾车路线不含摩托车禁限行避让。Android Key 在本机加密保存；导航服务仍需匹配有效的 Key、包名与安装签名。\n\n本页面的模拟功能使用虚构路线，和真实导航分开。真实导航页可连接圆屏，通过 BLE v3 发送转向、距离、车速及有效状态。").setPositiveButton("明白",null).show()));
        body.addView(button("真实地图 App 搜索",false,this::externalMap));
        Switch north=new Switch(this);north.setText("地图跟随行驶方向");north.setTextColor(WHITE);north.setChecked(headingUp);north.setPadding(0,dp(18),0,dp(18));north.setOnCheckedChangeListener((v,checked)->{headingUp=checked;getPreferences(MODE_PRIVATE).edit().putBoolean("heading_up",checked).apply();});body.addView(north);
        body.addView(text("隐私与运行",18,WHITE));
        copy("联网权限仅供用户主动同意后打开的独立高德地图模块使用；真实定位默认关闭，仅在你主动开启后前台读取，离开App立即停止并清除坐标。不保存搜索历史、设备地址或真实位置，仅保存地图朝向、质量阈值及协议数据版本计数。离开App即暂停并断开设备，不后台跟踪、不自动重连。\n\n旧版Android的BLE扫描可能要求定位权限；扫描本身不会启动真实定位。外部地图App有自己的权限与隐私政策。");
        body.addView(button("清除本机显示与质量偏好",false,()->new AlertDialog.Builder(this).setTitle("清除显示与质量阈值偏好？").setMessage("恢复地图朝向、定位时效和精度阈值，不删除其他App数据。").setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{getPreferences(MODE_PRIVATE).edit().clear().apply();headingUp=false;maximumLocationAgeMs=15000;maximumLocationAccuracy=50;showPage(4);}).show()));
        copy("MOTONAV " + BuildConfig.VERSION_NAME + " / AMAP FOUNDATION\n独立设计的街机都市风格。不是 Rockstar / GTA 官方应用，不含其地图、商标或游戏素材。\n尚未完成手机与开发板实机验收，不可用于真实骑行。");
    }
    private void locationPage(){
        heading("04 / REAL LOCATION","真实位置，独立看清。");
        copy("这里读取手机系统定位；不把真实坐标套在虚构地图上，也不发送给 ESP32 或外部地图 App。坐标质量合格不表示已具备导航能力。");
        locationDetails=text("",16,WHITE);locationDetails.setTypeface(Typeface.MONOSPACE);body.addView(locationDetails);
        locationToggle=button("主动开启真实定位",true,()->{if(phoneLocation.active())phoneLocation.stop();else requestRealLocation();});body.addView(locationToggle);
        body.addView(button("查看系统定位开关",false,()->{try{startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));}catch(ActivityNotFoundException e){note("请在系统设置中手动检查定位开关。");}}));
        copy("选择大致位置也可继续，只会标注为大致；精确权限和GPS信号均不保证精度。系统测试/模拟位置会单独标记。位置过期、权限撤回或退出前台都不会继续宣称有效。");
        body.addView(text("时效阈值（仅影响质量标记）",14,GREEN));
        Spinner age=new Spinner(this);String[] ages={"15 秒","30 秒","60 秒"};long[] ageValues={15000,30000,60000};ArrayAdapter<String> ageAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ages);age.setAdapter(ageAdapter);age.setSelection(maximumLocationAgeMs==30000?1:maximumLocationAgeMs==60000?2:0);age.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){maximumLocationAgeMs=ageValues[position];getPreferences(MODE_PRIVATE).edit().putLong("location_age_ms",maximumLocationAgeMs).apply();renderLocation();}});body.addView(age);
        body.addView(text("精度阈值（仅影响质量标记）",14,GREEN));
        Spinner accuracy=new Spinner(this);String[] accuracies={"50 米","100 米","200 米"};int[] accuracyValues={50,100,200};accuracy.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accuracies));accuracy.setSelection(maximumLocationAccuracy==100?1:maximumLocationAccuracy==200?2:0);accuracy.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){maximumLocationAccuracy=accuracyValues[position];getPreferences(MODE_PRIVATE).edit().putInt("location_accuracy_m",(int)maximumLocationAccuracy).apply();renderLocation();}});body.addView(accuracy);
        copy("真实坐标不持久化、不导出、不上传。只保存上述显示阈值。位置提供者和系统设置可能影响耗电；结束查看后请关闭。进入后台会自动关闭，返回后需主动重新开启。");renderLocation();
    }
    private void requestRealLocation(){
        new AlertDialog.Builder(this).setTitle("仅在前台读取真实位置？").setMessage("用于这个独立定位面板。本App不转发位置给ESP32、地图服务或其他App，不保存历史；Android系统定位提供者按系统设置和自身隐私政策工作。手机模拟路线保持完全独立。离开App即停止并清除坐标。").setNegativeButton("取消",null).setPositiveButton("开启",(d,w)->{if(phoneLocation.permitted())phoneLocation.start();else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},2);}).show();
    }
    private void renderLocation(){
        if(phoneLocation==null)return;
        phoneLocation.refreshPermission();
        if(locationToggle!=null)locationToggle.setText(phoneLocation.active()?"关闭定位并清除坐标":"主动开启真实定位");
        if(locationDetails==null)return;
        LocationFix fix=phoneLocation.fix();LocationQuality.Assessment quality=LocationQuality.evaluate(fix,SystemClock.elapsedRealtime(),phoneLocation.precise(),maximumLocationAgeMs,maximumLocationAccuracy);
        String qualityName=switch(quality.status()){case NO_FIX->"无位置";case INVALID_TIME->"时间异常 / 不可用";case MOCK->"系统模拟位置 / 非真实证据";case STALE->"位置已过期";case APPROXIMATE->"大致位置 / 非精确";case LOW_ACCURACY->"精度不足";case GOOD->"单次位置质量合格 / 非导航";};
        String details=phoneLocation.status()+"\n\n"+qualityName;
        if(fix!=null)details+=String.format(Locale.CHINA,"\n纬度 %.6f\n经度 %.6f\n报告精度 ±%.0f 米\n来源 %s · 年龄 %s\n权限：%s",fix.latitude(),fix.longitude(),fix.accuracyMeters(),fix.provider(),quality.ageMillis()<0?"异常":String.format(Locale.CHINA,"%.1f 秒",quality.ageMillis()/1000.0),phoneLocation.precise()?"精确":"大致");
        locationDetails.setText(details);
    }
    private void externalMap(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("真实地点名称，例如：上海火车站");
        new AlertDialog.Builder(this).setTitle("交给外部地图 App 搜索").setMessage("仅在你确认后发送这段地点文字，不发送当前位置。外部 App 独立处理搜索；本 App 不会收到其路线或导航数据。").setView(input).setNegativeButton("取消",null).setPositiveButton("选择地图 App",(d,w)->{
            String q=input.getText().toString().trim();if(q.isEmpty()){note("请输入地点名称。没有发送任何数据。");return;}
            if(q.length()>200){note("地点文字过长，请缩短后重试。");return;}
            Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(q)));
            try{startActivity(Intent.createChooser(intent,"选择地图 App（只搜索，不回传路线）"));}
            catch(ActivityNotFoundException e){note("没有可处理地图搜索的应用。请自行安装可信地图App后重试。");}
        }).show();
    }
    private void requestScan(){
        String[] permissions=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};
        for(String p:permissions)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){new AlertDialog.Builder(this).setTitle("连接设备所需权限").setMessage(Build.VERSION.SDK_INT>=31?"附近设备权限用于扫描和连接ESP32。扫描不会启动真实定位；定位须在独立页面主动开启。":"Android 8–11 的 BLE 扫描需要系统定位权限。扫描不会启动位置读取；真实定位须另行主动开启。拒绝后仍可使用手机模拟。").setNegativeButton("暂不连接",null).setPositiveButton("继续",(d,w)->requestPermissions(permissions,1)).show();return;}
        if(deviceList!=null)deviceList.removeAllViews();discovered.clear();link.scan();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==2){if(visible&&phoneLocation.permitted())phoneLocation.start();else note("定位权限未授予，仍可使用独立手机模拟。可在系统应用权限中修改。");renderLocation();return;}if(code!=1)return;boolean granted=results.length>0;for(int r:results)granted&=r==PackageManager.PERMISSION_GRANTED;if(granted&&visible)requestScan();else status("权限未授予。可继续手机模拟；连接需在系统设置允许权限。");}
    @Override public void status(String s){linkStatus=s;events.addFirst(s);while(events.size()>6)events.removeLast();render();}
    @Override public void found(BluetoothDevice device){
        if(!visible||page!=2||deviceList==null)return;
        try{if(!discovered.add(device.getAddress()))return;String name=device.getName();deviceList.addView(button((name==null?"MotoNav BLE 设备":name)+"\n"+device.getAddress(),false,()->{deviceList.removeAllViews();connectSelectedDevice(device);}));}
        catch(SecurityException e){status("蓝牙权限不可用，请重新授权。");}
    }
    @Override public void ready(){simulation.reset();ackCount=0;lastAck=0;status(v2Mode?"真实BLE v2已连接 · 路网长度/CRC提交已确认 · 数据版本"+localNetwork.mapId():"真实 BLE v1 已连接 · 内置路线已确认；模拟位置同步等待回执");link.sendPosition(simulation,null);}
    @Override public void disconnected(){simulation.running=false;lastAckAt=-1;lastAck=0;ackCount=0;linkStatus="未连接设备；模拟已暂停，旧确认已清除。";render();}
    @Override public void acknowledged(int seq){lastAck=seq;lastAckAt=SystemClock.elapsedRealtime();ackCount++;render();}
    private void render(){
        if(statusLine==null)return;boolean connected=link!=null&&link.ready();statusLine.setText(getString(R.string.session_status,connected?"虚构地图 / 真实BLE连接":"虚构地图 / 手机模拟",phoneLocation!=null&&phoneLocation.active()?" / 前台定位开启（不投屏）":""));
        Simulation.Position p=simulation.position();if(turn!=null)turn.setText(simulation.distanceMm==simulation.totalMm()?"已到达霓虹码头":String.format(Locale.CHINA,"%s   %.0f 米",p.turn(),p.turnDistance()));
        if(metrics!=null)metrics.setText(String.format(Locale.CHINA,"剩余 %.2f km  ·  模拟约 %d 秒\n%s  ·  X %.0f / Y %.0f（虚构米数）",(simulation.totalMm()-simulation.distanceMm)/1000000.0,(int)Math.ceil((simulation.totalMm()-simulation.distanceMm)/12000.0),simulation.running?"模拟 43.2 km/h":"已暂停",p.x(),p.y()));
        if(play!=null)play.setText(simulation.running?"暂停模拟":simulation.distanceMm==simulation.totalMm()?"已到达，请先复位":connected?"开始模拟并同步设备":"开始手机模拟（未连接设备）");
        if(ackLine!=null)ackLine.setText(connected?(ackCount==0?"握手成功，等待首个位置回执":getString(R.string.ack_status,lastAck,ackCount)+String.format(Locale.CHINA," · %.1f 秒前",Math.max(0,SystemClock.elapsedRealtime()-lastAckAt)/1000.0)):(simulation.running?"手机独立模拟中；没有设备回执，未向设备发送数据。":"没有设备连接。模拟已暂停，需主动开始；不会产生设备回执。"));
        if(deviceStatus!=null)deviceStatus.setText(linkStatus);if(logView!=null)logView.setText(String.join("\n\n",events));if(map!=null)map.invalidate();renderLocation();
    }
    private final Runnable ticker=new Runnable(){public void run(){if(!visible)return;long now=SystemClock.elapsedRealtime(),elapsed=now-lastTick;lastTick=now;if(!link.ready()||!link.busy()){simulation.advance(elapsed);if(link.ready())link.sendPosition(simulation,null);}render();handler.postDelayed(this,500);}};
    @Override protected void onStart(){super.onStart();visible=true;lastTick=SystemClock.elapsedRealtime();handler.postDelayed(ticker,500);}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(ticker);simulation.running=false;phoneLocation.stop();link.disconnect();linkStatus="离开App后已关闭连接，请重新扫描。";super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("network_v2",v2Mode);out.putBoolean("network_detour",detour);out.putInt("distance",simulation.distanceMm);out.putBoolean("planned",planned);out.putInt("page",page);super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(page!=0)showPage(0);else super.onBackPressed();}

    private final class CityMap extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path routePath=new Path(),arrowPath=new Path();
        private final Typeface mapFont=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);
        CityMap(){super(MainActivity.this);setContentDescription("虚构城区地图，固定模拟路线，非真实地理数据");}
        private float scale(){return Math.min((getWidth()-dp(100))/500f,(getHeight()-dp(48))/1100f);}
        private float x(double v){return getWidth()/2f+(float)(v-60)*scale();}
        private float y(double v){return getHeight()-dp(28)-(float)v*scale();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.drawColor(0xffd8dbcc);paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffa8c6bb);c.drawRect(getWidth()*.76f,0,getWidth(),getHeight(),paint);
            c.save();if(headingUp&&page==1)c.rotate((float)-Math.toDegrees(simulation.position().heading()),getWidth()/2f,getHeight()/2f);
            for(int iy=-1;iy<10;iy++)for(int ix=-3;ix<5;ix++){float left=x(ix*100),top=y(iy*120+90);paint.setColor((ix+iy)%3==0?0xffbec8a7:0xffb9bbaf);c.drawRect(left+dp(3),top+dp(3),x(ix*100+75),y(iy*120),paint);}
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xfff7f4e8);paint.setStrokeWidth(dp(7));if(v2Mode){for(LocalNetwork.Edge edge:localNetwork.edges()){LocalNetwork.Node a=localNetwork.nodes().get(edge.a()),b=localNetwork.nodes().get(edge.b());c.drawLine(x(a.x()),y(a.y()),x(b.x()),y(b.y()),paint);}}else{for(int i=-3;i<5;i++)c.drawLine(x(i*100-10),0,x(i*100-10),getHeight(),paint);for(int i=0;i<10;i++)c.drawLine(0,y(i*120-15),getWidth(),y(i*120-15),paint);}
            Path path=routePath;path.reset();double[][] routePoints=simulation.route();for(int i=0;i<routePoints.length;i++){double[] v=routePoints[i];if(i==0)path.moveTo(x(v[0]),y(v[1]));else path.lineTo(x(v[0]),y(v[1]));}
            paint.setColor(0xff1a241d);paint.setStrokeWidth(dp(10));c.drawPath(path,paint);paint.setColor(GREEN);paint.setStrokeWidth(dp(5));c.drawPath(path,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(ORANGE);c.drawCircle(x(240),y(960),dp(8),paint);paint.setColor(BG);paint.setTextSize(dp(10));paint.setTypeface(mapFont);c.drawText("NEON WHARF",x(240)-dp(80),y(960)-dp(14),paint);
            Simulation.Position p=simulation.position();c.save();c.translate(x(p.x()),y(p.y()));c.rotate((float)Math.toDegrees(p.heading()));Path arrow=arrowPath;arrow.reset();arrow.moveTo(0,-dp(13));arrow.lineTo(dp(10),dp(10));arrow.lineTo(0,dp(6));arrow.lineTo(-dp(10),dp(10));arrow.close();paint.setColor(BG);c.drawPath(arrow,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(WHITE);paint.setStrokeWidth(dp(2));c.drawPath(arrow,paint);c.restore();c.restore();
            paint.setStyle(Paint.Style.FILL);paint.setColor(BG);c.drawRoundRect(dp(10),dp(10),dp(166),dp(35),dp(4),dp(4),paint);paint.setColor(GREEN);paint.setTextSize(dp(10));c.drawText(v2Mode?"LOCAL NETWORK / V2":"FICTIONAL CITY / V1",dp(18),dp(27),paint);
            paint.setColor(BG);c.drawText(headingUp&&page==1?"HEADING ↑":"N ↑",getWidth()-dp(67),getHeight()-dp(14),paint);
        }
    }
}
