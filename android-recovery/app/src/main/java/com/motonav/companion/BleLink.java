package com.motonav.companion;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One GATT operation and one acknowledged application frame in flight. */
@SuppressLint("MissingPermission")
public final class BleLink {
    public static final UUID SERVICE=UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID MAP_SERVICE=UUID.fromString("91c40001-6b5d-4f1a-b817-52e0baf8a430");
    private static final UUID MAP_RX=UUID.fromString("91c40002-6b5d-4f1a-b817-52e0baf8a430");
    private static final UUID MAP_TX=UUID.fromString("91c40003-6b5d-4f1a-b817-52e0baf8a430");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public interface Listener { void status(String text); void found(BluetoothDevice device); void ready(); void disconnected(); void acknowledged(int seq); }
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic notifications;
    private BluetoothGattDescriptor pendingSubscription;
    private String phase="idle";
    private BluetoothLeScanner scanner;
    private ScanCallback activeScanCallback;
    private boolean ready;
    private boolean scanning;
    private int serviceChanges;
    private boolean retriedDiscovery, awaitingDiscoveryRetry;
    private record Pending(int sequence,int helloVersion) { }
    private Pending pending;
    private Runnable completion;
    private List<byte[]> chunks;
    private int chunkIndex;
    private boolean writing;
    private boolean ackReceived;
    private final StringBuilder incoming=new StringBuilder();
    private final Protocol protocol=new Protocol();
    private final NetworkProtocol networkProtocol=new NetworkProtocol();
    private LocalNetwork network;
    private NavigationProtocol navigation;
    private RouteProtocol routeProtocol;
    private boolean routeMaps;
    public boolean routeMaps(){return ready && routeMaps;}
    private BluetoothDevice bondingDevice;
    private BroadcastReceiver bondReceiver;
    private List<Protocol.Command> transfer;
    private int transferIndex;
    private final Runnable timeout=()->{diagnostic("timeout",-1);fail("设备响应超时，已断开。请重新连接。");};
    private final Runnable scanTimeout=this::onScanTimeout;
    private void onScanTimeout(){ stopScan(); listener.status("扫描结束；没有找到设备时，请确认固件和蓝牙已开启。"); }
    public BleLink(Context context,Listener listener) { this.context=context;this.listener=listener; }
    public boolean ready(){ return ready; }
    public boolean busy(){ return pending!=null; }
    public boolean sessionOpen(){return gatt!=null || bondingDevice!=null;}
    public boolean version2(){return network!=null;}
    public boolean configureNetwork(LocalNetwork selected){if(gatt!=null)return false;network=selected;return true;}
    public void configureNavigation(int session) {
        if (sessionOpen() || bondingDevice != null) throw new IllegalStateException("Connected");
        network = null;
        navigation = new NavigationProtocol(session);
        routeProtocol = new RouteProtocol(session);
    }
    public boolean sendNavigation(NavigationFrame frame, Runnable done) {
        return sendNavigation(frame,null,done);
    }
    public boolean sendNavigation(NavigationFrame frame, DeviceRoute.Frame map, Runnable done) {
        if (!ready || busy() || navigation == null) return false;
        if(routeMaps)send(routeProtocol.snapshot(frame,map,SystemClock.elapsedRealtime()),done);
        else send(navigation.snapshot(frame, SystemClock.elapsedRealtime()), done);
        return true;
    }
    public void scan(){
        disconnect();
        try {
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null||!adapter.isEnabled()){listener.status("请先在系统设置中开启蓝牙。");return;}
            scanner=adapter.getBluetoothLeScanner();
            if(scanner==null){listener.status("无法开启 BLE 扫描。");return;}
            listener.status("正在扫描 MotoNav，10 秒后停止…");
            scanning=true;activeScanCallback=newScanCallback();
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),activeScanCallback);
            handler.postDelayed(scanTimeout,10000);
        }catch(SecurityException|IllegalStateException e){fail("蓝牙权限或蓝牙状态不可用。");}
    }
    private ScanCallback newScanCallback(){return new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult result){handler.post(()->{if(scanning&&activeScanCallback==this)listener.found(result.getDevice());});}
        @Override public void onScanFailed(int error){handler.post(()->{if(!scanning||activeScanCallback!=this)return;stopScan();listener.status("扫描失败："+error);});}
    };}
    public void stopScan(){
        scanning=false;handler.removeCallbacks(scanTimeout);
        BluetoothLeScanner oldScanner=scanner;ScanCallback oldCallback=activeScanCallback;scanner=null;activeScanCallback=null;
        if(oldScanner!=null&&oldCallback!=null)try{oldScanner.stopScan(oldCallback);}catch(SecurityException|IllegalStateException ignored){}
    }
    public void connect(BluetoothDevice device){
        serviceChanges=0;
        disconnect(); listener.status("正在连接设备…");
        if (navigation != null) {
            try {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) { bond(device); return; }
            } catch (SecurityException e) { fail("蓝牙权限不可用。"); return; }
        }
        openGatt(device);
    }
    private void openGatt(BluetoothDevice device) {
        retriedDiscovery=false;awaitingDiscoveryRetry=false;
        phase="connect";diagnostic("start",0);
        try {gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);handler.postDelayed(timeout,15000);}
        catch(SecurityException|IllegalArgumentException e){fail("连接失败，请检查蓝牙权限。");}
    }
    private void bond(BluetoothDevice device) {
        phase="pair";diagnostic("start",0);
        bondingDevice = device;
        bondReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                BluetoothDevice changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (!device.equals(changed) || !device.equals(bondingDevice)) return;
                try {
                    int state = device.getBondState();
                    diagnostic("bond_state",state);
                    if (state == BluetoothDevice.BOND_BONDED) { clearBondWait(); openGatt(device); }
                    else if (state == BluetoothDevice.BOND_NONE) fail("圆屏配对未完成，请重试连接。");
                } catch (SecurityException e) { fail("蓝牙权限已撤回。"); }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(bondReceiver, filter);
        listener.status("请在系统配对窗口输入圆屏上的六位配对码。");
        handler.postDelayed(timeout, 60000);
        if (device.getBondState() == BluetoothDevice.BOND_NONE && !device.createBond()) fail("无法启动圆屏配对。");
    }
    private void clearBondWait() {
        handler.removeCallbacks(timeout);
        bondingDevice = null;
        if (bondReceiver != null) {
            try { context.unregisterReceiver(bondReceiver); } catch (IllegalArgumentException ignored) {}
            bondReceiver = null;
        }
    }
    public void disconnect(){
        clearBondWait();
        stopScan();handler.removeCallbacks(timeout);ready=false;pending=null;completion=null;chunks=null;chunkIndex=0;ackReceived=false;rx=null;writing=false;incoming.setLength(0);protocol.reset();networkProtocol.reset();transfer=null;transferIndex=0;
        if (navigation != null) navigation.reset();
        if (routeProtocol != null) routeProtocol.reset();
        routeMaps=false;
        pendingSubscription=null;notifications=null;phase="idle";
        awaitingDiscoveryRetry=false;
        BluetoothGatt old=gatt;gatt=null;
        if(old!=null){try{old.disconnect();old.close();}catch(SecurityException ignored){}}
        listener.disconnected();
    }
    private void fail(String message){disconnect();listener.status(message);}
    private void diagnostic(String state,int error){
        Diagnostics.record("ble_state","phase",phase,"state",state,"error",error,
                "generation",navigation==null?0:routeMaps?4:3);
    }
    public boolean sendPosition(Simulation simulation,Runnable done){
        if(!ready||busy()||navigation!=null) return false;
        if((network!=null&&(simulation.totalMm()!=network.lengthMm()||!Arrays.deepEquals(simulation.route(),network.points())))||(network==null&&!Arrays.deepEquals(simulation.route(),Simulation.ROUTE))){fail("手机路线与已提交数据不一致，已安全断开。");return false;}
        send(network==null?protocol.position(simulation.distanceMm,Simulation.SPEED_MMPS,!simulation.running):networkProtocol.position(network,simulation.distanceMm,Simulation.SPEED_MMPS,!simulation.running),done);return true;
    }
    public boolean resetRoute(Runnable done){if(!ready||busy())return false;send(network==null?protocol.route():networkProtocol.restart(network.mapId()),done);return true;}
    private void sendNextNetworkFrame(){
        if(gatt==null||transfer==null)return;
        if(transferIndex==transfer.size()){transfer=null;ready=true;listener.ready();return;}
        listener.status("上传模拟局部路网 "+(transferIndex+1)+"/"+transfer.size()+"；CRC提交前不生效");
        send(transfer.get(transferIndex++),this::sendNextNetworkFrame);
    }
    private void beginProtocol(){
        phase="hello";diagnostic("start",0);
        if (navigation != null && routeMaps) { send(routeProtocol.hello(), () -> { ready=true; listener.ready(); }); return; }
        if (navigation != null) { send(navigation.hello(), () -> { ready = true; listener.ready(); }); return; }
        if(network==null){send(protocol.hello(),()->send(protocol.route(),()->{ready=true;listener.ready();}));return;}
        send(networkProtocol.hello(),()->{transfer=networkProtocol.transfer(network);transferIndex=0;sendNextNetworkFrame();});
    }
    private void send(Protocol.Command command,Runnable done){
        pending=new Pending(command.sequence(),navigation!=null&&command.wire().startsWith("H,3,")?3:0);
        completion=done;chunks=Protocol.chunks(command.wire());chunkIndex=0;ackReceived=false;
        handler.removeCallbacks(timeout);handler.postDelayed(timeout,3000);writeNext();
    }
    private void send(RouteProtocol.Command command,Runnable done){
        pending=new Pending(command.sequence(),command.hello()?4:0); completion=done;
        chunks=new ArrayList<>(); byte[] bytes=command.bytes();
        for(int i=0;i<bytes.length;i+=20)chunks.add(Arrays.copyOfRange(bytes,i,Math.min(bytes.length,i+20)));
        chunkIndex=0;ackReceived=false;handler.removeCallbacks(timeout);handler.postDelayed(timeout,3000);writeNext();
    }
    @SuppressWarnings("deprecation")
    private void writeNext(){
        if(gatt==null||pending==null)return;
        if(chunkIndex==chunks.size()){writing=false;finishIfAcknowledged();return;}
        writing=true;rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);rx.setValue(chunks.get(chunkIndex++));
        try{if(!gatt.writeCharacteristic(rx))fail("BLE 写入未启动，已断开。");}catch(SecurityException e){fail("蓝牙权限已被撤回。");}
    }
    private void finishIfAcknowledged(){
        if(pending==null||writing||!ackReceived)return;
        if(pending.helloVersion()!=0){diagnostic("acknowledged",0);phase="stream";}
        int sequence=pending.sequence();Runnable done=completion;pending=null;completion=null;handler.removeCallbacks(timeout);
        listener.acknowledged(sequence);if(done!=null)done.run();
    }
    private void notification(byte[] bytes){
        for(byte b:bytes){
            if(b=='\n'){
                String line=incoming.toString();incoming.setLength(0);
                if(pending==null)continue;
                String expected = "A," + pending.sequence() + ",NAV" + pending.helloVersion();
                boolean navHello = pending.helloVersion()!=0;
                if(navHello ? line.equals(expected) : Protocol.acknowledged(line,pending.sequence())){ackReceived=true;finishIfAcknowledged();}
                else {fail("设备拒绝数据或协议不匹配："+line);return;}
            } else if(b>=32&&b<=126&&incoming.length()<32) incoming.append((char)b);
            else {fail("设备返回无效数据。");return;}
        }
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onServiceChanged(BluetoothGatt g){handler.post(()->{
            if(g!=gatt)return;
            diagnostic("services_changed",0);
            if(++serviceChanges>1){fail("圆屏服务反复变化，请重新连接。");return;}
            BluetoothDevice device=g.getDevice();
            disconnect();listener.status("圆屏固件已更新，正在重新发现路线地图服务…");openGatt(device);
        });}
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){handler.post(()->{
            if(g!=gatt)return;
            diagnostic(state==BluetoothProfile.STATE_CONNECTED?"connected":"connection_state",status);
            if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){fail("设备已断开（"+status+"）。");return;}
            if(state==BluetoothProfile.STATE_CONNECTED){phase="discover";listener.status("连接成功，正在验证服务…");try{if(!g.discoverServices())fail("服务发现失败。");}catch(SecurityException e){fail("蓝牙权限不可用。");}}
        });}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){handler.post(()->{
            if(g!=gatt||awaitingDiscoveryRetry||pendingSubscription!=null||pending!=null||ready)return;
            diagnostic("services_discovered",status);
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("设备服务发现失败（GATT "+status+"）。");return;}
            BluetoothGattService service=g.getService(SERVICE);
            BluetoothGattService mapService=navigation==null?null:g.getService(MAP_SERVICE);
            routeMaps=mapService!=null&&mapService.getCharacteristic(MAP_RX)!=null&&mapService.getCharacteristic(MAP_TX)!=null;
            Diagnostics.record("ble_state","phase","discover","state","capabilities",
                    "selected",routeMaps,"count",g.getServices().size(),"map_service",mapService!=null,
                    "map_rx",mapService!=null&&mapService.getCharacteristic(MAP_RX)!=null,
                    "map_tx",mapService!=null&&mapService.getCharacteristic(MAP_TX)!=null);
            // A bonded Android phone may return its old database before the
            // peripheral's Service Changed indication arrives. Use one delayed,
            // public-API rediscovery before settling on the legacy service.
            if(navigation!=null&&!routeMaps&&!retriedDiscovery){
                retriedDiscovery=true;awaitingDiscoveryRetry=true;
                listener.status("正在重新确认圆屏路线地图服务…");
                handler.postDelayed(()->{
                    if(g!=gatt||!awaitingDiscoveryRetry)return;
                    awaitingDiscoveryRetry=false;diagnostic("rediscover",0);
                    try{if(!g.discoverServices())fail("圆屏服务重新发现未启动，请重新连接。");}
                    catch(SecurityException e){fail("蓝牙权限不可用。");}
                },750);
                return;
            }
            if(routeMaps)service=mapService;
            rx=service==null?null:service.getCharacteristic(routeMaps?MAP_RX:RX);
            BluetoothGattCharacteristic tx=service==null?null:service.getCharacteristic(routeMaps?MAP_TX:TX);
            BluetoothGattDescriptor descriptor=tx==null?null:tx.getDescriptor(CCCD);
            if(status!=BluetoothGatt.GATT_SUCCESS||rx==null||tx==null||descriptor==null||(rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0||(tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0){fail("设备不支持所需MotoNav BLE写入/通知服务，请检查固件。");return;}
            try{
                phase="subscribe";diagnostic("start",0);
                if(!g.setCharacteristicNotification(tx,true)){fail("无法开启设备回执。");return;}
                notifications=tx;pendingSubscription=descriptor;
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if(!g.writeDescriptor(descriptor)){diagnostic("not_started",-1);fail("回执订阅未启动，请重新连接。");}
            }catch(SecurityException e){fail("蓝牙权限不可用。");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status){handler.post(()->{
            if(g!=gatt||pendingSubscription==null||descriptor!=pendingSubscription)return;
            pendingSubscription=null;
            diagnostic("complete",status);
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("设备回执订阅失败（GATT "+status+"，NAV"+(routeMaps?4:3)+"）。");return;}
            beginProtocol();
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic characteristic,int status){handler.post(()->{
            if(g!=gatt||pending==null)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){diagnostic("write_failed",status);fail("写入失败（"+status+"）。");return;}writeNext();
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic){byte[] value=characteristic.getValue();if(value!=null)receiveNotification(g,characteristic,value);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] value){if(value!=null)receiveNotification(g,characteristic,value);}
    };
    private void receiveNotification(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] value){
        byte[] bytes=value.clone();handler.post(()->{if(g==gatt&&characteristic==notifications)notification(bytes);});
    }
}
