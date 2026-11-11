package com.motonav.companion;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Foreground-only BLE, one GATT operation and one application frame in flight. */
@SuppressLint("MissingPermission")
public final class BleLink {
    public static final UUID SERVICE=UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public interface Listener { void status(String text); void found(BluetoothDevice device); void ready(); void disconnected(); void acknowledged(int seq); }
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothLeScanner scanner;
    private ScanCallback activeScanCallback;
    private boolean ready;
    private boolean scanning;
    private Protocol.Command pending;
    private Runnable completion;
    private List<byte[]> chunks;
    private int chunkIndex;
    private boolean writing;
    private boolean ackReceived;
    private final StringBuilder incoming=new StringBuilder();
    private final Protocol protocol=new Protocol();
    private final NetworkProtocol networkProtocol=new NetworkProtocol();
    private LocalNetwork network;
    private List<Protocol.Command> transfer;
    private int transferIndex;
    private final Runnable timeout=()->fail("设备响应超时，已断开。请重新连接。");
    private final Runnable scanTimeout=this::onScanTimeout;
    private void onScanTimeout(){ stopScan(); listener.status("扫描结束；没有找到设备时，请确认固件和蓝牙已开启。"); }
    public BleLink(Context context,Listener listener) { this.context=context;this.listener=listener; }
    public boolean ready(){ return ready; }
    public boolean busy(){ return pending!=null; }
    public boolean sessionOpen(){return gatt!=null;}
    public boolean version2(){return network!=null;}
    public boolean configureNetwork(LocalNetwork selected){if(gatt!=null)return false;network=selected;return true;}
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
        disconnect(); listener.status("正在连接设备…");
        try {gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);handler.postDelayed(timeout,15000);}
        catch(SecurityException|IllegalArgumentException e){fail("连接失败，请检查蓝牙权限。");}
    }
    public void disconnect(){
        stopScan();handler.removeCallbacks(timeout);ready=false;pending=null;completion=null;chunks=null;chunkIndex=0;ackReceived=false;rx=null;writing=false;incoming.setLength(0);protocol.reset();networkProtocol.reset();transfer=null;transferIndex=0;
        BluetoothGatt old=gatt;gatt=null;
        if(old!=null){try{old.disconnect();old.close();}catch(SecurityException ignored){}}
        listener.disconnected();
    }
    private void fail(String message){disconnect();listener.status(message);}
    public boolean sendPosition(Simulation simulation,Runnable done){
        if(!ready||busy()) return false;
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
        if(network==null){send(protocol.hello(),()->send(protocol.route(),()->{ready=true;listener.ready();}));return;}
        send(networkProtocol.hello(),()->{transfer=networkProtocol.transfer(network);transferIndex=0;sendNextNetworkFrame();});
    }
    private void send(Protocol.Command command,Runnable done){
        pending=command;completion=done;chunks=Protocol.chunks(command.wire());chunkIndex=0;ackReceived=false;
        handler.removeCallbacks(timeout);handler.postDelayed(timeout,3000);writeNext();
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
        int sequence=pending.sequence();Runnable done=completion;pending=null;completion=null;handler.removeCallbacks(timeout);
        listener.acknowledged(sequence);if(done!=null)done.run();
    }
    private void notification(byte[] bytes){
        for(byte b:bytes){
            if(b=='\n'){
                String line=incoming.toString();incoming.setLength(0);
                if(pending==null)continue;
                if(Protocol.acknowledged(line,pending.sequence())){ackReceived=true;finishIfAcknowledged();}
                else {fail("设备拒绝数据或协议不匹配："+line);return;}
            } else if(b>=32&&b<=126&&incoming.length()<32) incoming.append((char)b);
            else {fail("设备返回无效数据。");return;}
        }
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){handler.post(()->{
            if(g!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS||state==BluetoothProfile.STATE_DISCONNECTED){fail("设备已断开（"+status+"）。模拟已暂停。");return;}
            if(state==BluetoothProfile.STATE_CONNECTED){listener.status("连接成功，正在验证服务…");try{if(!g.discoverServices())fail("服务发现失败。");}catch(SecurityException e){fail("蓝牙权限不可用。");}}
        });}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){handler.post(()->{
            if(g!=gatt)return;
            BluetoothGattService service=g.getService(SERVICE);
            rx=service==null?null:service.getCharacteristic(RX);
            BluetoothGattCharacteristic tx=service==null?null:service.getCharacteristic(TX);
            BluetoothGattDescriptor descriptor=tx==null?null:tx.getDescriptor(CCCD);
            if(status!=BluetoothGatt.GATT_SUCCESS||rx==null||tx==null||descriptor==null||(rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0||(tx.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0){fail("设备不支持所需MotoNav BLE写入/通知服务，请检查固件。");return;}
            try{
                if(!g.setCharacteristicNotification(tx,true)){fail("无法开启设备回执。");return;}
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if(!g.writeDescriptor(descriptor))fail("回执订阅失败。");
            }catch(SecurityException e){fail("蓝牙权限不可用。");}
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status){handler.post(()->{
            if(g!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("设备回执订阅失败。");return;}
            beginProtocol();
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic characteristic,int status){handler.post(()->{
            if(g!=gatt||pending==null)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){fail("写入失败（"+status+"）。");return;}writeNext();
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic){byte[] bytes=characteristic.getValue().clone();handler.post(()->{if(g==gatt)notification(bytes);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic characteristic,byte[] value){byte[] bytes=value.clone();handler.post(()->{if(g==gatt)notification(bytes);});}
    };
}
