package com.motonav.companion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** No cookies, credentials, device identifiers, keys, or location are added to requests. */
public final class UpdateTransport {
    private UpdateTransport() {}

    public interface Progress { void transferred(long downloaded, long expected); }

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
        fetch(origin, manifest, output, expectedBytes, cancellation, null);
    }

    public static void fetch(String origin, boolean manifest, OutputStream output, long expectedBytes,
                             Cancellation cancellation, Progress progress) throws IOException {
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
                    copyBounded(input, output, max, expectedBytes, deadline, cancellation, progress);
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
        return copyBounded(input, output, max, expected, deadlineNanos, cancellation, null);
    }

    public static long copyBounded(InputStream input, OutputStream output, long max, long expected,
                                   long deadlineNanos, Cancellation cancellation, Progress progress) throws IOException {
        long total = 0;
        byte[] buffer = new byte[32768];
        cancellation.check();
        checkDeadline(deadlineNanos);
        long lastProgress = System.nanoTime();
        if (progress != null) progress.transferred(0, expected);
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
            long now = System.nanoTime();
            // Bound UI work to five updates per second. Report 100% only after valid EOF.
            if (progress != null && (expected <= 0 || total < expected) && now - lastProgress >= 200_000_000L) {
                progress.transferred(total, expected);
                lastProgress = now;
            }
        }
        if (total == 0 || (expected > 0 && total != expected)) throw new IOException("download_size");
        if (progress != null) progress.transferred(total, expected);
        cancellation.check();
        return total;
    }

    /** Resume only the immutable official asset. Final full-file hash/signature checks remain mandatory. */
    public static void fetchResumable(String origin, File file, long expected, Cancellation cancellation,
                                     Progress progress) throws IOException {
        if (!UpdatePolicy.isOfficialAsset(origin, false) || expected <= 0 || expected > UpdatePolicy.MAX_APK_BYTES)
            throw new IOException("untrusted_source");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            if (output.length() > expected) output.setLength(0);
            final long offset = output.length();
            cancellation.check();
            if (progress != null) progress.transferred(offset, expected);
            if (offset == expected) return;
            long deadline = System.nanoTime() + 300L * 1_000_000_000L;
            String current = origin;
            for (int hop = 0; hop <= UpdatePolicy.MAX_REDIRECTS; hop++) {
                cancellation.check(); checkDeadline(deadline);
                if (!UpdatePolicy.allowedRedirect(origin, current, false)) throw new IOException("untrusted_redirect");
                HttpsURLConnection connection = (HttpsURLConnection) URI.create(current).toURL().openConnection();
                cancellation.connection = connection;
                try {
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                    connection.setUseCaches(false); connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("User-Agent", "MotoNav-Updater/2");
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
                    int code = connection.getResponseCode();
                    cancellation.check(); checkDeadline(deadline);
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || hop == UpdatePolicy.MAX_REDIRECTS) throw new IOException("redirect_limit");
                        try { current = URI.create(current).resolve(location).toString(); }
                        catch (IllegalArgumentException e) { throw new IOException("untrusted_redirect"); }
                        continue;
                    }
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("unexpected_encoding");
                    final long start = resumeOffset(code, connection.getHeaderField("Content-Range"),
                            connection.getContentLengthLong(), offset, expected);
                    if (start == 0) output.setLength(0);
                    output.seek(start);
                    OutputStream stream = new OutputStream() {
                        @Override public void write(int value) throws IOException { output.write(value); }
                        @Override public void write(byte[] bytes, int from, int length) throws IOException { output.write(bytes, from, length); }
                    };
                    try (InputStream input = connection.getInputStream()) {
                        copyBounded(input, stream, expected-start, expected-start, deadline, cancellation,
                                progress == null ? null : (done,total) -> progress.transferred(start+done, expected));
                    } finally { output.getFD().sync(); }
                    return;
                } finally { connection.disconnect(); cancellation.connection = null; }
            }
            throw new IOException("redirect_limit");
        }
    }
    public static long resumeOffset(int code, String range, long length, long offset, long expected) throws IOException {
        if (offset < 0 || offset >= expected || expected <= 0 || expected > UpdatePolicy.MAX_APK_BYTES) throw new IOException("download_size");
        if (code == 200) {
            if (length >= 0 && length != expected) throw new IOException("download_size");
            return 0; // Server ignored Range: replace the partial file instead of appending twice.
        }
        if (code != 206) throw new IOException(code == 404 ? "not_published" : "http_failure");
        String required = "bytes " + offset + "-" + (expected-1) + "/" + expected;
        if (!required.equals(range) || (length >= 0 && length != expected-offset)) throw new IOException("invalid_range");
        return offset;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new IOException("download_timeout");
    }
}
