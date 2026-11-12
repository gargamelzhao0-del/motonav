package com.motonav.companion;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Update operations for background workers. No access to map config or navigation state. */
final class UpdateRepository {
    private static final Object DOWNLOAD_LOCK = new Object();
    interface Progress extends UpdateTransport.Progress { void verifying(); }
    private final Context context;
    UpdateRepository(Context context) { this.context = context.getApplicationContext(); }

    UpdatePolicy.Release check(UpdateTransport.Cancellation cancellation) throws Exception {
        cleanupExpired();
        return fetchRelease(cancellation);
    }

    UpdatePolicy.Release fetchRelease(UpdateTransport.Cancellation cancellation) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UpdateTransport.fetch(UpdatePolicy.MANIFEST_URL, true, bytes, -1, cancellation);
        cancellation.check();
        return decodeRelease(bytes.toString(StandardCharsets.UTF_8.name()));
    }
    UpdatePolicy.Release decodeRelease(String encoded) throws Exception {
        JSONObject json = new JSONObject(encoded);
        // A deliberately small, versioned schema. Do not coerce numbers/booleans from strings.
        if (json.length() != 8) throw new IllegalArgumentException("manifest_schema");
        UpdatePolicy.Release release = new UpdatePolicy.Release(integer(json, "schemaVersion"),
                string(json, "applicationId"), number(json, "versionCode"), string(json, "versionName"),
                integer(json, "minSdk"), string(json, "apkUrl"), string(json, "sha256"), number(json, "sizeBytes"));
        PackageInfo installed = installed();
        UpdatePolicy.validateRelease(release, installed.packageName, version(installed), Build.VERSION.SDK_INT);
        return release;
    }
    static String encodeRelease(UpdatePolicy.Release release) {
        try {
            return new JSONObject().put("schemaVersion",release.schemaVersion).put("applicationId",release.applicationId)
                    .put("versionCode",release.versionCode).put("versionName",release.versionName).put("minSdk",release.minSdk)
                    .put("apkUrl",release.apkUrl).put("sha256",release.sha256).put("sizeBytes",release.sizeBytes).toString();
        } catch (org.json.JSONException e) { throw new IllegalArgumentException("manifest_schema"); }
    }
    long partialBytes(UpdatePolicy.Release release) {
        return Math.min(release.sizeBytes, new File(UpdateApkProvider.directory(context), release.sha256 + ".partial").length());
    }

    File download(UpdatePolicy.Release release, UpdateTransport.Cancellation cancellation, Progress progress) throws Exception {
        synchronized (DOWNLOAD_LOCK) { return downloadLocked(release, cancellation, progress); }
    }
    private File downloadLocked(UpdatePolicy.Release release, UpdateTransport.Cancellation cancellation, Progress progress) throws Exception {
        PackageInfo installed = installed();
        UpdatePolicy.validateRelease(release, installed.packageName, version(installed), Build.VERSION.SDK_INT);
        File directory = UpdateApkProvider.directory(context).getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("storage_failure");
        String id = UUID.randomUUID().toString().replace("-", "");
        File pending = new File(directory, release.sha256 + ".partial");
        File verified = new File(directory, id + ".apk");
        boolean kept = false, downloaded = false;
        try {
            if (!pending.getCanonicalFile().getParentFile().equals(directory) || verified.exists()) throw new IOException("storage_failure");
            UpdateTransport.fetchResumable(release.apkUrl, pending, release.sizeBytes, cancellation, progress);
            downloaded = true;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            progress.verifying();
            try (FileInputStream input = new FileInputStream(pending)) {
                byte[] bytes = new byte[32768]; int count;
                while ((count = input.read(bytes)) != -1) { cancellation.check(); digest.update(bytes,0,count); }
            }
            cancellation.check();
            UpdatePolicy.validateHash(release.sha256, hex(digest.digest()));
            verifyArchive(pending, release);
            cancellation.check();
            // A new unique immutable file per download. No updater ever rewrites a handed-off APK.
            if (!pending.setReadOnly() || !pending.renameTo(verified)) throw new IOException("storage_failure");
            cancellation.check();
            kept = true;
            return verified;
        } catch (Exception e) {
            // Interrupted transfers stay resumable; invalid completed archives must be replaced.
            if (downloaded && !cancellation.isCancelled()) pending.delete();
            throw e;
        } finally {
            if (!kept) verified.delete();
        }
    }

    void verifyForInstall(File file, UpdatePolicy.Release release, UpdateTransport.Cancellation cancellation) throws Exception {
        File root = UpdateApkProvider.directory(context).getCanonicalFile();
        if (file == null || !UpdatePolicy.sharedFilename(file.getName()) || !file.getCanonicalFile().equals(file)
                || !root.equals(file.getParentFile()) || !file.isFile() || file.length() != release.sizeBytes
                || System.currentTimeMillis() - file.lastModified() > UpdatePolicy.CACHE_TTL_MS) throw new IOException("update_expired");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.check();
                count += read;
                if (count > release.sizeBytes) throw new IOException("download_size");
                digest.update(buffer, 0, read);
            }
        }
        cancellation.check();
        if (count != release.sizeBytes) throw new IOException("download_size");
        UpdatePolicy.validateHash(release.sha256, hex(digest.digest()));
        verifyArchive(file, release);
        cancellation.check();
    }

    @SuppressWarnings("deprecation")
    private void verifyArchive(File apk, UpdatePolicy.Release release) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags());
        PackageInfo installed = installed();
        if (archive == null || archive.applicationInfo == null) throw new IllegalArgumentException("invalid_apk");
        if (archive.splitNames != null && archive.splitNames.length != 0) throw new IllegalArgumentException("split_apk");
        UpdatePolicy.validateArchive(release, installed.packageName, version(installed), Build.VERSION.SDK_INT,
                archive.packageName, version(archive), archive.versionName, archive.applicationInfo.minSdkVersion,
                signers(installed), signers(archive));
    }

    @SuppressWarnings("deprecation")
    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
    }
    @SuppressWarnings("deprecation")
    private static int signingFlags() { return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES; }
    @SuppressWarnings("deprecation")
    private static byte[][] signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signatures == null || signatures.length == 0) return null;
        byte[][] bytes = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) bytes[i] = signatures[i] == null ? null : signatures[i].toByteArray();
        return bytes;
    }
    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static long number(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("manifest_schema");
        return ((Number) value).longValue();
    }
    private static int integer(JSONObject json, String key) throws Exception {
        long value = number(json, key);
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("manifest_schema");
        return (int) value;
    }
    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("manifest_schema");
        return (String) value;
    }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
    private void cleanupExpired() {
        File[] files = UpdateApkProvider.directory(context).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.isFile() && now - file.lastModified() > UpdatePolicy.CACHE_TTL_MS) file.delete();
        }
    }
}
