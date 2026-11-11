package com.motonav.companion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** No cookies, credentials, device identifiers, keys, or location are added to requests. */
public final class UpdateTransport {
    private UpdateTransport() {}

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpsURLConnection connection;
        public void cancel() {
            cancelled.set(true);
            HttpsURLConnection active = connection;
            if (active != null) active.disconnect();
        }
        public boolean isCancelled() { return cancelled.get(); }
        public void check() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        }
    }

    public static void fetch(String origin, boolean manifest, OutputStream output, long expectedBytes,
                             Cancellation cancellation) throws IOException {
        if (!UpdatePolicy.isOfficialAsset(origin, manifest)) throw new IOException("untrusted_source");
        final long max = manifest ? UpdatePolicy.MAX_MANIFEST_BYTES : UpdatePolicy.MAX_APK_BYTES;
        if (!manifest && (expectedBytes <= 0 || expectedBytes > max)) throw new IOException("manifest_size");
        final long deadline = System.nanoTime() + (manifest ? 30L : 300L) * 1_000_000_000L;
        String current = origin;
        for (int hop = 0; hop <= UpdatePolicy.MAX_REDIRECTS; hop++) {
            cancellation.check();
            checkDeadline(deadline);
            if (!UpdatePolicy.allowedRedirect(origin, current, manifest)) throw new IOException("untrusted_redirect");
            HttpsURLConnection connection = (HttpsURLConnection) URI.create(current).toURL().openConnection();
            cancellation.connection = connection;
            try {
                cancellation.check();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "MotoNav-Updater/1");
                connection.setRequestProperty("Accept", manifest ? "application/json" : "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                checkDeadline(deadline);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || hop == UpdatePolicy.MAX_REDIRECTS) throw new IOException("redirect_limit");
                    try { current = URI.create(current).resolve(location).toString(); }
                    catch (IllegalArgumentException e) { throw new IOException("untrusted_redirect"); }
                    if (!UpdatePolicy.allowedRedirect(origin, current, manifest)) throw new IOException("untrusted_redirect");
                    continue;
                }
                if (code != 200) throw new IOException(code == 404 ? "not_published" : "http_failure");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("unexpected_encoding");
                long length = connection.getContentLengthLong();
                if (length > max || (expectedBytes > 0 && length >= 0 && length != expectedBytes)) throw new IOException("download_size");
                try (InputStream input = connection.getInputStream()) {
                    copyBounded(input, output, max, expectedBytes, deadline, cancellation);
                }
                return;
            } finally {
                connection.disconnect();
                cancellation.connection = null;
            }
        }
        throw new IOException("redirect_limit");
    }

    /** Also used by host tests: reject empty, truncated, oversized and cancelled streams. */
    public static long copyBounded(InputStream input, OutputStream output, long max, long expected,
                                   long deadlineNanos, Cancellation cancellation) throws IOException {
        long total = 0;
        byte[] buffer = new byte[32768];
        while (true) {
            cancellation.check();
            checkDeadline(deadlineNanos);
            int read = input.read(buffer);
            cancellation.check();
            checkDeadline(deadlineNanos);
            if (read == -1) break;
            if (read == 0) continue;
            if (read > max - total || (expected > 0 && read > expected - total)) throw new IOException("download_size");
            output.write(buffer, 0, read);
            total += read;
        }
        if (total == 0 || (expected > 0 && total != expected)) throw new IOException("download_size");
        return total;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new IOException("download_timeout");
    }
}
