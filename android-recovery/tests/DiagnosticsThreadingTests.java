package com.motonav.companion;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Deliberately stalled platform boundaries must never stall UI calls. */
public final class DiagnosticsThreadingTests {
    private static final ExecutorService ui = Executors.newSingleThreadExecutor();
    private static int checks;
    private static final DiagnosticEndpoint ENDPOINT = DiagnosticEndpoint.parse(
            "https://diagnostic-test.trycloudflare.com/#" + "a".repeat(43));

    private static Object field(String name) throws Exception {
        var value=Diagnostics.class.getDeclaredField(name);value.setAccessible(true);return value.get(null);
    }
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    private static <T> T responsive(Callable<T> action) throws Exception {
        try { T result=ui.submit(action).get(800,TimeUnit.MILLISECONDS);checks++;return result; }
        catch(TimeoutException blocked){throw new AssertionError("UI operation waited for blocked disk/network",blocked);}
    }
    private static void responsive(Runnable action) throws Exception {responsive(()->{action.run();return null;});}
    private static void drain() throws Exception {
        ThreadPoolExecutor writer=(ThreadPoolExecutor)field("writer");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(writer.getQueue().remainingCapacity()==0&&System.nanoTime()<deadline)Thread.sleep(5);
        writer.submit(()->{}).get(3,TimeUnit.SECONDS);
    }

    private static void recoveryDoesNotBlockUi() throws Exception {
        var gate=new DiagnosticSpool.Gate("recover");DiagnosticSpool.gate=gate;
        responsive(()->Diagnostics.start(ENDPOINT));gate.await();
        responsive(()->Diagnostics.record("user_mark","reason","during_recovery"));
        check(responsive(Diagnostics::status).contains("诊断记录中"),"Status available while recovery is stalled");
        responsive(Diagnostics::stop);
        responsive(()->Diagnostics.start(null)); // Switch to local even while old journal is stuck.
        check(responsive(Diagnostics::active),"Local session starts without waiting for the remote journal");
        gate.release.countDown();drain();
        check(FakeHttps.requests==0,"Stopping a pending session prevents its HTTP send");
        responsive(Diagnostics::stop);drain();
    }

    private static void acknowledgementDoesNotBlockUi() throws Exception {
        var gate=new DiagnosticSpool.Gate("ack");DiagnosticSpool.gate=gate;
        responsive(()->Diagnostics.start(ENDPOINT));gate.await();
        check(FakeHttps.requests>0,"Exercise real upload orchestration with a fake HTTPS boundary");
        responsive(Diagnostics::status);
        responsive(()->Diagnostics.record("user_mark","reason","during_ack"));
        responsive(Diagnostics::stop);
        gate.release.countDown();drain();
    }

    private static void disconnectDoesNotBlockUi() throws Exception {
        DiagnosticSpool.gate=null;
        responsive(()->Diagnostics.start(null));drain();
        var gate=new DiagnosticSpool.Gate("close");FakeHttps.closeGate=gate;
        var connection=Diagnostics.class.getDeclaredField("connection");connection.setAccessible(true);
        connection.set(null,new FakeHttps(new URL(ENDPOINT.uploadUrl)));
        responsive(Diagnostics::stop);gate.await();
        responsive(()->Diagnostics.start(null));
        responsive(Diagnostics::status);
        gate.release.countDown();FakeHttps.closeGate=null;
        responsive(Diagnostics::stop);drain();
    }

    private static void backpressureIsBounded() throws Exception {
        var gate=new DiagnosticSpool.Gate("append");DiagnosticSpool.gate=gate;
        responsive(()->Diagnostics.start(ENDPOINT));gate.await();
        for(int i=0;i<400;i++)Diagnostics.record("user_mark","count",i);
        String status=responsive(Diagnostics::status);
        check(!status.contains("丢弃 0 条"),"Full queue reports dropped records");
        check(((ThreadPoolExecutor)field("writer")).getQueue().size()<=256,"Pending memory is bounded");
        responsive(Diagnostics::stop);
        gate.release.countDown();drain();
    }

    private static final class FakeHttps extends HttpsURLConnection {
        static volatile int requests;
        static volatile DiagnosticSpool.Gate closeGate;
        final ByteArrayOutputStream body=new ByteArrayOutputStream();
        FakeHttps(URL url){super(url);}
        @Override public OutputStream getOutputStream(){requests++;return body;}
        @Override public int getResponseCode(){return 200;}
        @Override public InputStream getInputStream(){
            var matcher=java.util.regex.Pattern.compile("\"seq\":([0-9]+)").matcher(body.toString(StandardCharsets.UTF_8));
            long last=0;while(matcher.find())last=Long.parseLong(matcher.group(1));
            return new ByteArrayInputStream(("{\"last_seq\":"+last+"}").getBytes(StandardCharsets.UTF_8));
        }
        @Override public void disconnect(){var gate=closeGate;if(gate!=null)gate.run("close");}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public String getCipherSuite(){return "test";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return null;}
    }

    public static void main(String[] args) throws Exception {
        // A test-only handler prevents all real network access by the production class.
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){
            @Override protected URLConnection openConnection(URL url){return new FakeHttps(url);}
        }:null);
        boolean success=false;
        try {
            Diagnostics.initialize(new android.content.Context(new File(args[0])));
            recoveryDoesNotBlockUi();acknowledgementDoesNotBlockUi();disconnectDoesNotBlockUi();backpressureIsBounded();
            System.out.println("PASS: "+checks+" real Diagnostics checks with blocked recovery, ACK persistence, socket close and full queue");
            success=true;
        } catch(Throwable error){error.printStackTrace();}
        finally {
            if(DiagnosticSpool.gate!=null)DiagnosticSpool.gate.release.countDown();
            if(FakeHttps.closeGate!=null)FakeHttps.closeGate.release.countDown();
            for(String name:new String[]{"writer","sender","closer"})try{((ExecutorService)field(name)).shutdownNow();}catch(Exception ignored){}
            ui.shutdownNow();
        }
        System.exit(success?0:1);
    }
}
