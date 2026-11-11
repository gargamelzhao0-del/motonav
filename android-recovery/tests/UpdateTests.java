import com.motonav.companion.UpdatePolicy;
import com.motonav.companion.UpdateTransport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class UpdateTests {
    private static int checks;
    private static final String APK = "https://github.com/gargamelzhao0-del/motonav/releases/download/v0.6.0/motonav.apk";
    private static final String HASH = "a".repeat(64);
    private static final byte[][] SIGNER = {new byte[]{1, 2, 3}};
    private static UpdatePolicy.Release release(long version, String url, String hash, long size) {
        return new UpdatePolicy.Release(1, UpdatePolicy.APPLICATION_ID, version, "0.6.0", 26, url, hash, size);
    }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing run) {
        checks++;
        try { run.run(); } catch (IllegalArgumentException | IOException expected) { return; }
        catch (Exception other) { throw new AssertionError(other); }
        throw new AssertionError("expected rejection " + checks);
    }
    private static void valid(UpdatePolicy.Release release) {
        UpdatePolicy.validateRelease(release, UpdatePolicy.APPLICATION_ID, 5, 35);
    }
    private static void archive(UpdatePolicy.Release release, String name, long version, String versionName,
                                int minSdk, byte[][] installed, byte[][] candidate) {
        UpdatePolicy.validateArchive(release, UpdatePolicy.APPLICATION_ID, 5, 35, name, version, versionName,
                minSdk, installed, candidate);
    }
    public static void main(String[] args) throws Exception {
        UpdatePolicy.Release good = release(6, APK, HASH, 100);
        valid(good); checks++;
        rejects(() -> valid(release(5, APK, HASH, 100)));
        rejects(() -> valid(release(4, APK, HASH, 100)));
        rejects(() -> valid(release(-1, APK, HASH, 100)));
        rejects(() -> valid(release(Long.MAX_VALUE, APK, HASH, 100)));
        rejects(() -> valid(release(6, APK, null, 100)));
        rejects(() -> valid(release(6, APK, "a".repeat(63), 100)));
        rejects(() -> valid(release(6, APK, "z".repeat(64), 100)));
        rejects(() -> valid(release(6, APK, HASH, 0)));
        rejects(() -> valid(release(6, APK, HASH, UpdatePolicy.MAX_APK_BYTES + 1)));
        rejects(() -> valid(new UpdatePolicy.Release(2, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 26, APK, HASH, 100)));
        rejects(() -> valid(new UpdatePolicy.Release(1, "com.motonav.companion", 6, "0.6.0", 26, APK, HASH, 100)));
        rejects(() -> valid(new UpdatePolicy.Release(1, UpdatePolicy.APPLICATION_ID, 6, "0.6.0\nInstall now", 26, APK, HASH, 100)));
        rejects(() -> valid(new UpdatePolicy.Release(1, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 36, APK, HASH, 100)));
        rejects(() -> valid(new UpdatePolicy.Release(1, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 25, APK, HASH, 100)));
        rejects(() -> UpdatePolicy.validateRelease(good, "other.package", 5, 35));

        yes(UpdatePolicy.isOfficialAsset(UpdatePolicy.MANIFEST_URL, true));
        yes(UpdatePolicy.isOfficialAsset(APK, false));
        for (String bad : new String[]{APK.replace("https", "http"), APK.replace("github.com", "github.com.evil.test"),
                APK.replace("github.com", "github.com@evil.test"), APK.replace("github.com", "user@github.com"),
                APK.replace("github.com", "github.com:443"), APK.replace("motonav/releases", "other/releases"),
                APK.replace("gargamelzhao0-del", "attacker"), APK + "?download=1", APK + "#x", APK + "/file.apk",
                APK.replace("v0.6.0", ".."), APK.replace("v0.6.0", "%2e%2e"), APK.replace("v0.6.0", "v%2fother"),
                APK.replace("motonav.apk", "file.zip"), APK.replace("motonav.apk", "../motonav.apk"),
                "https://release-assets.githubusercontent.com/github-production-release-asset/file.apk", "file:///tmp/app.apk"}) {
            yes(!UpdatePolicy.isOfficialAsset(bad, false));
            rejects(() -> valid(release(6, bad, HASH, 100)));
        }
        String cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=x";
        yes(UpdatePolicy.allowedRedirect(APK, cdn, false));
        yes(UpdatePolicy.allowedRedirect(APK, cdn.replace("release-assets", "objects"), false));
        yes(UpdatePolicy.allowedRedirect(UpdatePolicy.MANIFEST_URL,
                "https://github.com/gargamelzhao0-del/motonav/releases/download/v6/motonav-update.json", true));
        yes(!UpdatePolicy.allowedRedirect(APK, APK.replace("v0.6.0", "v0.7.0"), false));
        yes(!UpdatePolicy.allowedRedirect(APK, cdn.replace("https", "http"), false));
        yes(!UpdatePolicy.allowedRedirect(APK, cdn.replace(".com/", ".com.evil.test/"), false));
        yes(!UpdatePolicy.allowedRedirect(APK, cdn.replace("github-production-release-asset", "other"), false));
        yes(!UpdatePolicy.allowedRedirect("https://evil.test/asset.apk", cdn, false));
        yes(!UpdatePolicy.allowedRedirect(UpdatePolicy.MANIFEST_URL, APK, true));
        yes(!UpdatePolicy.isOfficialAsset(UpdatePolicy.MANIFEST_URL, false));
        yes(!UpdatePolicy.isOfficialAsset(APK, true));

        yes(UpdatePolicy.sameSigners(SIGNER, new byte[][]{new byte[]{1, 2, 3}}));
        yes(!UpdatePolicy.sameSigners(null, SIGNER));
        yes(!UpdatePolicy.sameSigners(SIGNER, null));
        yes(!UpdatePolicy.sameSigners(new byte[0][], new byte[0][]));
        yes(!UpdatePolicy.sameSigners(SIGNER, new byte[][]{new byte[]{1, 2, 4}}));
        yes(!UpdatePolicy.sameSigners(new byte[][]{null}, new byte[][]{null}));
        yes(!UpdatePolicy.sameSigners(new byte[][]{new byte[0]}, new byte[][]{new byte[0]}));
        byte[][] multiple = {new byte[]{1}, new byte[]{2}};
        yes(UpdatePolicy.sameSigners(multiple, new byte[][]{new byte[]{2}, new byte[]{1}}));
        yes(!UpdatePolicy.sameSigners(multiple, new byte[][]{new byte[]{1}}));
        yes(!UpdatePolicy.sameSigners(new byte[][]{new byte[]{1}, new byte[]{1}}, new byte[][]{new byte[]{1}, new byte[]{1}}));
        archive(good, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 26, SIGNER, SIGNER); checks++;
        rejects(() -> archive(good, "com.evil.app", 6, "0.6.0", 26, SIGNER, SIGNER));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 7, "0.6.0", 26, SIGNER, SIGNER));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 5, "0.6.0", 26, SIGNER, SIGNER));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 6, "other", 26, SIGNER, SIGNER));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 27, SIGNER, SIGNER));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 26, SIGNER, null));
        rejects(() -> archive(good, UpdatePolicy.APPLICATION_ID, 6, "0.6.0", 26, SIGNER, new byte[][]{new byte[]{7}}));
        UpdatePolicy.validateHash(HASH, HASH.toUpperCase()); checks++;
        rejects(() -> UpdatePolicy.validateHash(HASH, "b".repeat(64)));
        rejects(() -> UpdatePolicy.validateHash(null, HASH));
        yes(UpdatePolicy.sharedFilename("a".repeat(32) + ".apk"));
        for (String name : new String[]{"../" + "a".repeat(32) + ".apk", "a".repeat(32) + ".pending.apk", "app.apk", "a".repeat(32) + ".apk/other", "A".repeat(32) + ".apk"}) yes(!UpdatePolicy.sharedFilename(name));

        byte[] body = {1, 2, 3};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long later = System.nanoTime() + 1_000_000_000L;
        yes(UpdateTransport.copyBounded(new ByteArrayInputStream(body), output, 3, 3, later, new UpdateTransport.Cancellation()) == 3);
        yes(java.util.Arrays.equals(body, output.toByteArray()));
        yes(UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 3, -1, later, new UpdateTransport.Cancellation()) == 3);
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 2, -1, later, new UpdateTransport.Cancellation()));
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 10, 2, later, new UpdateTransport.Cancellation()));
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 10, 4, later, new UpdateTransport.Cancellation()));
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 10, -1, later, new UpdateTransport.Cancellation()));
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 10, 3, System.nanoTime() - 1, new UpdateTransport.Cancellation()));
        UpdateTransport.Cancellation cancelled = new UpdateTransport.Cancellation(); cancelled.cancel();
        yes(cancelled.isCancelled());
        rejects(() -> UpdateTransport.copyBounded(new ByteArrayInputStream(body), new ByteArrayOutputStream(), 10, 3, later, cancelled));
        rejects(() -> UpdateTransport.fetch("http://evil.test/a.apk", false, new ByteArrayOutputStream(), 3, new UpdateTransport.Cancellation()));
        System.out.println("PASS: " + checks + " updater policy/stream checks (no Android/device/network execution)");
    }
}
