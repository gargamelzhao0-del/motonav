package com.motonav.companion;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android ICU, storage and production uploader; only the HTTPS boundary is controlled. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticsRuntimeTest {
    private static final AtomicInteger requests = new AtomicInteger();
    private static volatile CountDownLatch firstResponse;
    private static volatile String firstRecord;
    private static final String HOST = "diagnostic-test.trycloudflare.com";

    @BeforeClass public static void emulatorOnlyNetworkBoundary() {
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        // This test class runs in its own instrumentation process. It cannot send data to the internet.
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
                if (!HOST.equals(url.getHost())) throw new IOException("test_network_boundary");
                return new ControlledHttps(url);
            }
        } : null);
    }
    private static void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static Object field(String name) throws Exception {
        java.lang.reflect.Field field=Diagnostics.class.getDeclaredField(name);
        field.setAccessible(true);return field.get(null);
    }
    private static <T> T stored(Callable<T> action) throws Exception {
        return ((ExecutorService)field("writer")).submit(action).get(5,TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+12000;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(25);
        assertTrue(Diagnostics.status(),condition.getAsBoolean());
    }
    @After public void stopRecording() throws Exception {
        if (firstResponse!=null) firstResponse.countDown();
        main(Diagnostics::stop);stored(()->null);
    }

    @Test public void androidJournalRecordsRecoversAndAcknowledges() throws Exception {
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "diagnostic-runtime-"+UUID.randomUUID());
        assertTrue(directory.mkdirs());
        String session="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String first=DiagnosticRecord.line(session,1,0,"user_mark","reason","runtime_journal");
        String second=DiagnosticRecord.line(session,2,1,"user_mark","reason","runtime_recovery");
        try {
            DiagnosticSpool spool=new DiagnosticSpool(directory);
            spool.append(first,true);spool.append(second,true);
            assertEquals(2,spool.size());
            spool=new DiagnosticSpool(directory);
            assertEquals(first,spool.batch(24,32768).get(0).line());
            spool.acknowledge(session,1);
            spool=new DiagnosticSpool(directory);
            assertEquals(1,spool.size());assertEquals(second,spool.batch(24,32768).get(0).line());
            spool.acknowledge(session,2);
            assertEquals(0,new DiagnosticSpool(directory).size());
        } finally {
            File[] files=directory.listFiles();if(files!=null)for(File file:files)assertTrue(file.delete());
            assertTrue(directory.delete());
        }
    }

    @Test public void actualUploaderRetainsBadAckThenConfirmsAndPersistsGoodAck() throws Exception {
        requests.set(0);firstRecord=null;firstResponse=new CountDownLatch(1);
        DiagnosticEndpoint endpoint=DiagnosticEndpoint.parse("https://"+HOST+"/#aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        main(()->Diagnostics.start(endpoint));
        main(()->Diagnostics.record("user_mark","reason","android_runtime_upload"));
        await(()->requests.get()>0);
        assertFalse(Diagnostics.status().contains("远程接收已确认"));
        assertTrue(stored(()->((DiagnosticSpool)field("spool")).size())>0);
        firstResponse.countDown();
        await(()->Diagnostics.status().contains("上传未确认"));
        assertTrue("A mismatched acknowledgement must retain the journal",
                stored(()->((DiagnosticSpool)field("spool")).size())>0);
        await(()->Diagnostics.status().contains("远程接收已确认"));
        assertTrue(requests.get()>=2);
        assertTrue((Long)field("lastDelivered")>0);
        String journal=stored(()->new String(Files.readAllBytes(new File((File)field("directory"),
                "pending-events.journal").toPath()),StandardCharsets.UTF_8));
        assertNotNull(firstRecord);assertFalse("Confirmed records are removed durably",journal.contains(firstRecord));
        try (FileOutputStream output=new FileOutputStream(NavigationRuntimeTest.evidence("diagnostics-runtime.json"))) {
            JSONObject evidence=new JSONObject().put("api",Build.VERSION.SDK_INT).put("requests",requests.get())
                    .put("real_android_journal",true).put("invalid_ack_retained",true).put("valid_ack_persisted",true)
                    .put("network_boundary","controlled_https_no_internet");
            output.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ControlledHttps extends HttpsURLConnection {
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        private int attempt;
        ControlledHttps(URL url){super(url);}
        @Override public OutputStream getOutputStream(){attempt=requests.incrementAndGet();return body;}
        @Override public int getResponseCode() throws IOException {
            if(attempt==1)try {
                if(!firstResponse.await(8,TimeUnit.SECONDS))throw new IOException("test_response_timeout");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            return 200;
        }
        @Override public InputStream getInputStream() throws IOException {
            try {
                long last=0;
                for(String line:body.toString("UTF-8").split("\n")) {
                    if(firstRecord==null)firstRecord=line;
                    last=new JSONObject(line).getLong("seq");
                }
                // A 200 response alone is insufficient; the first reply acknowledges the wrong sequence.
                return new ByteArrayInputStream(("{\"last_seq\":"+(attempt==1?last+1:last)+"}").getBytes(StandardCharsets.UTF_8));
            }catch(org.json.JSONException e){throw new IOException(e);}
        }
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public String getCipherSuite(){return "test";}
        @Override public Certificate[] getLocalCertificates(){return null;}
        @Override public Certificate[] getServerCertificates(){return null;}
    }
}
