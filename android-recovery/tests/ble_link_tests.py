"""Run the real BleLink against deterministic Android GATT callbacks, without a radio."""
from pathlib import Path
import os
import subprocess
import tempfile

stubs = {
    'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
    'android/content/Context.java': '''package android.content; public class Context {
        public static final int RECEIVER_EXPORTED=2;
        public <T> T getSystemService(Class<T> type){return null;}
        public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){return null;}
        public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){return null;}
        public void unregisterReceiver(BroadcastReceiver r){} }''',
    'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
    'android/content/Intent.java': 'package android.content; public class Intent {public <T> T getParcelableExtra(String name){return null;}}',
    'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public IntentFilter(String action){}}',
    'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long now=10000; public static long elapsedRealtime(){return now;}}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': '''package android.os; public class Handler {
        static java.util.List<Runnable> queue=new java.util.ArrayList<>();
        record Delayed(Runnable action,long millis){} static java.util.List<Delayed> delayed=new java.util.ArrayList<>();
        public Handler(Looper l){} public boolean post(Runnable r){queue.add(r);return true;}
        public boolean postDelayed(Runnable r,long t){delayed.add(new Delayed(r,t));return true;}
        public void removeCallbacks(Runnable r){queue.removeIf(x->x==r);delayed.removeIf(x->x.action()==r);}
        public static void flush(){while(!queue.isEmpty())queue.remove(0).run();}
        public static void advance(long millis){var copy=new java.util.ArrayList<>(delayed);for(var d:copy)if(d.millis()<=millis&&delayed.remove(d))d.action().run();flush();}
        public static void timeout(){advance(Long.MAX_VALUE);}
        public static void clear(){queue.clear();delayed.clear();} }''',
    'android/os/ParcelUuid.java': 'package android.os; public class ParcelUuid {public ParcelUuid(java.util.UUID u){}}',
    'android/bluetooth/BluetoothProfile.java': 'package android.bluetooth; public class BluetoothProfile {public static final int STATE_CONNECTED=2,STATE_DISCONNECTED=0;}',
    'android/bluetooth/BluetoothManager.java': 'package android.bluetooth; public class BluetoothManager {public BluetoothAdapter getAdapter(){return null;}}',
    'android/bluetooth/BluetoothAdapter.java': 'package android.bluetooth; public class BluetoothAdapter {public boolean isEnabled(){return true;} public android.bluetooth.le.BluetoothLeScanner getBluetoothLeScanner(){return null;}}',
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth; public class BluetoothDevice {
        public static final int TRANSPORT_LE=2,BOND_BONDED=12,BOND_NONE=10;
        public static final String EXTRA_DEVICE="device",ACTION_BOND_STATE_CHANGED="bond";
        public BluetoothGatt last; public int getBondState(){return BOND_BONDED;} public boolean createBond(){return true;}
        public BluetoothGatt connectGatt(android.content.Context c,boolean auto,BluetoothGattCallback cb,int transport){last=new BluetoothGatt(this,cb);return last;} }''',
    'android/bluetooth/BluetoothGatt.java': '''package android.bluetooth; public class BluetoothGatt {
        public static final int GATT_SUCCESS=0; public BluetoothDevice device; public BluetoothGattCallback callback;
        public java.util.Map<java.util.UUID,BluetoothGattService> services=new java.util.HashMap<>();
        public int discoveries,descriptorWrites,writes; public boolean closed,writeDescriptorResult=true,mtuAccepted;
        public BluetoothGattDescriptor descriptor; public BluetoothGattCharacteristic lastWrite;
        public BluetoothGatt(BluetoothDevice d,BluetoothGattCallback c){device=d;callback=c;}
        public BluetoothDevice getDevice(){return device;} public void disconnect(){} public void close(){closed=true;}
        public boolean requestMtu(int mtu){return mtuAccepted;} public boolean discoverServices(){discoveries++;return true;} public BluetoothGattService getService(java.util.UUID u){return services.get(u);}
        public java.util.List<BluetoothGattService> getServices(){return new java.util.ArrayList<>(services.values());}
        public boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean b){return true;}
        public boolean writeDescriptor(BluetoothGattDescriptor d){descriptor=d;descriptorWrites++;return writeDescriptorResult;}
        public boolean writeCharacteristic(BluetoothGattCharacteristic c){lastWrite=c;writes++;return true;} }''',
    'android/bluetooth/BluetoothGattCallback.java': '''package android.bluetooth; public class BluetoothGattCallback {
        public void onMtuChanged(BluetoothGatt g,int mtu,int status){} public void onServiceChanged(BluetoothGatt g){} public void onConnectionStateChange(BluetoothGatt g,int status,int state){}
        public void onServicesDiscovered(BluetoothGatt g,int status){} public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){}
        public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){}
        public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){}
        public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){} }''',
    'android/bluetooth/BluetoothGattService.java': '''package android.bluetooth; public class BluetoothGattService {
        public java.util.Map<java.util.UUID,BluetoothGattCharacteristic> chars=new java.util.HashMap<>();
        public BluetoothGattCharacteristic getCharacteristic(java.util.UUID u){return chars.get(u);} }''',
    'android/bluetooth/BluetoothGattCharacteristic.java': '''package android.bluetooth; public class BluetoothGattCharacteristic {
        public static final int PROPERTY_WRITE=8,PROPERTY_NOTIFY=16,WRITE_TYPE_DEFAULT=2;
        public int properties; public byte[] value; public BluetoothGattDescriptor descriptor=new BluetoothGattDescriptor();
        public BluetoothGattCharacteristic(int p){properties=p;} public int getProperties(){return properties;}
        public BluetoothGattDescriptor getDescriptor(java.util.UUID u){return descriptor;}
        public void setWriteType(int type){} public boolean setValue(byte[] v){value=v;return true;} public byte[] getValue(){return value;} }''',
    'android/bluetooth/BluetoothGattDescriptor.java': '''package android.bluetooth; public class BluetoothGattDescriptor {
        public static final byte[] ENABLE_NOTIFICATION_VALUE={1,0}; public byte[] value;
        public boolean setValue(byte[] v){value=v;return true;} }''',
    'android/bluetooth/le/BluetoothLeScanner.java': '''package android.bluetooth.le; public class BluetoothLeScanner {
        public void startScan(java.util.List<ScanFilter> f,ScanSettings s,ScanCallback c){} public void stopScan(ScanCallback c){} }''',
    'android/bluetooth/le/ScanCallback.java': 'package android.bluetooth.le; public class ScanCallback {public void onScanResult(int t,ScanResult r){} public void onScanFailed(int e){}}',
    'android/bluetooth/le/ScanResult.java': 'package android.bluetooth.le; public class ScanResult {public android.bluetooth.BluetoothDevice getDevice(){return null;}}',
    'android/bluetooth/le/ScanFilter.java': 'package android.bluetooth.le; public class ScanFilter {public static class Builder {public Builder setServiceUuid(android.os.ParcelUuid u){return this;} public ScanFilter build(){return new ScanFilter();}}}',
    'android/bluetooth/le/ScanSettings.java': 'package android.bluetooth.le; public class ScanSettings {public static final int SCAN_MODE_LOW_LATENCY=2; public static class Builder {public Builder setScanMode(int m){return this;} public ScanSettings build(){return new ScanSettings();}}}',
    'com/motonav/companion/Diagnostics.java': '''package com.motonav.companion; public class Diagnostics {
        public static java.util.List<String> lines=new java.util.ArrayList<>();
        public static int session; public static String bridgeStatus=""; public static long delivered,skipped;
        public static int bluetoothSession(){return session;} public static long bluetoothMarkers(){return 2;}
        public static void bluetoothStatus(int s,String text,long sent,long dropped){if(s==session){bridgeStatus=text;delivered=sent;skipped=dropped;}}
        public static void record(String event,Object... fields){lines.add(DiagnosticRecord.line("0".repeat(32),1,1,event,fields));} }''',
    'com/motonav/companion/BuildConfig.java': 'package com.motonav.companion; public class BuildConfig {public static final int VERSION_CODE=22;}',
}

harness = r'''
import com.motonav.companion.*;
import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
public class BleLinkTests implements BleLink.Listener {
    static int checks; String status=""; int readyCount;
    BluetoothDevice device=new BluetoothDevice();
    BleLink link=new BleLink(new android.content.Context(),this);
    BluetoothGattCharacteristic tx;
    void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public void status(String s){status=s;} public void found(BluetoothDevice d){} public void ready(){readyCount++;}
    public void disconnected(){} public void acknowledged(int seq){}
    BluetoothGatt start(boolean maps){
        Handler.clear();Diagnostics.lines.clear();Diagnostics.session=0;android.os.SystemClock.now=10000;link.configureNavigation(123);link.connect(device);
        BluetoothGatt g=device.last;configure(g,maps);return g;
    }
    void configure(BluetoothGatt g,boolean maps){
        var service=new BluetoothGattService();tx=new BluetoothGattCharacteristic(16);
        service.chars.put(UUID.fromString(maps?"91c40002-6b5d-4f1a-b817-52e0baf8a430":"6e400002-b5a3-f393-e0a9-e50e24dcca9e"),new BluetoothGattCharacteristic(8));
        service.chars.put(UUID.fromString(maps?"91c40003-6b5d-4f1a-b817-52e0baf8a430":"6e400003-b5a3-f393-e0a9-e50e24dcca9e"),tx);
        g.services.put(maps?BleLink.MAP_SERVICE:BleLink.SERVICE,service);
        g.callback.onConnectionStateChange(g,0,2);Handler.flush();
        if(g.mtuAccepted){check(g.discoveries==0,"MTU and service discovery never overlap");g.callback.onMtuChanged(g,247,0);Handler.flush();}
        check(g.discoveries==1,"Discover once per connection");
    }
    void discover(BluetoothGatt g){g.callback.onServicesDiscovered(g,0);Handler.flush();}
    static void subscriptionAndAck(){
        var t=new BleLinkTests();var g=t.start(true);t.discover(g);
        t.check(g.descriptorWrites==1&&Arrays.equals(g.descriptor.value,new byte[]{1,0}),"Enable NAV4 notifications");
        t.check(g.writes==0,"No HELLO before CCCD acknowledgement");
        g.callback.onDescriptorWrite(g,new BluetoothGattDescriptor(),0);Handler.flush();
        t.check(g.writes==0,"Unrelated descriptor cannot start protocol");
        g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
        t.check(g.writes==1,"CCCD success starts one HELLO");
        g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
        t.check(g.writes==1,"Duplicate CCCD callback cannot restart HELLO");
        g.callback.onCharacteristicWrite(g,g.lastWrite,0);Handler.flush();
        byte[] ack="A,1,NAV4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        g.callback.onCharacteristicChanged(g,new BluetoothGattCharacteristic(16),ack);Handler.flush();
        t.check(!t.link.ready(),"Ignore notifications from other services");
        g.callback.onCharacteristicChanged(g,t.tx);Handler.flush();
        t.check(!t.link.ready(),"Null legacy value ignored");
        g.callback.onCharacteristicChanged(g,t.tx,ack);Handler.flush();
        t.check(t.link.ready()&&t.link.routeMaps()&&t.readyCount==1,"Authenticated protocol ACK reaches ready");
        t.link.disconnect();
    }
    static void errorsAndLateCallbacks(){
        for(boolean maps:new boolean[]{false,true}){
            var t=new BleLinkTests();var g=t.start(maps);t.discover(g);
            if(!maps){Handler.advance(750);t.discover(g);}
            g.callback.onDescriptorWrite(g,g.descriptor,3);Handler.flush();
            t.check(t.status.contains("GATT 3")&&t.status.contains(maps?"NAV4":"NAV3"),"Expose exact subscription error and protocol");
            t.check(g.closed&&!t.link.sessionOpen()&&g.writes==0,"Failed subscription closes without HELLO");
            t.check(Diagnostics.lines.stream().anyMatch(s->s.contains("\"phase\":\"subscribe\"")&&s.contains("\"error\":3.0")),"Remote diagnostics retain phase and error");
            g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
            t.check(g.writes==0,"Late successful callback from closed GATT ignored");
        }
        var t=new BleLinkTests();var g=t.start(true);g.writeDescriptorResult=false;t.discover(g);
        t.check(g.closed&&g.writes==0&&t.status.contains("未启动"),"Distinguish write not started from remote rejection");
        t=new BleLinkTests();g=t.start(true);g.callback.onServicesDiscovered(g,133);Handler.flush();
        t.check(g.closed&&g.descriptorWrites==0&&t.status.contains("133"),"Service discovery failure stops before CCCD");
        t=new BleLinkTests();g=t.start(true);t.discover(g);Handler.timeout();
        t.check(g.closed&&Diagnostics.lines.stream().anyMatch(s->s.contains("\"phase\":\"subscribe\"")&&s.contains("\"state\":\"timeout\"")),"Subscription timeout retains phase");
    }
    static void serviceChange(){
        var t=new BleLinkTests();var old=t.start(true);t.discover(old);
        old.callback.onServiceChanged(old);Handler.flush();var fresh=t.device.last;
        t.check(old.closed&&fresh!=old,"Changed GATT database opens a fresh connection");
        t.configure(fresh,true);t.discover(fresh);
        old.callback.onDescriptorWrite(old,old.descriptor,0);Handler.flush();
        t.check(old.writes==0&&fresh.writes==0,"Old connection cannot complete new subscription");
        fresh.callback.onServiceChanged(fresh);Handler.flush();
        t.check(fresh.closed&&!t.link.sessionOpen(),"Repeated service change is bounded");
    }
    static void rediscovery(){
        var t=new BleLinkTests();var g=t.start(false);t.discover(g);
        t.check(g.descriptorWrites==0&&g.writes==0,"Do not select cached NAV3 before Service Changed can arrive");
        g.callback.onServicesDiscovered(g,0);Handler.flush();
        t.check(g.descriptorWrites==0,"Duplicate discovery callback cannot bypass migration wait");
        Handler.advance(750);
        t.check(g.discoveries==2,"One public API rediscovery");
        var service=new BluetoothGattService();var mapTx=new BluetoothGattCharacteristic(16);
        service.chars.put(UUID.fromString("91c40002-6b5d-4f1a-b817-52e0baf8a430"),new BluetoothGattCharacteristic(8));
        service.chars.put(UUID.fromString("91c40003-6b5d-4f1a-b817-52e0baf8a430"),mapTx);
        g.services.put(BleLink.MAP_SERVICE,service);t.discover(g);
        t.check(g.descriptor==mapTx.descriptor,"New NAV4 service wins over cached legacy service");
        t.link.disconnect();
        t=new BleLinkTests();g=t.start(false);t.discover(g);Handler.advance(750);t.discover(g);
        t.check(g.descriptorWrites==1&&g.discoveries==2,"Actual NAV3-only firmware remains supported with bounded retry");
        t.link.disconnect();
        t=new BleLinkTests();g=t.start(false);t.discover(g);t.link.disconnect();Handler.advance(750);
        t.check(g.discoveries==1,"Disconnect cancels the delayed rediscovery");
    }
    BluetoothGatt diagnosticReady(boolean support){
        var g=start(true);
        if(support){var service=new BluetoothGattService();service.chars.put(UUID.fromString("91d10002-6b5d-4f1a-b817-52e0baf8a430"),new BluetoothGattCharacteristic(8));g.services.put(BleLink.DIAGNOSTIC_SERVICE,service);}
        discover(g);g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
        g.callback.onCharacteristicWrite(g,g.lastWrite,0);Handler.flush();
        g.callback.onCharacteristicChanged(g,tx,"A,1,NAV4\n".getBytes());Handler.flush();
        check(link.ready(),"Ready before optional diagnostics");Diagnostics.session=456;
        return g;
    }
    static BleDiagnosticProtocol.Observation observation(){return new BleDiagnosticProtocol.Observation(14,5,0,2,10,20,30,40,100);}
    static void writeAck(BluetoothGatt g,int status){g.callback.onCharacteristicWrite(g,g.lastWrite,status);Handler.flush();}
    static void diagnosticsPriority(){
        var t=new BleLinkTests();var g=t.diagnosticReady(true);
        var diag=g.getService(BleLink.DIAGNOSTIC_SERVICE).getCharacteristic(UUID.fromString("91d10002-6b5d-4f1a-b817-52e0baf8a430"));
        t.link.offerDiagnostic(observation());t.check(g.lastWrite==diag&&diag.value[2]==0,"Diagnostic first fragment starts when idle");
        int before=g.writes;
        t.check(t.link.sendNavigation(new NavigationFrame(2,3,100,2000,300,30,9000,9000),null,null),"Navigation admitted during diagnostic ATT write");
        t.check(g.writes==before,"Only one ATT operation in flight");
        writeAck(g,0);t.check(g.lastWrite!=diag,"Navigation preempts between diagnostic fragments");
        for(int i=0;i<3;i++)writeAck(g,0);
        t.check(g.lastWrite!=diag&&t.link.busy(),"Diagnostics waits for navigation application ACK");
        g.callback.onCharacteristicChanged(g,t.tx,"A,2,OK\n".getBytes());Handler.flush();
        t.check(g.lastWrite==diag&&diag.value[2]==16,"Diagnostics resumes its own offset after navigation ACK");
        for(int i=0;i<5;i++)writeAck(g,0);
        t.check(Diagnostics.delivered==1&&!t.link.busy()&&!g.closed,"One complete diagnostic delivered without changing navigation session");
        t.link.disconnect();
    }
    static void diagnosticsLifecycle(){
        var t=new BleLinkTests();var g=t.diagnosticReady(false);int initial=g.writes;
        t.link.offerDiagnostic(observation());t.check(g.writes==initial&&Diagnostics.bridgeStatus.contains("不支持"),"Old firmware continues navigation without diagnostic writes");t.link.disconnect();
        t=new BleLinkTests();g=t.diagnosticReady(true);t.link.offerDiagnostic(observation());initial=g.writes;
        Diagnostics.session=0;t.link.stopDiagnostic();writeAck(g,0);
        t.check(g.writes==initial&&!g.closed,"Stop cancels all unsent diagnostic fragments");
        Diagnostics.session=789;android.os.SystemClock.now+=1000;t.link.offerDiagnostic(observation());
        t.check(g.lastWrite.value[2]==0,"New session restarts at offset zero");
        Diagnostics.session=790;android.os.SystemClock.now+=1000;t.link.offerDiagnostic(observation());initial=g.writes;
        t.check(g.writes==initial,"Session replacement cannot overlap ATT operations");writeAck(g,0);
        t.check(g.lastWrite.value[2]==0,"Old session callback starts new session instead of resuming old data");
        writeAck(g,17);t.check(!g.closed&&!t.link.busy(),"Diagnostic rejection does not disconnect navigation");
        android.os.SystemClock.now+=1000;t.link.offerDiagnostic(observation());
        t.check(g.lastWrite.value[2]==0,"Retry on next snapshot abandons rejected frame");
        Handler.advance(1000);t.check(g.closed,"Lost ATT callback is bounded and requires a clean reconnection");
        t=new BleLinkTests();g=t.diagnosticReady(true);
        t.link.sendNavigation(NavigationFrame.status(1),null,null);
        t.link.offerDiagnostic(observation());android.os.SystemClock.now+=1000;t.link.offerDiagnostic(observation());
        t.check(Diagnostics.skipped==1,"One latest pending snapshot, no unbounded backlog");
        t.link.disconnect();initial=g.writes;writeAck(g,0);t.check(g.writes==initial,"Late callback after disconnect is ignored");
    }
    static void previewCompatibility() throws Exception {
        var t=new BleLinkTests();var g=t.start(true);t.discover(g);
        g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
        t.check(g.lastWrite.value[4]==8,"Negotiate progressive map explicitly");
        g.callback.onCharacteristicChanged(g,t.tx,"E,NAV4\n".getBytes());Handler.flush();
        t.check(g.writes==1&&!g.closed,"Legacy rejection waits for outstanding ATT completion");
        writeAck(g,0);t.check(g.writes==2&&g.lastWrite.value[4]==5,"Fallback probes static map");
        g.callback.onCharacteristicChanged(g,t.tx,"E,NAV4\n".getBytes());Handler.flush();writeAck(g,0);
        t.check(g.lastWrite.value[4]==3,"Fallback probes route preview");
        g.callback.onCharacteristicChanged(g,t.tx,"E,NAV4\n".getBytes());Handler.flush();writeAck(g,0);
        t.check(g.writes==4&&g.lastWrite.value[4]==1,"Old firmware fallback uses normal HELLO");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,1,NAV4\n".getBytes());Handler.flush();
        t.check(t.link.ready()&&!t.link.routePreview(),"Older firmware remains usable");t.link.disconnect();
        t=new BleLinkTests();g=t.start(true);t.discover(g);
        g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();
        g.callback.onCharacteristicChanged(g,t.tx,"E,NAV4\n".getBytes());Handler.flush();writeAck(g,0);
        g.callback.onCharacteristicChanged(g,t.tx,"E,NAV4\n".getBytes());Handler.flush();writeAck(g,0);
        g.callback.onCharacteristicChanged(g,t.tx,"A,1,NAV4P\n".getBytes());Handler.flush();
        t.check(!t.link.ready(),"Preview ACK cannot overlap HELLO ATT write");writeAck(g,0);
        t.check(t.link.ready()&&t.link.routePreview(),"Preview support is negotiated without changing GATT table");
        var points=List.of(new DeviceRoute.Point(-100,-1000),new DeviceRoute.Point(100,1000));
        var plan=new DeviceRoute.Preview(1,points,1);int[] completed={0};
        t.check(t.link.sendRoutePlanIfNeeded(plan,()->completed[0]++),"Plan sends before first live guidance");
        t.check(!t.link.sendRoutePlanIfNeeded(plan,null),"No second plan while in flight");
        writeAck(g,0);writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,2,OK\n".getBytes());Handler.flush();
        t.check(completed[0]==1&&!t.link.busy()&&!t.link.lastSentLive(),"Plan completion does not create a live navigation frame");
        t.check(!t.link.sendRoutePlanIfNeeded(plan,null),"Identical plan and reason do not consume radio time every heartbeat");
        t.link.sendNavigation(NavigationFrame.status(1),null,null);
        for(int i=0;i<3;i++)writeAck(g,0);
        g.callback.onCharacteristicChanged(g,t.tx,"A,3,OK\n".getBytes());Handler.flush();
        var ack=BleLink.class.getDeclaredField("lastAckSequence");ack.setAccessible(true);
        t.check(ack.getInt(t.link)==3,"Navigation ACK is tracked for diagnostics");
        t.check(t.link.sendRoutePlanIfNeeded(new DeviceRoute.Preview(1,points,7),null),"Changed waiting reason is delivered without new geometry revision");
        writeAck(g,0);writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,4,OK\n".getBytes());Handler.flush();
        t.check(ack.getInt(t.link)==3,"Plan ACK cannot masquerade as a navigation ACK");
        t.link.disconnect();t.check(!t.link.routePreview(),"Disconnect clears negotiated preview capability");
    }
    static void localMap() throws Exception {
        var t=new BleLinkTests();Handler.clear();Diagnostics.session=0;t.link.configureNavigation(123);t.link.connect(t.device);
        var g=t.device.last;g.mtuAccepted=true;t.configure(g,true);t.discover(g);
        g.callback.onDescriptorWrite(g,g.descriptor,0);Handler.flush();writeAck(g,0);
        g.callback.onCharacteristicChanged(g,t.tx,"A,1,NAV4S2\n".getBytes());Handler.flush();
        t.check(t.link.basemaps()&&t.link.mapUpdates()&&!t.link.routePreview(),"Local map negotiated independently of legacy overview");
        var p=new DeviceRoute.Point(0,0);var q=new DeviceRoute.Point(0,500);
        var frame=new DeviceRoute.Frame(1,List.of(p,q),0,0,0);
        t.check(t.link.sendFocusIfNeeded(new Basemap.Focus(frame,new DeviceRoute.Coordinate(30,120),1,5000,600),null),"Local focus before trusted GPS");
        t.check(g.lastWrite.value.length==45,"Negotiated MTU avoids twenty-byte round trips");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,2,OK\n".getBytes());Handler.flush();
        var items=new ArrayList<Basemap.Primitive>();for(int i=0;i<23;i++)items.add(new Basemap.Primitive(0,p,q,p));
        t.link.offerBasemap(new Basemap.Scene(1,items));
        t.check(t.link.sendMapChunkIfNeeded(1,null)&&g.lastWrite.value.length==244,"Map chunk uses MTU budget");
        writeAck(g,0);t.check(g.lastWrite.value.length==53,"Remaining bytes stay in the same application frame");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,3,OK\n".getBytes());Handler.flush();
        t.check(!t.link.busy(),"Chunk ACK yields to navigation scheduler");
        t.check(t.link.sendNavigation(NavigationFrame.status(1),null,null),"WAIT can preempt the next map chunk");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,4,OK\n".getBytes());Handler.flush();
        t.check(t.link.sendMapChunkIfNeeded(1,null),"Map resumes after WAIT");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,5,OK\n".getBytes());Handler.flush();
        var ack=BleLink.class.getDeclaredField("lastAckSequence");ack.setAccessible(true);
        t.check(ack.getInt(t.link)==4,"Map ACK never counts as navigation delivery");
        t.link.offerBasemap(new Basemap.Scene(1,items));t.check(!t.link.sendMapChunkIfNeeded(1,null),"No repeated map upload");
        t.link.offerBasemap(new Basemap.Scene(1,2,false,items));
        t.check(t.link.sendMapChunkIfNeeded(1,null),"Partial update starts");
        t.link.offerBasemap(new Basemap.Scene(1,4,true,items));
        t.link.offerBasemap(new Basemap.Scene(1,3,false,items));
        writeAck(g,0);writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,6,OK\n".getBytes());Handler.flush();
        t.check(t.link.sendMapChunkIfNeeded(1,null)&&g.lastWrite.value[17]==2&&g.lastWrite.value[23]==19,"Finish first useful scene before replacing it");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,7,OK\n".getBytes());Handler.flush();
        t.check(t.link.sendMapChunkIfNeeded(1,null)&&g.lastWrite.value[17]==4&&g.lastWrite.value[23]==0,"Only newest queued scene begins at offset zero");
        writeAck(g,0);writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,8,OK\n".getBytes());Handler.flush();
        t.check(t.link.sendMapChunkIfNeeded(1,null),"Complete newest scene");
        writeAck(g,0);g.callback.onCharacteristicChanged(g,t.tx,"A,9,OK\n".getBytes());Handler.flush();
        t.link.offerBasemap(new Basemap.Scene(1,3,false,items));
        t.check(!t.link.sendMapChunkIfNeeded(1,null),"Late scene cannot roll back delivered generation");
        t.link.disconnect();t.check(!t.link.basemaps(),"Disconnect clears scene negotiation");
    }
    public static void main(String[] args) throws Exception {subscriptionAndAck();errorsAndLateCallbacks();serviceChange();rediscovery();diagnosticsPriority();diagnosticsLifecycle();previewCompatibility();localMap();System.out.println("PASS: "+checks+" real BleLink callback checks (boundary doubles; no radio)");}
}
'''

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='motonav-ble-tests-') as directory:
    output = Path(directory)
    for name, source in stubs.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
    (output / 'BleLinkTests.java').write_text(harness, encoding='utf-8')
    names = ['BleLink', 'DiagnosticRecord', 'Simulation', 'Protocol', 'LocalNetwork', 'NetworkProtocol',
             'NavigationProtocol', 'NavigationFrame', 'DeviceRoute', 'Basemap', 'RouteProtocol', 'BleDiagnosticProtocol']
    sources = [root / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in names]
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                    '-d', str(output), *map(str, output.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run([java, '-cp', str(output), 'BleLinkTests'], check=True)
