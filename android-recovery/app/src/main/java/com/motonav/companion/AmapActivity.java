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
    private TextView status, journeyStatus;
    private ImageView turnIcon;
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
            if (journeyStatus != null) journeyStatus.setText("导航服务已停止，请重新规划路线。");
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
            handler.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        UpdateCheckJob.restoreSchedule(this);
        saved=new SavedMapConfig(this);
        try{keys.replace(saved.load());consent=saved.consent();}catch(Exception e){consent=false;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff101411);root.setPadding(20,16,20,16);
        label("MOTO / NAV · 独立恢复测试",22);label("普通驾车导航与圆屏连接测试",13);
        status=label("首次填写Android Key并阅读政策。保存后在本机加密存储，重启后自动载入；开始导航后可锁屏继续定位。新包名须单独绑定，不会读取旧App数据。",14);
        keyInput=new EditText(this);keyInput.setHint("Android Key · 32位字符");keyInput.setTextColor(0xffeef3e6);keyInput.setHintTextColor(0xffaab3a7);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSingleLine(true);
        keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);keyInput.setSaveEnabled(false);keyInput.setSaveFromParentEnabled(false);keyInput.setFreezesText(false);keyInput.setLongClickable(false);keyInput.setTextIsSelectable(false);
        keyInput.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        keyInput.setCustomInsertionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        root.addView(keyInput);button("加密保存Key / 修改Key",this::applyKey);button("删除已存Key并撤回同意",this::clearKey);
        open=button("阅读并同意后打开地图",this::askConsent);open.setEnabled(keys.configured());
        button("高德隐私政策",()->policy(PRIVACY));button("高德服务条款",()->policy(TERMS));
        label("使用普通汽车驾车路线，不避让摩托车禁限行。无需填写车牌和排量；不提供车牌限行判断。",12);
        label("Android Key绑定包名："+getPackageName()+"\n本安装签名SHA1："+certificateSha1(),12);
        button("应用更新",()->startActivity(new Intent(this,UpdateActivity.class)));
        button("定位诊断 / 实时日志",()->startActivity(new Intent(this,DiagnosticsActivity.class)));
        button("返回模拟功能（非真实导航）", () -> {
            if (NavigationService.navigating) { status.setText("请先结束当前导航，再进入模拟功能。"); return; }
            if (navigationService != null) navigationService.disconnectScreen();
            startActivity(new Intent(this, MainActivity.class));
        });
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff101411);page.setPadding(dp(16),dp(12),dp(16),dp(16));
        ScrollView screen=new ScrollView(this);screen.setFillViewport(true);screen.addView(page);
        if(Build.VERSION.SDK_INT>=30)screen.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        TextView brand=new TextView(this);brand.setText("MOTO / NAV");brand.setTextSize(21);brand.setLetterSpacing(0.14f);brand.setTextColor(0xffe9eddf);brand.setTypeface(null,android.graphics.Typeface.BOLD);page.addView(brand);
        TextView warning=new TextView(this);warning.setText("RECOVERY " + BuildConfig.VERSION_NAME + " ·  仅停车测试，不用于骑行");warning.setTextSize(12);warning.setTextColor(0xffc6a988);warning.setPadding(0,dp(4),0,dp(12));page.addView(warning);
        TextView routeType=new TextView(this);routeType.setText("普通驾车 · 不含摩托车禁限行避让");routeType.setTextSize(13);routeType.setTextColor(0xffd4e2ba);routeType.setPadding(0,0,0,dp(8));page.addView(routeType);
        root.removeView(status);status.setTextSize(13);status.setPadding(dp(12),dp(10),dp(12),dp(10));status.setBackground(panel(0xff1a211c));page.addView(status);
        dial=new MapDial(this);frame=dial.mapFrame;page.addView(dial,new LinearLayout.LayoutParams(-1,dp(380)));
        TextView waiting=new TextView(this);waiting.setText("MOTO / NAV\n等待地图配置");waiting.setGravity(android.view.Gravity.CENTER);waiting.setTextSize(18);waiting.setTextColor(0xff9da69a);frame.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout guidance=new LinearLayout(this);guidance.setGravity(android.view.Gravity.CENTER_VERTICAL);guidance.setPadding(dp(12),dp(12),dp(12),dp(12));guidance.setBackground(panel(0xff20271f));
        turnIcon=new ImageView(this);turnIcon.setContentDescription("SDK转向图标；文字指令见右侧");guidance.addView(turnIcon,new LinearLayout.LayoutParams(dp(56),dp(56)));
        journeyStatus=new TextView(this);journeyStatus.setTextColor(0xffd4e2ba);journeyStatus.setTextSize(18);journeyStatus.setPadding(dp(12),0,0,0);journeyStatus.setText("输入目的地 → 开始导航\n当前位置自动获取");guidance.addView(journeyStatus,new LinearLayout.LayoutParams(0,-2,1));page.addView(guidance);
        LinearLayout actions=new LinearLayout(this);
        actions.addView(action("输入目的地",()->{if(journey!=null)journey.searchDialog();else status.setText("请先完成地图设置。");}),new LinearLayout.LayoutParams(0,-2,1));
        navigationButton = action("开始导航", this::toggleNavigation);
        actions.addView(navigationButton,new LinearLayout.LayoutParams(0,-2,1));page.addView(actions);
        LinearLayout tools=new LinearLayout(this);
        tools.addView(action("定位/权限",()->locationPermission(true)),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(action("圆形/完整图",()->{dial.toggleShape();placeLogo();}),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(action("地图设置",()->controls.setVisibility(controls.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)),new LinearLayout.LayoutParams(0,-2,1));page.addView(tools);
        screenStatus = new TextView(this); screenStatus.setText("圆屏尚未连接");
        screenStatus.setTextColor(0xffaab3a7); screenStatus.setPadding(dp(12), dp(8), dp(12), dp(4)); page.addView(screenStatus);
        page.addView(action("连接圆屏", this::chooseScreen));
        controls=new ScrollView(this);controls.addView(root);page.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->placeLogo());setContentView(screen);
        if(keys.configured()&&consent)controls.setVisibility(View.GONE);
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
        screenDialog = new AlertDialog.Builder(this).setTitle("选择你的 MotoNav 圆屏")
                .setAdapter(screenAdapter, (dialog, which) -> {
                    if (navigationService != null && which < screens.size()) navigationService.connect(screens.get(which));
                }).setNeutralButton("断开圆屏", (dialog, which) -> {
                    if (navigationService != null) navigationService.disconnectScreen();
                }).setNegativeButton("取消", null).create();
        screenDialog.setOnDismissListener(dialog -> screenDialog = null);
        screenDialog.show();
        navigationService.scan();
    }

    private void addScreen(android.bluetooth.BluetoothDevice device) {
        if (screenDialog == null || screens.contains(device)) return;
        try {
            String name = device.getName();
            screens.add(device);
            screenAdapter.add((name == null ? "MotoNav 圆屏" : name) + " · " + device.getAddress());
        } catch (SecurityException e) { screenStatus.setText("蓝牙权限已撤回。"); }
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable panel(int color){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private Button action(String text,Runnable run){Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(dp(48));b.setTextColor(0xffdce5d2);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff293228));b.setOnClickListener(v->run.run());return b;}
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
    private TextView label(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(0xffeef3e6);v.setTextSize(size);v.setPadding(0,4,0,4);root.addView(v);return v;}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->action.run());root.addView(b);return b;}
    private void policy(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){status.setText(getString(R.string.amap_no_browser,url));}}
    private void askConsent(){
        if(map!=null||!keys.configured()||!foreground||consentDialog!=null)return;
        consentDialog=new AlertDialog.Builder(this).setTitle("启用高德联网地图？").setMessage("本应用使用高德地图、定位、搜索及导航SDK，联网时向高德提交Android Key、包信息及服务必需的网络、设备和位置信息，服务端可见IP。浏览地图时在前台定位。主动开始导航后，通过持续通知在锁屏或切换应用时继续定位，直到结束导航。主动搜索或规划路线时会把搜索词和起终点提交高德；显式开始后提供GPS普通驾车导航（不含摩托车禁限行避让），并向你选择并配对的ESP32发送转向、距离、车速和数据有效状态。圆屏连接通过系统蓝牙授权和配对。本次版本扩展了数据用途，因此需要重新同意一次。请先阅读页面中的隐私政策和条款。\n\nKey将用Android Keystore保护的AES-GCM加密保存于本应用私有存储，应用备份关闭。此次同意会保存，今后打开自动加载地图及定位；可在设置中删除Key并撤回同意。SDK自身缓存无法保证彻底清除。系统首次定位授权仍需本人操作。")
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
            map.getMap().setMapType(com.amap.api.maps.AMap.MAP_TYPE_NIGHT);
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
                    journeyStatus.setText(text);
                    if (navigationButton != null && journey != null) navigationButton.setText(journey.actionLabel());
                }
                public void overview(){dial.setRound(false);placeLogo();}
                public void icon(android.graphics.Bitmap bitmap) { turnIcon.setImageBitmap(bitmap); }
                public void bleStatus(String text) { screenStatus.setText(text); }
                public void found(android.bluetooth.BluetoothDevice device) { addScreen(device); }
            };
            serviceBound = bindService(new Intent(this, NavigationService.class), navigationConnection, BIND_AUTO_CREATE);
            if (!serviceBound) journeyStatus.setText("导航服务启动失败，请重新打开地图。");
            final TextureMapView activeMap = map;
            map.getMap().setOnMapLoadedListener(() -> {
                if (map != activeMap) return;
                mapLoaded = true;
                if (foreground && locationRequested) locationPermission(false);
            });
            map.onResume();
            open.setEnabled(false);
            controls.setVisibility(View.GONE);
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
    }
    private void applyKey(){
        if(!foreground)return;
        if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}
        boolean valid=AmapAccess.configured(keyInput.getText().toString());
        if(valid){try{saved.save(keyInput.getText().toString());dispose();keys.replace(keyInput.getText().toString());}catch(Exception e){status.setText("无法安全保存Key，未启用地图；请检查设备安全存储后重试");return;}}keyInput.getText().clear();keyInput.clearFocus();
        InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(keyInput.getWindowToken(),0);
        open.setEnabled(keys.configured());status.setText(valid?"Key已加密保存，格式检查不代表鉴权通过":"Key格式无效，原有配置未改变");if(valid&&consent)initialize();
    }
    private void clearKey(){
        if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}
        consent=false;dispose();keys.clear();boolean removed=saved.clear();if(keyInput!=null){keyInput.getText().clear();keyInput.clearFocus();InputMethodManager ime=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(keyInput.getWindowToken(),0);}if(open!=null)open.setEnabled(false);if(status!=null)status.setText(removed?"已删除本App保存的Key并撤回同意；SDK缓存可能仍存在":"地图已停止，但本地删除未完全成功。请在系统中清除本测试应用数据后再使用。");controls.setVisibility(View.VISIBLE);
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
        MapFixTracker.Fix<Location> observed = fixes.latest(now);
        MapFixTracker.Fix<Location> reliable = fixes.navigation(now, precise, true);
        MapFixTracker.Fix<Location> displayed = reliable != null ? reliable : observed;
        if (displayed == null) {
            locationFeed.show(null);
            setLocationDot(false);
            long age = fixes.observationAgeMillis(now);
            status.setText((age >= 0 ? "距上次有效定位 " + age / 1000 + " 秒，正在自动重新获取。\n" : "") + (locationProblem != null ? locationProblem
                    : precise ? "正在获取新定位。室内精度可能较低，移动到开阔处可帮助接收卫星信号。"
                    : "当前只有大致位置权限。点「定位/权限」开启精确位置。"));
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
                    : "定位未授权。可浏览地图，点「定位/权限」进入应用设置。");
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
        attachNavigation();
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
        if (map != null) map.onPause();
        super.onPause();
    }
    @Override protected void onDestroy(){releaseMap();keys.clear();super.onDestroy();}
}
