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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;
import com.amap.api.maps.TextureMapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.apis.utils.core.api.AMapUtilCoreApi;

/** Recovery preview: foreground motorcycle navigation experiment; no BLE export. */
public final class AmapActivity extends Activity {
    private static final String PRIVACY="https://lbs.amap.com/pages/privacy/";
    private static final String TERMS="https://lbs.amap.com/pages/terms/";
    private LinearLayout root;
    private FrameLayout frame;
    private MapDial dial;
    private TextView status, journeyStatus;
    private ImageView turnIcon;
    private JourneyController journey;
    private android.location.Location currentFix;
    private Button open;
    private TextureMapView map;
    private AlertDialog consentDialog;
    private boolean foreground, consent, sdkTouched;
    private final AmapKeySession keys=new AmapKeySession();
    private EditText keyInput;
    private SavedMapConfig saved;
    private ScrollView controls;
    private boolean permissionPending;
    private long lastFixNanos;
    private String locationProblem;
    private boolean locationRequested, recenterPending=true;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable freshness=new Runnable(){public void run(){
        if(foreground&&map!=null){
            boolean allowed=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
            if(!allowed&&!permissionPending){locationPermission(false);}
            else if(locationRequested){
                if(!systemLocationEnabled()){invalidateFix("系统定位开关已关闭；请打开后点定位重试。");}
                else if(locationProblem!=null)status.setText(locationProblem);
                else if(!MapStartupPolicy.fresh(android.os.SystemClock.elapsedRealtimeNanos(),lastFixNanos))status.setText("尚无有效新定位：请检查网络和室外信号，再点定位重试。");
            }
            handler.postDelayed(this,5000);
        }
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        UpdateCheckJob.restoreSchedule(this);
        saved=new SavedMapConfig(this);
        try{keys.replace(saved.load());consent=saved.consent();}catch(Exception e){consent=false;}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff101411);root.setPadding(20,16,20,16);
        label("MOTO / NAV · 独立恢复测试",22);label("前台摩托导航恢复测试 · 非0.10完整恢复 · 不用于骑行",13);
        status=label("首次填写Android Key并阅读政策。保存后在本机加密存储，重启后自动载入；仅前台定位。新包名须单独绑定，不会读取旧App数据。",14);
        keyInput=new EditText(this);keyInput.setHint("Android Key · 32位字符");keyInput.setTextColor(0xffeef3e6);keyInput.setHintTextColor(0xffaab3a7);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyInput.setSingleLine(true);
        keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);keyInput.setSaveEnabled(false);keyInput.setSaveFromParentEnabled(false);keyInput.setFreezesText(false);keyInput.setLongClickable(false);keyInput.setTextIsSelectable(false);
        keyInput.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        keyInput.setCustomInsertionActionModeCallback(new android.view.ActionMode.Callback(){public boolean onCreateActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onPrepareActionMode(android.view.ActionMode m,android.view.Menu n){return false;}public boolean onActionItemClicked(android.view.ActionMode m,android.view.MenuItem i){return false;}public void onDestroyActionMode(android.view.ActionMode m){}});
        root.addView(keyInput);button("加密保存Key / 修改Key",this::applyKey);button("删除已存Key并撤回同意",this::clearKey);
        open=button("阅读并同意后打开地图",this::askConsent);open.setEnabled(keys.configured());
        button("高德隐私政策",()->policy(PRIVACY));button("高德服务条款",()->policy(TERMS));
        label("摩托服务需高德另行授权。无语音、后台导航、自动重算保证或ESP32转发。",12);
        label("Android Key绑定包名："+getPackageName()+"\n本安装签名SHA1："+certificateSha1(),12);
        button("应用更新",()->startActivity(new Intent(this,UpdateActivity.class)));
        button("返回模拟功能（非真实导航）",()->startActivity(new Intent(this,MainActivity.class)));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff101411);page.setPadding(dp(16),dp(12),dp(16),dp(16));
        ScrollView screen=new ScrollView(this);screen.setFillViewport(true);screen.addView(page);
        if(Build.VERSION.SDK_INT>=30)screen.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        TextView brand=new TextView(this);brand.setText("MOTO / NAV");brand.setTextSize(21);brand.setLetterSpacing(0.14f);brand.setTextColor(0xffe9eddf);brand.setTypeface(null,android.graphics.Typeface.BOLD);page.addView(brand);
        TextView warning=new TextView(this);warning.setText("RECOVERY " + BuildConfig.VERSION_NAME + " ·  仅停车测试，不用于骑行");warning.setTextSize(12);warning.setTextColor(0xffc6a988);warning.setPadding(0,dp(4),0,dp(12));page.addView(warning);
        root.removeView(status);status.setTextSize(13);status.setPadding(dp(12),dp(10),dp(12),dp(10));status.setBackground(panel(0xff1a211c));page.addView(status);
        dial=new MapDial(this);frame=dial.mapFrame;page.addView(dial,new LinearLayout.LayoutParams(-1,dp(380)));
        TextView waiting=new TextView(this);waiting.setText("MOTO / NAV\n等待地图配置");waiting.setGravity(android.view.Gravity.CENTER);waiting.setTextSize(18);waiting.setTextColor(0xff9da69a);frame.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout guidance=new LinearLayout(this);guidance.setGravity(android.view.Gravity.CENTER_VERTICAL);guidance.setPadding(dp(12),dp(12),dp(12),dp(12));guidance.setBackground(panel(0xff20271f));
        turnIcon=new ImageView(this);turnIcon.setContentDescription("SDK转向图标；文字指令见右侧");guidance.addView(turnIcon,new LinearLayout.LayoutParams(dp(56),dp(56)));
        journeyStatus=new TextView(this);journeyStatus.setTextColor(0xffd4e2ba);journeyStatus.setTextSize(18);journeyStatus.setPadding(dp(12),0,0,0);journeyStatus.setText("选择目的地\n路线与导航信息只显示真实SDK结果");guidance.addView(journeyStatus,new LinearLayout.LayoutParams(0,-2,1));page.addView(guidance);
        LinearLayout actions=new LinearLayout(this);
        actions.addView(action("目的地",()->{if(journey!=null)journey.searchDialog();else status.setText("请先完成地图Key与隐私设置。");}),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(action("摩托算路",()->{if(journey!=null)journey.planDialog();else status.setText("请先完成地图设置。");}),new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(action("开始/结束",()->{if(journey!=null)journey.startOrStop();else status.setText("请先完成地图设置。");}),new LinearLayout.LayoutParams(0,-2,1));page.addView(actions);
        LinearLayout tools=new LinearLayout(this);
        tools.addView(action("定位/权限",()->locationPermission(true)),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(action("圆形/完整图",()->{dial.toggleShape();placeLogo();}),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(action("地图设置",()->controls.setVisibility(controls.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE)),new LinearLayout.LayoutParams(0,-2,1));page.addView(tools);
        controls=new ScrollView(this);controls.addView(root);page.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->placeLogo());setContentView(screen);
        if(keys.configured()&&consent)controls.setVisibility(View.GONE);
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
        consentDialog=new AlertDialog.Builder(this).setTitle("启用高德联网地图？").setMessage("本应用使用高德地图、定位、搜索及导航SDK，联网时向高德提交Android Key、包信息及服务必需的网络、设备和位置信息，服务端可见IP。定位仅在本页面前台且已获系统权限时启动，用于展示当前位置。主动搜索或规划路线时会把搜索词、起终点、车牌和排量提交高德；显式开始后提供前台GPS摩托导航测试，不传给ESP32。本次版本扩展了数据用途，因此需要重新同意一次。请先阅读页面中的隐私政策和条款。\n\nKey将用Android Keystore保护的AES-GCM加密保存于本应用私有存储，应用备份关闭。此次同意会保存，今后打开自动加载地图及定位；可在设置中删除Key并撤回同意。SDK自身缓存无法保证彻底清除。系统首次定位授权仍需本人操作。")
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
            map.getMap().setMyLocationStyle(new com.amap.api.maps.model.MyLocationStyle().myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_FOLLOW).interval(2000));
            map.getMap().setMyLocationEnabled(false);map.getMap().getUiSettings().setMyLocationButtonEnabled(false);
            map.getMap().setMapType(com.amap.api.maps.AMap.MAP_TYPE_NIGHT);
            map.getMap().getUiSettings().setZoomControlsEnabled(false);
            map.getMap().getUiSettings().setCompassEnabled(false);
            map.getMap().getUiSettings().setScaleControlsEnabled(false);
            placeLogo();
            final TextureMapView activeMap=map;
            map.getMap().setOnMyLocationChangeListener(location->{if(!foreground||map!=activeMap)return;handleLocation(location);});
            journey=new JourneyController(this,map.getMap(),new JourneyController.Host(){
                public boolean active(){return foreground&&consent&&map!=null;}
                public String key(){return keys.sdkValue();}
                public android.location.Location fix(){
                    boolean allowed=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
                    if(currentFix==null||!MapStartupPolicy.usableSnapshot(allowed,systemLocationEnabled(),android.os.SystemClock.elapsedRealtimeNanos(),lastFixNanos))return null;
                    return new android.location.Location(currentFix);
                }
                public long fixNanos(){return lastFixNanos;}
                public void report(String text){journeyStatus.setText(text);}
                public void overview(){dial.setRound(false);placeLogo();}
                public void icon(android.graphics.Bitmap bitmap){turnIcon.setImageBitmap(bitmap);}
            });
            map.getMap().setOnMapLoadedListener(()->{if(map==activeMap&&foreground&&lastFixNanos==0&&locationProblem==null&&locationRequested)status.setText("底图加载完成，正在等待定位；加载回调不代表鉴权成功");});
            map.onResume();open.setEnabled(false);status.setText("地图加载中，底图回调不代表鉴权成功");controls.setVisibility(View.GONE);locationPermission(false);handler.removeCallbacks(freshness);handler.postDelayed(freshness,15000);
        }catch(RuntimeException|LinkageError e){dispose();status.setText(R.string.amap_failed);}
    }
    private void dispose(){
        handler.removeCallbacks(freshness);lastFixNanos=0;currentFix=null;locationRequested=false;locationProblem=null;
        if(journey!=null){journey.close();journey=null;}
        if(map!=null){TextureMapView old=map;map=null;try{old.getMap().setOnMyLocationChangeListener(null);old.getMap().setOnMapLoadedListener(null);}catch(RuntimeException|LinkageError ignored){}try{old.onPause();}catch(RuntimeException|LinkageError ignored){}try{old.onDestroy();}catch(RuntimeException|LinkageError ignored){}frame.removeAllViews();}
        if(sdkTouched){sdkTouched=false;try{MapsInitializer.updatePrivacyAgree(this,false);com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this,false);}catch(RuntimeException|LinkageError ignored){}try{MapsInitializer.setApiKey("");}catch(RuntimeException|LinkageError ignored){}}
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
    private void invalidateFix(String message){currentFix=null;lastFixNanos=0;locationProblem=message;status.setText(message);}
    private void centerOnFix(android.location.Location location){
        map.getMap().animateCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(new com.amap.api.maps.model.LatLng(location.getLatitude(),location.getLongitude()),16));recenterPending=false;
    }
    private void handleLocation(android.location.Location location){
        if(!locationRequested)return;
        boolean allowed=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(!allowed){invalidateFix("定位权限已被撤销，请检查系统设置。");return;}
        if(!systemLocationEnabled()){invalidateFix("系统定位开关已关闭；请打开后点定位重试。");return;}
        if(location==null){invalidateFix("定位未返回结果，请点定位重试。");return;}
        Bundle extra=location.getExtras();
        int error=location instanceof com.amap.api.location.AMapLocation?((com.amap.api.location.AMapLocation)location).getErrorCode():extra==null?0:extra.getInt(com.amap.api.maps.model.MyLocationStyle.ERROR_CODE,0);
        if(error!=0){invalidateFix(MapStartupPolicy.locationError(error));return;}
        if(!MapStartupPolicy.validFix(location.getLatitude(),location.getLongitude(),location.hasAccuracy()?location.getAccuracy():Float.NaN,location.isFromMockProvider())){
            invalidateFix("定位数据无效或来自模拟位置，未用作算路起点；请检查定位来源后重试。");return;
        }
        long time=MapStartupPolicy.fixTime(android.os.SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),location.getElapsedRealtimeNanos(),location.getTime());
        if(time==0){invalidateFix("收到过期或时间异常的位置，等待新的定位；请勿使用旧点算路。");return;}
        currentFix=new android.location.Location(location);lastFixNanos=time;locationProblem=null;
        if(recenterPending)centerOnFix(location);
        boolean precise=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        status.setText((precise?"当前位置":"大致位置")+" · 精度约"+Math.round(location.getAccuracy())+"米 · "+(precise&&location.getAccuracy()<=100?"定位已更新":"暂不满足摩托算路精度要求"));
    }
    private void locationPermission(boolean userRetry){
        if(!foreground||map==null||!consent||permissionPending)return;
        boolean precise=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        boolean approximate=checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        MapStartupPolicy.Action action=MapStartupPolicy.location(foreground,map!=null,consent,permissionPending,precise,approximate,saved.asked(),userRetry,shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION));
        if(action==MapStartupPolicy.Action.LOCATE){
            try{
                locationRequested=true;
                if(!systemLocationEnabled()){invalidateFix("系统定位开关已关闭；请打开后点定位重试。");map.getMap().setMyLocationEnabled(false);return;}
                if(userRetry){
                    locationProblem=null;recenterPending=true;
                    map.getMap().setMyLocationStyle(new com.amap.api.maps.model.MyLocationStyle().myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_FOLLOW).interval(2000));
                    if(currentFix!=null&&MapStartupPolicy.fresh(android.os.SystemClock.elapsedRealtimeNanos(),lastFixNanos))centerOnFix(currentFix);
                    else {currentFix=null;lastFixNanos=0;map.getMap().setMyLocationEnabled(false);}
                }
                map.getMap().setMyLocationEnabled(true);
                status.setText(!systemLocationEnabled()?"系统定位开关已关闭；请打开后点定位重试。":precise?"等待当前位置…（仅前台定位）":"已获大致定位，位置可能不够精确；摩托算路需精确权限");
            }
            catch(SecurityException e){locationRequested=false;invalidateFix("定位权限已变化，请检查系统设置");}
            return;
        }
        locationRequested=false;currentFix=null;lastFixNanos=0;
        map.getMap().setMyLocationEnabled(false);
        if(action==MapStartupPolicy.Action.REQUEST){
            saved.asked(true);permissionPending=true;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION},71);
        }else{
            status.setText(approximate?"目前只有大致定位；请在系统设置开启精确定位后返回，才能请求摩托路线。":"定位未授权或已被撤销。地图仍可浏览；请点定位重试进入系统设置。");
            if(userRetry)startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==71){permissionPending=false;if(foreground)locationPermission(false);}
    }
    @Override protected void onResume(){super.onResume();foreground=true;handler.removeCallbacks(freshness);handler.postDelayed(freshness,15000);if(map==null)initialize();else{map.onResume();locationPermission(false);}}
    @Override protected void onPause(){foreground=false;currentFix=null;lastFixNanos=0;locationRequested=false;handler.removeCallbacks(freshness);if(journey!=null)journey.pause();if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}if(map!=null){map.getMap().setMyLocationEnabled(false);map.onPause();}super.onPause();}
    @Override protected void onDestroy(){dispose();keys.clear();super.onDestroy();}
}
