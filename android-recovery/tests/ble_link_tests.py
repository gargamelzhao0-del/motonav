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
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 10000;}}',
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
        public int discoveries,descriptorWrites,writes; public boolean closed,writeDescriptorResult=true;
        public BluetoothGattDescriptor descriptor; public BluetoothGattCharacteristic lastWrite;
        public BluetoothGatt(BluetoothDevice d,BluetoothGattCallback c){device=d;callback=c;}
        public BluetoothDevice getDevice(){return device;} public void disconnect(){} public void close(){closed=true;}
        public boolean discoverServices(){discoveries++;return true;} public BluetoothGattService getService(java.util.UUID u){return services.get(u);}
        public java.util.List<BluetoothGattService> getServices(){return new java.util.ArrayList<>(services.values());}
        public boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean b){return true;}
        public boolean writeDescriptor(BluetoothGattDescriptor d){descriptor=d;descriptorWrites++;return writeDescriptorResult;}
        public boolean writeCharacteristic(BluetoothGattCharacteristic c){lastWrite=c;writes++;return true;} }''',
    'android/bluetooth/BluetoothGattCallback.java': '''package android.bluetooth; public class BluetoothGattCallback {
        public void onServiceChanged(BluetoothGatt g){} public void onConnectionStateChange(BluetoothGatt g,int status,int state){}
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
        public static void record(String event,Object... fields){lines.add(DiagnosticRecord.line("0".repeat(32),1,1,event,fields));} }''',
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
        Handler.clear();Diagnostics.lines.clear();link.configureNavigation(123);link.connect(device);
        BluetoothGatt g=device.last;configure(g,maps);return g;
    }
    void configure(BluetoothGatt g,boolean maps){
        var service=new BluetoothGattService();tx=new BluetoothGattCharacteristic(16);
        service.chars.put(UUID.fromString(maps?"91c40002-6b5d-4f1a-b817-52e0baf8a430":"6e400002-b5a3-f393-e0a9-e50e24dcca9e"),new BluetoothGattCharacteristic(8));
        service.chars.put(UUID.fromString(maps?"91c40003-6b5d-4f1a-b817-52e0baf8a430":"6e400003-b5a3-f393-e0a9-e50e24dcca9e"),tx);
        g.services.put(maps?BleLink.MAP_SERVICE:BleLink.SERVICE,service);
        g.callback.onConnectionStateChange(g,0,2);Handler.flush();
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
    public static void main(String[] args){subscriptionAndAck();errorsAndLateCallbacks();serviceChange();rediscovery();System.out.println("PASS: "+checks+" real BleLink callback checks (boundary doubles; no radio)");}
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
             'NavigationProtocol', 'NavigationFrame', 'DeviceRoute', 'RouteProtocol']
    sources = [root / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in names]
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                    '-d', str(output), *map(str, output.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run([java, '-cp', str(output), 'BleLinkTests'], check=True)
