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
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.apis.utils.core.api.AMapUtilCoreApi;

/** Recovery preview: foreground motorcycle navigation experiment; no BLE export. */
public final class AmapActivity extends Activity {
    private static final String PRIVACY="https://lbs.amap.com/pages/privacy/";
    private static final String TERMS="https://lbs.amap.com/pages/terms/";
    private LinearLayout root;
    private FrameLayout frame;
    private TextView status, journeyStatus;
    private ImageView turnIcon;
    private JourneyController journey;
    private android.location.Location currentFix;
    private Button open;
    private MapView map;
    private AlertDialog consentDialog;
    private boolean foreground, consent, sdkTouched;
    private final AmapKeySession keys=new AmapKeySession();
    private EditText keyInput;
    private SavedMapConfig saved;
    private ScrollView controls;
    private boolean permissionPending;
    private long lastFixNanos;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable freshness=new Runnable(){public void run(){if(foreground&&map!=null){if(!MapStartupPolicy.fresh(android.os.SystemClock.elapsedRealtimeNanos(),lastFixNanos))status.setText("尚无有效新定位：检查定位开关、权限与室外信号；可点定位重试");handler.postDelayed(this,5000);}}};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        button("返回模拟功能（非真实导航）",()->startActivity(new Intent(this,MainActivity.class)));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(0xff101411);
        if(Build.VERSION.SDK_INT>=30)page.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,bars.top,0,bars.bottom);return insets;});
        root.removeView(status);page.addView(status);
        Button settings=new Button(this);settings.setText("地图设置 / Key / 隐私");settings.setOnClickListener(v->controls.setVisibility(controls.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));page.addView(settings);
        Button locate=new Button(this);locate.setText("定位重试 / 权限设置");locate.setOnClickListener(v->locationPermission(true));page.addView(locate);
        LinearLayout actions=new LinearLayout(this);
        Button search=new Button(this);search.setText("目的地");search.setOnClickListener(v->{if(journey!=null)journey.searchDialog();else status.setText("请先完成地图Key与隐私设置。");});actions.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        Button route=new Button(this);route.setText("摩托算路");route.setOnClickListener(v->{if(journey!=null)journey.planDialog();else status.setText("请先完成地图设置。");});actions.addView(route,new LinearLayout.LayoutParams(0,-2,1));
        Button start=new Button(this);start.setText("开始/结束");start.setOnClickListener(v->{if(journey!=null)journey.startOrStop();else status.setText("请先完成地图设置。");});actions.addView(start,new LinearLayout.LayoutParams(0,-2,1));page.addView(actions);
        LinearLayout guidance=new LinearLayout(this);turnIcon=new ImageView(this);guidance.addView(turnIcon,new LinearLayout.LayoutParams(80,80));
        journeyStatus=new TextView(this);journeyStatus.setTextColor(0xffb4ff65);journeyStatus.setText("真实搜索/摩托导航实验。仅停车及受控环境测试。");guidance.addView(journeyStatus,new LinearLayout.LayoutParams(0,-2,1));page.addView(guidance);
        controls=new ScrollView(this);controls.addView(root);page.addView(controls,new LinearLayout.LayoutParams(-1,0,1));
        frame=new FrameLayout(this);page.addView(frame,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        if(keys.configured()&&consent)controls.setVisibility(View.GONE);
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
            map=new MapView(this);map.onCreate(null);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(this,true,true);
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this,true);
            com.amap.api.location.AMapLocationClient.setApiKey(keys.sdkValue());
            map.getMap().setMyLocationStyle(new com.amap.api.maps.model.MyLocationStyle().myLocationType(com.amap.api.maps.model.MyLocationStyle.LOCATION_TYPE_FOLLOW).interval(2000));
            map.getMap().setMyLocationEnabled(false);map.getMap().getUiSettings().setMyLocationButtonEnabled(false);
            final MapView activeMap=map;
            map.getMap().setOnMyLocationChangeListener(location->{if(!foreground||map!=activeMap||location==null)return;currentFix=new android.location.Location(location);lastFixNanos=MapStartupPolicy.fixTime(android.os.SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis(),location.getElapsedRealtimeNanos(),location.getTime());if(MapStartupPolicy.fresh(android.os.SystemClock.elapsedRealtimeNanos(),lastFixNanos))status.setText("当前位置 · 精度约"+Math.round(location.getAccuracy())+"米 · 定位状态");});
            journey=new JourneyController(this,map.getMap(),new JourneyController.Host(){
                public boolean active(){return foreground&&consent&&map!=null;}
                public String key(){return keys.sdkValue();}
                public android.location.Location fix(){return currentFix==null?null:new android.location.Location(currentFix);}
                public long fixNanos(){return lastFixNanos;}
                public void report(String text){journeyStatus.setText(text);}
                public void icon(android.graphics.Bitmap bitmap){turnIcon.setImageBitmap(bitmap);}
            });
            map.getMap().setOnMapLoadedListener(()->{if(map==activeMap&&foreground&&lastFixNanos==0)status.setText("底图加载完成，正在等待定位；加载回调不代表鉴权成功");});
            map.onResume();open.setEnabled(false);status.setText("地图加载中，底图回调不代表鉴权成功");controls.setVisibility(View.GONE);locationPermission(false);handler.removeCallbacks(freshness);handler.postDelayed(freshness,15000);
        }catch(RuntimeException|LinkageError e){dispose();status.setText(R.string.amap_failed);}
    }
    private void dispose(){
        handler.removeCallbacks(freshness);lastFixNanos=0;currentFix=null;
        if(journey!=null){journey.close();journey=null;}
        if(map!=null){MapView old=map;map=null;try{old.getMap().setOnMyLocationChangeListener(null);old.getMap().setOnMapLoadedListener(null);}catch(RuntimeException|LinkageError ignored){}try{old.onPause();}catch(RuntimeException|LinkageError ignored){}try{old.onDestroy();}catch(RuntimeException|LinkageError ignored){}frame.removeAllViews();}
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
    private void locationPermission(boolean userRetry){
        if(!foreground||map==null||!consent||permissionPending)return;
        boolean precise=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        boolean approximate=checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        MapStartupPolicy.Action action=MapStartupPolicy.location(foreground,map!=null,consent,permissionPending,precise,approximate,saved.asked(),userRetry,shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION));
        if(action==MapStartupPolicy.Action.LOCATE){
            try{map.getMap().setMyLocationEnabled(true);status.setText(precise?"等待当前位置…（仅前台定位）":"已获大致定位，位置可能不够精确");}
            catch(SecurityException e){status.setText("定位权限已变化，请检查系统设置");}
            return;
        }
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
    @Override protected void onPause(){foreground=false;currentFix=null;lastFixNanos=0;handler.removeCallbacks(freshness);if(journey!=null)journey.pause();if(consentDialog!=null){consentDialog.dismiss();consentDialog=null;}if(map!=null){map.getMap().setMyLocationEnabled(false);map.onPause();}super.onPause();}
    @Override protected void onDestroy(){dispose();keys.clear();super.onDestroy();}
    @Override public void onLowMemory(){super.onLowMemory();if(map!=null)map.onLowMemory();}
}
