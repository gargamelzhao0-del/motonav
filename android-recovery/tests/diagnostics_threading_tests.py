"""Exercise real Diagnostics with stalled storage/HTTP boundaries; no Android device or network."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

stubs = {
    'android/Manifest.java': 'package android; public class Manifest {public static class permission {public static final String ACCESS_FINE_LOCATION="fine",ACCESS_COARSE_LOCATION="coarse";}}',
    'android/content/Context.java': '''package android.content; public class Context {
        public java.io.File files; public Context(java.io.File f){files=f;} public Context getApplicationContext(){return this;}
        public java.io.File getFilesDir(){return files;} public <T> T getSystemService(Class<T> c){return null;}
        public int checkSelfPermission(String p){return 0;} public String getPackageName(){return "test";} }''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
    'android/location/LocationManager.java': 'package android.location; public class LocationManager {public static final String GPS_PROVIDER="gps",NETWORK_PROVIDER="network"; public boolean isLocationEnabled(){return true;} public boolean isProviderEnabled(String p){return true;}}',
    'android/net/ConnectivityManager.java': 'package android.net; public class ConnectivityManager {public Object getActiveNetwork(){return null;} public NetworkCapabilities getNetworkCapabilities(Object n){return null;}}',
    'android/net/NetworkCapabilities.java': 'package android.net; public class NetworkCapabilities {public static final int TRANSPORT_WIFI=1,TRANSPORT_CELLULAR=0,TRANSPORT_VPN=4; public boolean hasTransport(int t){return false;}}',
    'android/os/Build.java': 'package android.os; public class Build {public static String MANUFACTURER="test",MODEL="test"; public static class VERSION {public static int SDK_INT=35;}}',
    'android/os/PowerManager.java': 'package android.os; public class PowerManager {public boolean isPowerSaveMode(){return false;} public boolean isDeviceIdleMode(){return false;} public boolean isIgnoringBatteryOptimizations(String p){return false;}}',
    'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
    'android/os/Handler.java': 'package android.os; public class Handler {public Handler(Looper l){} public boolean post(Runnable r){return true;} public boolean postDelayed(Runnable r,long t){return true;} public void removeCallbacks(Runnable r){}}',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;} public static long elapsedRealtimeNanos(){return System.nanoTime();}}',
    'android/util/Log.java': 'package android.util; public class Log {public static int i(String tag,String line){return 0;}}',
    'com/amap/api/location/AMapLocation.java': '''package com.amap.api.location; public class AMapLocation {
        public int getErrorCode(){return 0;} public int getLocationType(){return 1;} public long getElapsedRealtimeNanos(){return 0;}
        public long getTime(){return 0;} public float getAccuracy(){return 0;} public boolean isFromMockProvider(){return false;}
        public int getSatellites(){return 0;} public int getGpsAccuracyStatus(){return 0;} public int getTrustedLevel(){return 0;} }''',
    'com/motonav/companion/BuildConfig.java': 'package com.motonav.companion; public class BuildConfig {public static final String VERSION_NAME="test";public static final int VERSION_CODE=19;}',
    'com/motonav/companion/NavigationService.java': 'package com.motonav.companion; public class NavigationService {static boolean navigating;}',
    'org/json/JSONObject.java': '''package org.json; public class JSONObject {
        final String data; public JSONObject(String d){data=d;} public long getLong(String k){return Long.parseLong(data.replaceAll("[^0-9]",""));} }''',
    'com/motonav/companion/DiagnosticSpool.java': r'''
package com.motonav.companion;
import java.util.*;import java.util.concurrent.*;
public class DiagnosticSpool {
    public record Entry(String session,long sequence,String line){}
    public static class Gate {
        final String operation; public final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public Gate(String name){operation=name;}
        void run(String name){if(!name.equals(operation))return;entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        public void await() throws Exception {if(!entered.await(3,TimeUnit.SECONDS))throw new AssertionError("Missing storage stall: "+operation);}
    }
    public static volatile Gate gate;
    static void stall(String op){Gate g=gate;if(g!=null)g.run(op);}
    final List<Entry> entries=new ArrayList<>();
    public DiagnosticSpool(java.io.File directory) throws java.io.IOException {stall("recover");}
    public synchronized void append(String line,boolean sync) throws java.io.IOException {
        stall("append");var m=java.util.regex.Pattern.compile("\"session\":\"([0-9a-f]+)\",\"seq\":([0-9]+)").matcher(line);
        if(!m.find())throw new AssertionError(line);entries.add(new Entry(m.group(1),Long.parseLong(m.group(2)),line));
    }
    public synchronized List<Entry> batch(int max,int bytes){
        return batch(null,max,bytes);
    }
    public synchronized List<Entry> batch(String preferred,int max,int bytes){
        if(entries.isEmpty())return List.of();String session=entries.get(0).session();
        if(preferred!=null&&entries.stream().anyMatch(e->e.session().equals(preferred)))session=preferred;
        final String selected=session;
        return entries.stream().filter(e->e.session().equals(selected)).limit(max).toList();
    }
    public synchronized void acknowledge(String session,long seq) throws java.io.IOException {stall("ack");entries.removeIf(e->e.session().equals(session)&&e.sequence()<=seq);}
    public synchronized int size(){return entries.size();} public synchronized int dropped(){return 0;}
}
''',
}

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--diagnostics-source', type=Path, help='Compare the same regression against an older Diagnostics.java')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='motonav-diagnostics-threads-') as directory:
    output = Path(directory)
    for name, source in stubs.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
    names = ['Diagnostics', 'DiagnosticRecord', 'DiagnosticEndpoint', 'MapStartupPolicy', 'MapFixTracker']
    sources = [root / 'app/src/main/java/com/motonav/companion' / (name + '.java') for name in names]
    if args.diagnostics_source:
        sources[0] = args.diagnostics_source.resolve()
    sources.append(root / 'tests/DiagnosticsThreadingTests.java')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                    '-d', str(output), *map(str, output.rglob('*.java')), *map(str, sources)], check=True)
    subprocess.run([java, '-cp', str(output), 'com.motonav.companion.DiagnosticsThreadingTests', str(output / 'files')], check=True, timeout=30)
