package com.motonav.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.text.InputType;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.util.Locale;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.graphics.Insets;
import android.location.Location;
import android.os.SystemClock;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;
import com.amap.api.location.AMapLocation;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.apis.utils.core.api.AMapUtilCoreApi;

/** Map view attached to a foreground navigation service. */
public final class AmapActivity extends Activity {
    private static final String PRIVACY="https://lbs.amap.com/pages/privacy/";
    private static final String TERMS="https://lbs.amap.com/pages/terms/";
    private LinearLayout root;
    private FrameLayout frame;
    private MapDial dial;
    private TextView status, destinationLabel, destinationHint, settingsFeedback;
    private NavigationCard guidanceCard;
    private Button mapShapeButton;
    private AlertDialog settingsDialog, endDialog;
    private String journeyMessage="自动定位当前位置，选择目的地后开始导航。";
    private LinearLayout content;
    private ScrollView mainScroll;
    private View destinationEntry;
    private boolean guidanceFocused;
    private TextView scanHint;
    private int scanGeneration;
    private JourneyController journey;
    private NavigationService navigationService;
    private NavigationService.View navigationView;
    private boolean serviceBound, scanAfterPermission, startAfterPermission;
    private TextView screenStatus;
    private AlertDialog screenDialog;
    private android.widget.ArrayAdapter<String> screenAdapter;
    private final java.util.List<android.bluetooth.BluetoothDevice> screens = new java.util.ArrayList<>();
    private final android.content.ServiceConnection navigationConnection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            if (!serviceBound) return;
            navigationService = ((NavigationService.LocalBinder) binder).service();
            journey = navigationService.journey();
            attachNavigation();
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) {
            navigationService = null; journey = null;
            renderJourney("导航连接暂时中断，请重新打开地图。");
        }
    };
    private final MapFixTracker<Location> fixes = new MapFixTracker<>();
    private AmapLocationFeed locationFeed;
    private Button open, navigationButton;
    private TextureMapView map;
    private AlertDialog consentDialog;
    private boolean foreground, consent, sdkTouched;
    private final AmapKeySession keys=new AmapKeySession();
    private EditText keyInput;
    private SavedMapConfig saved;
    private ScrollView controls;
    private boolean permissionPending;
    private String locationProblem;
    private boolean locationRequested, preciseLocation, locationDotVisible, mapLoaded, recenterPending = true;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable freshness = new Runnable() {
        @Override public void run() {
            if (!foreground || map == null) return;
            locationPermission(false);
            renderJourney(null);
            handler.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        UpdateCheckJob.restoreSchedule(this);
        saved=new SavedMapConfig(this);
        try{keys.replace(saved.load());consent=saved.consent();}catch(Exception e){consent=false;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout shell=AppUi.column(this);AppUi.insets(this,shell);
        LinearLayout header=new LinearLayout(this);header.setGravity(android.view.Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(10),dp(16),dp(6));
        LinearLayout brand=AppUi.column(this);
        TextView name=AppUi.text(this,"MOTONAV",24,AppUi.TEXT);name.setTypeface(null,android.graphics.Typeface.BOLD);name.setLetterSpacing(.1f);brand.addView(name);
        brand.addView(AppUi.text(this,"普通驾车导航 · 停车测试版",12,AppUi.MUTED));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=action("设置",this::showSettings);settings.setTag("map_settings");header.addView(settings,new LinearLayout.LayoutParams(-2,-2));shell.addView(header);
        LinearLayout page=AppUi.column(this);content=page;page.setPadding(dp(16),dp(10),dp(16),dp(20));
        ScrollView screen=new ScrollView(this);screen.setFillViewport(true);screen.setClipToPadding(false);screen.addView(page);
        mainScroll=screen;shell.addView(screen,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout search=AppUi.card(this);search.setFocusable(true);search.setClickable(true);search.setTag("destination_entry");
        destinationLabel=AppUi.text(this,"搜索目的地",20,AppUi.TEXT);destinationLabel.setTypeface(null,android.graphics.Typeface.BOLD);search.addView(destinationLabel);
        destinationHint=AppUi.text(this,"输入城市或地点，选择后即可开始",13,AppUi.MUTED);AppUi.add(search,destinationHint,4);
        destinationEntry=search;search.setOnClickListener(v->openDestination());page.addView(search);
        LinearLayout position=new LinearLayout(this);position.setGravity(android.view.Gravity.CENTER_VERTICAL);position.setPadding(dp(4),dp(10),0,dp(4));
        status=AppUi.text(this,"完成地图设置后，自动获取当前位置。",13,AppUi.MUTED);status.setMaxLines(3);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("当前位置").setMessage(status.getText()).setPositiveButton("知道了",null).show());
        status.setTooltipText("轻触查看完整定位说明");position.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        position.addView(action("定位",()->{if(map==null)showSettings();else locationPermission(true);}),new LinearLayout.LayoutParams(-2,-2));page.addView(position);
        dial=new MapDial(this);dial.setRound(getSharedPreferences("navigation_ui",MODE_PRIVATE).getBoolean("round_map",false));frame=dial.mapFrame;
        int mapHeight=Math.max(220,Math.min(360,Math.round(getResources().getConfiguration().screenHeightDp*.42f)));
        page.addView(dial,new LinearLayout.LayoutParams(-1,dp(mapHeight)));showMapPlaceholder();
        LinearLayout mapTools=new LinearLayout(this);mapTools.setGravity(android.view.Gravity.END);
        mapShapeButton=action(dial.round?"切换完整地图":"圆屏视图",()->{dial.toggleShape();getSharedPreferences("navigation_ui",MODE_PRIVATE).edit().putBoolean("round_map",dial.round).apply();updateMapShape();placeLogo();});
        mapTools.addView(mapShapeButton,new LinearLayout.LayoutParams(-2,-2));page.addView(mapTools);
        guidanceCard=new NavigationCard(this);guidanceCard.setTag("navigation_card");AppUi.add(page,guidanceCard,12);
        LinearLayout device=AppUi.card(this),deviceHeading=new LinearLayout(this);deviceHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView deviceTitle=AppUi.text(this,"圆屏设备",17,AppUi.TEXT);deviceTitle.setTypeface(null,android.graphics.Typeface.BOLD);deviceHeading.addView(deviceTitle,new LinearLayout.LayoutParams(0,-2,1));
        deviceHeading.addView(action("连接",this::chooseScreen),new LinearLayout.LayoutParams(-2,-2));device.addView(deviceHeading);
        screenStatus=AppUi.text(this,"未连接 · 手机导航可独立使用",13,AppUi.MUTED);AppUi.add(device,screenStatus,4);AppUi.add(page,device,12);
        LinearLayout dock=AppUi.column(this);dock.setPadding(dp(16),dp(10),dp(16),dp(12));dock.setBackgroundColor(AppUi.SURFACE);
        navigationButton=AppUi.button(this,"设置地图",true,this::primaryAction);navigationButton.setTag("navigation_primary");dock.addView(navigationButton,new LinearLayout.LayoutParams(-1,-2));
        TextView routeType=AppUi.text(this,"普通驾车路线，不含摩托车禁限行避让",12,AppUi.MUTED);routeType.setGravity(android.view.Gravity.CENTER);AppUi.add(dock,routeType,6);shell.addView(dock);
        buildSettings();frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->placeLogo());setContentView(shell);renderJourney(null);
    }
    private void buildSettings() {
        root=AppUi.column(this);root.setPadding(dp(20),dp(16),dp(20),dp(20));root.setBackgroundColor(AppUi.BG);
        label("地图与偏好",24);label("MotoNav "+BuildConfig.VERSION_NAME,13);
        label("地图服务",18);label("首次使用需要 Android Key。配置仅在本机加密保存。",14);
        settingsFeedback=label(keys.configured()?"已保存地图配置":"尚未配置地图服务",13);
        keyInput=new EditText(this);AppUi.field(keyInput,"Android Key · 32 位字符");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSingleLine(true);
        keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);keyInput.setSaveEnabled(false);keyInput.setSaveFromParentEnabled(false);keyInput.setFreezesText(false);keyInput.setLongClickable(false);keyInput.setTextIsSelectable(false);
        keyInput.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        keyInput.setCustomInsertionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        AppUi.add(root,keyInput,12);button("加密保存地图配置",this::applyKey);
        open=button("阅读并同意后启用地图",this::askConsent);open.setEnabled(keys.configured());
        label("导航偏好",18);
        android.content.SharedPreferences prefs=getSharedPreferences("navigation_ui",MODE_PRIVATE);
        Switch voice=new Switch(this);voice.setText("导航语音播报");voice.setTextColor(AppUi.TEXT);voice.setMinHeight(dp(56));voice.setChecked(prefs.getBoolean("voice_enabled",true));
        voice.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("voice_enabled",on).apply();if(journey!=null)journey.refreshGuidance();});root.addView(voice);
        Switch daylight=new Switch(this);daylight.setText("使用日间地图");daylight.setTextColor(AppUi.TEXT);daylight.setMinHeight(dp(56));daylight.setChecked(prefs.getBoolean("day_map",false));
        daylight.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("day_map",on).apply();applyMapTheme();});root.addView(daylight);
        label("更新与帮助",18);
        button("应用更新",()->startActivity(new Intent(this,UpdateActivity.class)));
        button("定位诊断与实时日志",()->startActivity(new Intent(this,DiagnosticsActivity.class)));
        button("SDK 模拟导航测试",()->{if(NavigationService.navigating){configurationMessage("请先结束真实导航，再运行模拟测试。");return;}startActivity(new Intent(this,SdkReplayActivity.class));});
        button("功能演示 · 模拟数据",()->{if(NavigationService.navigating){configurationMessage("请先结束当前导航，再进入功能演示。");return;}if(navigationService!=null)navigationService.disconnectScreen();startActivity(new Intent(this,MainActivity.class));});
        label("隐私与地图绑定",18);button("高德隐私政策",()->policy(PRIVACY));button("高德服务条款",()->policy(TERMS));
        label("绑定包名："+getPackageName()+"\n本安装签名 SHA1："+certificateSha1(),12);
        Button clear=button("清除地图配置并撤回同意",()->new AlertDialog.Builder(this).setTitle("清除地图配置？").setMessage("将结束当前导航、断开圆屏并删除本机保存的 Key。之后需要重新配置地图。").setNegativeButton("保留配置",null).setPositiveButton("清除配置",(d,w)->clearKey()).show());clear.setTextColor(AppUi.DANGER);
        controls=new ScrollView(this);controls.setFillViewport(true);controls.addView(root);
    }
    private void configurationMessage(String text) { AppUi.setText(status,text);AppUi.setText(settingsFeedback,text);renderJourney(null); }
    private void showSettings() {
        if(settingsDialog!=null)return;
        settingsDialog=new AlertDialog.Builder(this).setView(controls).setPositiveButton("完成",null).create();
        settingsDialog.setOnDismissListener(d->{if(controls.getParent() instanceof android.view.ViewGroup parent)parent.removeView(controls);settingsDialog=null;keyInput.getText().clear();});
        settingsDialog.show();
        if(settingsDialog.getWindow()!=null){settingsDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);settingsDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    }
    private void showMapPlaceholder() {
        if(map!=null)return;frame.removeAllViews();
        LinearLayout placeholder=AppUi.column(this);placeholder.setGravity(android.view.Gravity.CENTER);placeholder.setPadding(dp(24),dp(24),dp(24),dp(24));
        TextView mark=AppUi.text(this,"◎",52,AppUi.PURPLE);mark.setGravity(android.view.Gravity.CENTER);placeholder.addView(mark);
        TextView title=AppUi.text(this,"地图准备就绪后\n在这里查看你的路线",17,AppUi.TEXT);title.setGravity(android.view.Gravity.CENTER);AppUi.add(placeholder,title,8);
        TextView detail=AppUi.text(this,"自动定位 · 搜索目的地 · 开始导航",12,AppUi.MUTED);detail.setGravity(android.view.Gravity.CENTER);AppUi.add(placeholder,detail,12);frame.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
    }
    private void updateMapShape(){if(mapShapeButton!=null)mapShapeButton.setText(dial.round?"切换完整地图":"圆屏视图");}
    private void applyMapTheme(){if(map!=null)map.getMap().setMapType(getSharedPreferences("navigation_ui",MODE_PRIVATE).getBoolean("day_map",false)?com.amap.api.maps.AMap.MAP_TYPE_NORMAL:com.amap.api.maps.AMap.MAP_TYPE_NIGHT);}
    private void openDestination(){if(journey!=null)journey.searchDialog();else if(!keys.configured())showSettings();else if(!consent)askConsent();else {initialize();status.setText("地图正在准备，请稍后搜索目的地。");}}
    private void primaryAction(){
        if(!keys.configured()){showSettings();return;}if(!consent){askConsent();return;}if(map==null){initialize();return;}if(journey==null)return;
        if(journey.presentation().navigating()){
            if(endDialog!=null)return;
            endDialog=new AlertDialog.Builder(this).setTitle("结束本次导航？").setMessage("目的地会保留，随时可以重新开始。").setNegativeButton("继续导航",null).setPositiveButton("结束导航",(d,w)->{if(journey!=null)journey.end();}).create();
            endDialog.setOnDismissListener(d->endDialog=null);endDialog.show();return;
        }
        toggleNavigation();
    }
    private void renderJourney(String notice){
        if(guidanceCard==null)return;if(notice!=null)journeyMessage=notice;
        NavigationUiModel model=journey==null?NavigationUiModel.create(JourneyState.Phase.IDLE,NavigationFrame.status(NavigationFrame.STOPPED),"","",journeyMessage):journey.presentation();
        guidanceCard.render(model);AppUi.setText(destinationLabel,model.destination().isEmpty()?"搜索目的地":model.destination());
        boolean focus=model.navigating()||model.busy();
        destinationEntry.setVisibility(focus?View.GONE:View.VISIBLE);
        if(focus!=guidanceFocused){
            guidanceFocused=focus;android.view.ViewGroup.LayoutParams params=guidanceCard.getLayoutParams();content.removeView(guidanceCard);
            content.addView(guidanceCard,focus?0:content.getChildCount()-1,params);
            mainScroll.post(()->mainScroll.smoothScrollTo(0,0));
        }
        AppUi.setText(destinationHint,model.destination().isEmpty()?"输入城市或地点，选择后即可开始":model.navigating()?"当前目的地":"轻触更换目的地");
        String action=!keys.configured()?"设置地图":!consent?"启用地图":map==null?"重试打开地图":journey==null?"正在准备地图…":model.destination().isEmpty()?"选择目的地":journey.actionLabel();
        AppUi.setText(navigationButton,action);navigationButton.setEnabled(!keys.configured()||!consent||map==null||journey!=null);navigationButton.setAlpha(navigationButton.isEnabled()?1:.6f);
    }
    private void toggleNavigation() {
        if (journey == null) { status.setText("请先完成地图设置。"); return; }
        android.content.SharedPreferences prefs = getSharedPreferences("navigation_ui", MODE_PRIVATE);
        if (journey.canRequestStart() && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notification_asked", false)) {
            prefs.edit().putBoolean("notification_asked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 73);
            return;
        }
        journey.startOrStop();
    }

    private void attachNavigation() {
        if (foreground && consent && map != null && navigationService != null && navigationView != null)
            navigationService.attach(this, map.getMap(), navigationView);
    }

    private boolean blePermissions() {
        if (Build.VERSION.SDK_INT < 31) return hasPrecisePermission();
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void chooseScreen() {
        if (navigationService == null) { screenStatus.setText("请先打开地图，等待导航服务就绪。"); return; }
        if (!blePermissions()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT}, 72);
            else locationPermission(true);
            return;
        }
        screens.clear();
        screenAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        LinearLayout scanTitle=AppUi.column(this);scanTitle.setPadding(dp(24),dp(20),dp(24),dp(12));
        scanTitle.addView(AppUi.text(this,"连接圆屏",22,AppUi.TEXT));scanHint=AppUi.text(this,"正在寻找附近的 MotoNav 设备…",14,AppUi.MUTED);AppUi.add(scanTitle,scanHint,8);
        screenDialog = new AlertDialog.Builder(this).setCustomTitle(scanTitle)
                .setAdapter(screenAdapter, (dialog, which) -> {
                    if (navigationService != null && which < screens.size()) navigationService.connect(screens.get(which));
                }).setNeutralButton("断开圆屏", (dialog, which) -> {
                    if (navigationService != null) navigationService.disconnectScreen();
                }).setNegativeButton("取消", null).setPositiveButton("重新扫描",null).create();
        screenDialog.setOnDismissListener(dialog -> {screenDialog = null;scanHint=null;++scanGeneration;});
        screenDialog.show();
        screenDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->scanScreens());scanScreens();
    }
    private void scanScreens(){
        if(navigationService==null||screenDialog==null)return;screens.clear();screenAdapter.clear();scanHint.setText("正在寻找附近的 MotoNav 设备…");navigationService.scan();
        AlertDialog target=screenDialog;int generation=++scanGeneration;handler.postDelayed(()->{if(screenDialog==target&&generation==scanGeneration&&scanHint!=null&&screens.isEmpty())scanHint.setText("未发现圆屏。请确认设备已开机、蓝牙可用，并靠近后重新扫描。");},10000);
    }

    private void addScreen(android.bluetooth.BluetoothDevice device) {
        if (screenDialog == null || screens.contains(device)) return;
        try {
            String name = device.getName();
            screens.add(device);
            String address=device.getAddress();screenAdapter.add((name == null ? "MotoNav 圆屏" : name) + " · " + address.substring(Math.max(0,address.length()-5)));
            if(scanHint!=null)scanHint.setText("找到 "+screens.size()+" 台设备 · 点击后按圆屏提示完成配对");
        } catch (SecurityException e) { screenStatus.setText("蓝牙权限已撤回。"); }
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable panel(int color){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private Button action(String text,Runnable run){return AppUi.button(this,text,false,run);}
    private void placeLogo(){
        if(map==null)return;
        // Keep the SDK's unscaled attribution inside the circular viewport, never draw over it.
        map.getMap().getUiSettings().setLogoPosition(com.amap.api.maps.AMapOptions.LOGO_POSITION_BOTTOM_CENTER);
        map.getMap().getUiSettings().setLogoBottomMargin(dial.round?Math.max(dp(32),frame.getHeight()/8):dp(12));
    }
    private String certificateSha1(){
        try{
            PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            Signature[] signatures=Build.VERSION.SDK_INT>=28?info.signingInfo.getApkContentsSigners():info.signatures;
            if(signatures==null||signatures.length!=1)return "无法确定，请用构建记录核实";
            byte[] digest=MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());StringBuilder text=new StringBuilder();
            for(byte value:digest){if(text.length()>0)text.append(':');text.append(String.format(Locale.ROOT,"%02X",value&255));}return text.toString();
        }catch(Exception e){return "无法读取，请用构建记录核实";}
    }
    private TextView label(String value,int size){TextView v=AppUi.text(this,value,size,size>=18?AppUi.TEXT:AppUi.MUTED);if(size>=18)v.setTypeface(null,android.graphics.Typeface.BOLD);AppUi.add(root,v,size>=18?18:8);return v;}
    private Button button(String text,Runnable action){Button button=AppUi.button(this,text,false,action);AppUi.add(root,button,10);return button;}
    private void policy(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){status.setText(getString(R.string.amap_no_browser,url));}}
    private void askConsent(){
        if(map!=null||!keys.configured()||!foreground||consentDialog!=null)return;
        consentDialog=new AlertDialog.Builder(this).setTitle("启用高德联网地图？").setMessage("本应用使用高德地图、定位、搜索及导航SDK，联网时向高德提交Android Key、包信息及服务必需的网络、设备和位置信息，服务端可见IP。浏览地图时在前台定位。主动开始导航后，通过持续通知在锁屏或切换应用时继续定位，直到结束导航。主动搜索或规划路线时会把搜索词和起终点提交高德；显式开始后提供GPS普通驾车导航（不含摩托车禁限行避让），并向你选择并配对的ESP32发送当前位置、路线片段、转向、距离、车速和数据有效状态。圆屏连接通过系统蓝牙授权和配对。本次版本扩展了数据用途，因此需要重新同意一次。请先阅读页面中的隐私政策和条款。\n\nKey将用Android Keystore保护的AES-GCM加密保存于本应用私有存储，应用备份关闭。此次同意会保存，今后打开自动加载地图及定位；可在设置中删除Key并撤回同意。SDK自身缓存无法保证彻底清除。系统首次定位授权仍需本人操作。")
            .setNegativeButton("不同意并关闭",(d,w)->{consent=false;dispose();boolean recorded=saved.consent(false);if(!recorded){saved.clear();keys.clear();open.setEnabled(false);}status.setText(recorded?"已撤回同意，不会自动联网定位":"已停止地图，但无法确认撤回记录已保存。请在系统中清除本测试应用数据后再使用。");})
            .setPositiveButton("同意并联网打开",(d,w)->{if(!foreground||isFinishing())return;if(!saved.consent(true)){status.setText("无法保存同意，请重试");return;}consent=true;initialize();}).create();
        consentDialog.setOnDismissListener(d->consentDialog=null);consentDialog.show();
    }
    private void initialize(){
        if(map!=null||!keys.configured()||!consent||!foreground)return;
        try{
            sdkTouched=true;
            AMapUtilCoreApi.setCollectInfoEnable(false);AMapUtilCoreApi.setCollectIPEnable(false);
            MapsInitializer.updatePrivacyShow(this,true,true);MapsInitializer.updatePrivacyAgree(this,true);
            MapsInitializer.setProtocol(MapsInitializer.HTTPS);MapsInitializer.setApiKey(keys.sdkValue());
            frame.removeAllViews();map=new TextureMapView(this);map.onCreate(null);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(this,true,true);
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this,true);
            com.amap.api.location.AMapLocationClient.setApiKey(keys.sdkValue());
            map.getMap().setMyLocationStyle(new com.amap.api.maps.model.MyLocationStyle().myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_FOLLOW));
            locationFeed = new AmapLocationFeed(this, this::handleLocation);
            map.getMap().setLocationSource(locationFeed);
            map.getMap().setMyLocationEnabled(false);map.getMap().getUiSettings().setMyLocationButtonEnabled(false);
            applyMapTheme();
            map.getMap().getUiSettings().setZoomControlsEnabled(false);
            map.getMap().getUiSettings().setCompassEnabled(false);
            map.getMap().getUiSettings().setScaleControlsEnabled(false);
            placeLogo();
            navigationView = new NavigationService.View() {
                public Location fix() {
                    MapFixTracker.Fix<Location> fix = navigationFix();
                    return fix == null ? null : new Location(fix.point());
                }
                public long fixNanos() {
                    MapFixTracker.Fix<Location> fix = navigationFix();
                    return fix == null ? 0 : fix.timeNanos();
                }
                public void refreshLocation() {
                    locationPermission(false);
                    if (foreground && consent && map != null && locationRequested && navigationFix() == null)
                        locationFeed.maintain(true);
                }
                public void report(String text) {
                    renderJourney(text);
                }
                public void overview(){dial.setRound(false);updateMapShape();placeLogo();}
                public void icon(android.graphics.Bitmap bitmap) { guidanceCard.icon(bitmap); }
                public void bleStatus(String text) { AppUi.setText(screenStatus,text); }
                public void found(android.bluetooth.BluetoothDevice device) { addScreen(device); }
            };
            serviceBound = bindService(new Intent(this, NavigationService.class), navigationConnection, BIND_AUTO_CREATE);
            if (!serviceBound) renderJourney("导航服务启动失败，请重新打开地图。");
            final TextureMapView activeMap = map;
            map.getMap().setOnMapLoadedListener(() -> {
                if (map != activeMap) return;
                mapLoaded = true;
                if (foreground && locationRequested) locationPermission(false);
            });
            map.onResume();
            open.setEnabled(false);
            if(settingsDialog!=null)settingsDialog.dismiss();
            checkLocationNow();
        }catch(RuntimeException|LinkageError e){dispose();status.setText(R.string.amap_failed);}
    }
    private void dispose() {
        if (navigationService != null) navigationService.revoke();
        releaseMap();
    }
    private void releaseMap(){
        handler.removeCallbacks(freshness);
        stopLocation();
        locationFeed = null;
        mapLoaded = false;
        if (navigationService != null) navigationService.detach(navigationView);
        if (serviceBound) { unbindService(navigationConnection); serviceBound = false; }
        navigationService = null; journey = null; navigationView = null;
        if (screenDialog != null) { screenDialog.dismiss(); screenDialog = null; }
        if(map!=null){TextureMapView old=map;map=null;try{old.getMap().setOnMapLoadedListener(null);old.getMap().setLocationSource(null);}catch(RuntimeException|LinkageError ignored){}try{old.onPause();}catch(RuntimeException|LinkageError ignored){}try{old.onDestroy();}catch(RuntimeException|LinkageError ignored){}frame.removeAllViews();}
        if(sdkTouched && !NavigationService.navigating){sdkTouched=false;try{MapsInitializer.updatePrivacyAgree(this,false);com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this,false);}catch(RuntimeException|LinkageError ignored){}try{MapsInitializer.setApiKey("");}catch(RuntimeException|LinkageError ignored){}}
        if(open!=null)open.setEnabled(keys.configured());
        showMapPlaceholder();renderJourney(null);
    }
    private void applyKey(){
        if(!foreground)return;
        if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}
        boolean valid=AmapAccess.configured(keyInput.getText().toString());
        if(!valid){keyInput.setError("请输入有效的 32 位 Android Key");configurationMessage("配置格式不正确，原有配置未改变。");return;}keyInput.setError(null);
        if(valid){try{saved.save(keyInput.getText().toString());dispose();keys.replace(keyInput.getText().toString());}catch(Exception e){configurationMessage("无法安全保存Key，未启用地图；请检查设备安全存储后重试");return;}}keyInput.getText().clear();keyInput.clearFocus();
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(keyInput.getWindowToken(),0);
        open.setEnabled(keys.configured());configurationMessage(valid?"Key已加密保存，格式检查不代表鉴权通过":"Key格式无效，原有配置未改变");if(valid&&consent)initialize();
    }
    private void clearKey(){
        if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}
        consent=false;dispose();keys.clear();boolean removed=saved.clear();if(keyInput!=null){keyInput.getText().clear();keyInput.clearFocus();InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(keyInput.getWindowToken(),0);}if(open!=null)open.setEnabled(false);if(status!=null)configurationMessage(removed?"已删除本App保存的Key并撤回同意；SDK缓存可能仍存在":"地图已停止，但本地删除未完全成功。请在系统中清除本测试应用数据后再使用。");showMapPlaceholder();renderJourney(null);
    }
    private boolean systemLocationEnabled(){
        android.location.LocationManager manager=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
        if(manager==null)return false;
        try{return Build.VERSION.SDK_INT>=28?manager.isLocationEnabled():manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);}
        catch(RuntimeException e){return true;} // Unknown OS status: let the SDK report the actual failure.
    }
    private boolean hasPrecisePermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasApproximatePermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean navigationAllowed() {
        return foreground && consent && map != null && hasPrecisePermission() && systemLocationEnabled();
    }

    private MapFixTracker.Fix<Location> navigationFix() {
        return foreground && consent && map != null ? fixes.navigation(SystemClock.elapsedRealtimeNanos(),
                hasPrecisePermission(), systemLocationEnabled()) : null;
    }

    private void checkLocationNow() {
        handler.removeCallbacks(freshness);
        freshness.run();
    }

    private void setLocationDot(boolean visible) {
        if (map == null || locationDotVisible == visible) return;
        try {
            map.getMap().setMyLocationEnabled(visible);
            locationDotVisible = visible;
        } catch (RuntimeException | LinkageError ignored) {
            // Losing the map overlay must not stop the separate foreground location request.
        }
    }

    private void stopLocation() {
        locationRequested = false;
        locationProblem = null;
        recenterPending = true;
        fixes.clear();
        if (locationFeed != null) locationFeed.stop();
        setLocationDot(false);
    }

    private void centerOnFix(Location location) {
        map.getMap().animateCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                new com.amap.api.maps.model.LatLng(location.getLatitude(), location.getLongitude()), 16));
        recenterPending = false;
    }

    private void handleLocation(AMapLocation location) {
        if (!foreground || !consent || map == null || !locationRequested) {
            Diagnostics.record("location_decision", "owner", "map", "reason", "lifecycle_blocked", "foreground", foreground, "consent", consent, "map_ready", map != null, "requested", locationRequested);
            return;
        }
        boolean precise = hasPrecisePermission();
        if ((!precise && !hasApproximatePermission()) || !systemLocationEnabled()) {
            locationPermission(false);
            return;
        }
        if (precise != preciseLocation) {
            locationPermission(false);
            return;
        }

        String decision = "duplicate_or_older"; boolean accepted = false;
        if (location == null) {
            decision = "null_result";
            locationProblem = "尚未收到定位结果，正在继续获取。";
        } else if (location.getErrorCode() != 0) {
            decision = "sdk_error";
            locationProblem = MapStartupPolicy.locationError(location.getErrorCode());
        } else if (!MapStartupPolicy.validFix(location.getLatitude(), location.getLongitude(),
                location.getAccuracy(), location.isFromMockProvider())) {
            decision = "invalid_or_mock";
            locationProblem = "收到无效或模拟位置，正在等待新定位。";
        } else {
            long time = MapStartupPolicy.fixTime(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
                    location.getElapsedRealtimeNanos(), location.getTime());
            int source = AmapLocationSample.source(location);
            if (time == 0 || !MapFixTracker.isLiveSource(source)) {
                decision = time == 0 ? "measurement_expired_or_invalid_time" : "cached_or_unsupported_source";
                locationProblem = "收到缓存、过期或无法确认来源的位置，正在等待新定位。";
            } else if (fixes.offer(snapshot(location, time), time, location.getAccuracy(), source, precise)) {
                accepted = true; decision = "accepted";
                locationProblem = null;
            }
        }
        Diagnostics.record("location_decision", "owner", "map", "reason", decision, "accepted", accepted,
                "source", location == null ? 0 : AmapLocationSample.source(location));
        renderLocation(precise);
    }

    private static Location snapshot(AMapLocation source, long timeNanos) {
        // AMapLocation overrides the coordinate accessors. Copy the reported values explicitly.
        Location fix = new Location(source.getProvider());
        fix.setLatitude(source.getLatitude());
        fix.setLongitude(source.getLongitude());
        fix.setAccuracy(source.getAccuracy());
        fix.setTime(source.getTime());
        fix.setElapsedRealtimeNanos(timeNanos);
        if (source.hasBearing()) fix.setBearing(source.getBearing());
        if (source.hasSpeed()) fix.setSpeed(source.getSpeed());
        return fix;
    }

    private void renderLocation(boolean precise) {
        Diagnostics.fixState("map", fixes, precise, systemLocationEnabled());
        long now = SystemClock.elapsedRealtimeNanos();
        Location guidance = navigationService != null && NavigationService.navigating ? navigationService.journey().guidanceFix() : null;
        if (precise && systemLocationEnabled() && guidance != null && "amap_navigation_gps".equals(guidance.getProvider())) {
            setLocationDot(true); locationFeed.show(guidance);
            status.setText("导航卫星定位 · 精度约 " + (long)Math.ceil(guidance.getAccuracy()) + " 米 · "
                    + Math.max(0,(now-guidance.getElapsedRealtimeNanos())/1_000_000_000L) + " 秒前更新");
            return;
        }
        MapFixTracker.Fix<Location> observed = fixes.latest(now);
        MapFixTracker.Fix<Location> reliable = fixes.navigation(now, precise, true);
        MapFixTracker.Fix<Location> displayed = reliable != null ? reliable : observed;
        if (displayed == null) {
            locationFeed.show(null);
            setLocationDot(false);
            long age = fixes.observationAgeMillis(now);
            status.setText((age >= 0 ? "距上次有效定位 " + age / 1000 + " 秒，正在自动重新获取。\n" : "") + (locationProblem != null ? locationProblem
                    : precise ? "正在获取新定位。室内精度可能较低，移动到开阔处可帮助接收卫星信号。"
                    : "当前只有大致位置权限。点「定位」开启精确位置。"));
            return;
        }

        setLocationDot(true);
        locationFeed.show(displayed.point());
        if (mapLoaded && reliable != null && recenterPending) centerOnFix(reliable.point());
        MapFixTracker.Fix<Location> reported = observed != null ? observed : displayed;
        String summary = sourceLabel(reported.source()) + " · 精度约 " + (long) Math.ceil(reported.accuracy()) + " 米";
        if (!precise) summary += " · 仅大致位置权限";
        else if (reliable == null) summary += " · 暂不足以算路，持续获取中";
        else if (reliable != reported) summary += " · 保留最近可用位置，正在获取更准的位置";
        else summary += " · 可用于算路";
        summary += " · " + Math.max(0, (now - reported.timeNanos()) / 1000000000L) + " 秒前更新";
        if (locationProblem != null) summary += "\n" + locationProblem;
        status.setText(summary);
    }

    private static String sourceLabel(int source) {
        return switch (source) {
            case MapFixTracker.SATELLITE -> "卫星定位";
            case MapFixTracker.WIFI -> "Wi-Fi 定位";
            case MapFixTracker.CELL -> "基站定位";
            case MapFixTracker.APPROXIMATE -> "大致定位";
            case MapFixTracker.SYSTEM_NETWORK -> "系统网络定位";
            default -> "定位";
        };
    }

    private void locationPermission(boolean userRetry) {
        if (userRetry) Diagnostics.record("location_retry", "owner", "map", "foreground", foreground, "consent", consent, "requested", locationRequested);
        if (!foreground || map == null || !consent || permissionPending) return;
        boolean precise = hasPrecisePermission();
        boolean approximate = hasApproximatePermission();
        MapStartupPolicy.Action action = MapStartupPolicy.location(foreground, map != null, consent,
                permissionPending, precise, approximate, saved.asked(), userRetry,
                shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION));
        if (action == MapStartupPolicy.Action.LOCATE) {
            if (!systemLocationEnabled()) {
                stopLocation();
                status.setText("系统定位已关闭。打开后会自动恢复定位。");
                if (userRetry) startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }
            if (locationRequested && preciseLocation != precise) stopLocation();
            preciseLocation = precise;
            locationRequested = true;
            if (userRetry) {
                recenterPending = true;
                map.getMap().setMyLocationStyle(new com.amap.api.maps.model.MyLocationStyle()
                        .myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_FOLLOW));
            }
            if (!locationFeed.maintain(userRetry) && locationProblem == null) {
                locationProblem = "定位暂未启动，App 会自动重试。";
            }
            renderLocation(precise);
            return;
        }

        stopLocation();
        if (action == MapStartupPolicy.Action.REQUEST) {
            saved.asked(true);
            permissionPending = true;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION}, 71);
        } else {
            status.setText(approximate ? "当前只有大致位置权限。请在系统设置开启精确位置。"
                    : "定位未授权。可浏览地图，点「定位」进入应用设置。");
            if (userRetry) startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==71){permissionPending=false;if(foreground)locationPermission(false);}
        if (request == 72) { scanAfterPermission = true; if (foreground) { scanAfterPermission = false; if (blePermissions()) chooseScreen(); } }
        if (request == 73) { startAfterPermission = true; if (foreground) { startAfterPermission = false; toggleNavigation(); } }
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        Diagnostics.record("lifecycle", "owner", "map", "state", "resume");
        if (map == null) initialize();
        else {
            map.onResume();
            checkLocationNow();
        }
        attachNavigation();renderJourney(null);
        if (scanAfterPermission) { scanAfterPermission = false; if (blePermissions()) chooseScreen(); }
        if (startAfterPermission) { startAfterPermission = false; toggleNavigation(); }
    }

    @Override protected void onPause() {
        foreground = false;
        Diagnostics.record("lifecycle", "owner", "map", "state", "pause");
        handler.removeCallbacks(freshness);
        stopLocation();
        if (navigationService != null) navigationService.detach(navigationView);
        if (screenDialog != null) { screenDialog.dismiss(); screenDialog = null; }
        if (consentDialog != null) {
            consentDialog.dismiss();
            consentDialog = null;
        }
        if(settingsDialog!=null)settingsDialog.dismiss();
        if(endDialog!=null)endDialog.dismiss();
        if (map != null) map.onPause();
        super.onPause();
    }
    @Override protected void onDestroy(){releaseMap();keys.clear();super.onDestroy();}
}
