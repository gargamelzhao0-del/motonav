package com.motonav.companion;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import android.os.SystemClock;

/** Foreground, explicit opt-in location. Never forwards fixes to Simulation or BLE. */
public final class PhoneLocation implements LocationListener {
    public interface Listener { void locationChanged(); }
    private final Context context;
    private final Listener listener;
    private final LocationManager manager;
    private boolean active;
    private boolean preciseAtStart;
    private long startedElapsedMs;
    private final Set<String> subscribedProviders=new HashSet<>();
    private String status="未开启真实定位";
    private LocationFix fix;
    public PhoneLocation(Context context,Listener listener){this.context=context;this.listener=listener;manager=context.getSystemService(LocationManager.class);}
    public boolean active(){return active;}
    public String status(){return status;}
    public LocationFix fix(){return fix;}
    public boolean precise(){return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    public boolean permitted(){return precise()||context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @android.annotation.SuppressLint("MissingPermission") // Explicit checks below, plus SecurityException handling.
    public boolean start(){
        stopSilently();
        if(!permitted()){status="未授予定位权限；没有启动定位";listener.locationChanged();return false;}
        if(manager==null){status="此设备没有系统定位服务";listener.locationChanged();return false;}
        try{
            preciseAtStart=precise();startedElapsedMs=SystemClock.elapsedRealtime();
            List<String> providers=manager.getAllProviders();int registered=0,enabled=0;
            if(precise()&&providers.contains(LocationManager.GPS_PROVIDER)){manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());subscribedProviders.add(LocationManager.GPS_PROVIDER);registered++;if(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))enabled++;}
            if(providers.contains(LocationManager.NETWORK_PROVIDER)){manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000,0,this,Looper.getMainLooper());subscribedProviders.add(LocationManager.NETWORK_PROVIDER);registered++;if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))enabled++;}
            active=registered>0;
            status=!active?"没有可用定位提供者；大致位置不可用时可选择精确权限":enabled==0?"已订阅，但系统定位关闭；请在系统设置开启后等待新位置":precise()?"正在等待真实定位；室内GPS可能无信号":"正在等待系统大致位置；不会当作精确定位";
        }catch(SecurityException|IllegalArgumentException e){stopSilently();status="定位权限或服务不可用，已停止";}
        listener.locationChanged();return active;
    }
    public void refreshPermission(){
        if(!active)return;
        if(!permitted()){stopSilently();status="定位权限已撤回，已停止并清除坐标";listener.locationChanged();}
        else if(precise()!=preciseAtStart){start();}
    }
    public void stop(){stopSilently();status="真实定位已关闭，坐标已清除";listener.locationChanged();}
    private void stopSilently(){active=false;fix=null;subscribedProviders.clear();if(manager!=null)try{manager.removeUpdates(this);}catch(SecurityException ignored){}}
    @Override public void onLocationChanged(Location location){
        if(!active)return;
        if(!permitted()){stopSilently();status="定位权限已撤回，已停止并清除坐标";listener.locationChanged();return;}
        if(precise()!=preciseAtStart){start();return;}
        if(location.getProvider()==null||!subscribedProviders.contains(location.getProvider()))return;
        // A fix without reported accuracy is not accepted as a usable observation.
        if(!location.hasAccuracy()){status="收到无精度信息的位置，已忽略";listener.locationChanged();return;}
        try{
            if(location.getElapsedRealtimeNanos()<0){status="收到无效时间位置，已忽略";listener.locationChanged();return;}
            boolean mock=Build.VERSION.SDK_INT>=31?location.isMock():location.isFromMockProvider();
            LocationFix incoming=new LocationFix(location.getLatitude(),location.getLongitude(),location.getAccuracy(),location.getProvider()==null?"unknown":location.getProvider(),location.getElapsedRealtimeNanos()/1000000L,location.getTime(),mock);
            // Ignore pre-session, future-clock and out-of-order samples. They cannot poison ordering.
            if(incoming.elapsedRealtimeMillis()<startedElapsedMs||incoming.elapsedRealtimeMillis()>SystemClock.elapsedRealtime()){status="收到会话外或时间异常位置，已忽略";listener.locationChanged();return;}
            // A late callback must not replace a more recent observation.
            if(fix!=null&&incoming.elapsedRealtimeMillis()<fix.elapsedRealtimeMillis())return;
            fix=incoming;status="已收到系统位置；请查看质量与时效";
        }catch(IllegalArgumentException e){status="收到无效位置，已忽略";}
        listener.locationChanged();
    }
    @Override public void onProviderEnabled(String provider){if(active&&subscribedProviders.contains(provider)){status="系统定位已开启，等待新位置";listener.locationChanged();}}
    @Override public void onProviderDisabled(String provider){if(active&&subscribedProviders.contains(provider)){if(fix!=null&&fix.provider().equals(provider))fix=null;status="定位提供者已关闭；对应旧坐标已清除";listener.locationChanged();}}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
}
