package com.motonav.companion;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Fail-closed, Android-independent update policy. No configurable update origin. */
public final class UpdatePolicy {
    public static final String APPLICATION_ID = "com.motonav.recovery";
    public static final String RELEASE_PREFIX = "/gargamelzhao0-del/motonav/releases/download/";
    public static final String MANIFEST_NAME = "motonav-update.json";
    public static final String MANIFEST_URL = "https://github.com/gargamelzhao0-del/motonav/releases/latest/download/" + MANIFEST_NAME;
    public static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    public static final long MAX_MANIFEST_BYTES = 32 * 1024;
    public static final int MAX_REDIRECTS = 5;
    public static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    private UpdatePolicy() {}

    public static final class Release {
        public final int schemaVersion, minSdk;
        public final String applicationId, versionName, apkUrl, sha256;
        public final long versionCode, sizeBytes;
        public Release(int schemaVersion, String applicationId, long versionCode, String versionName,
                       int minSdk, String apkUrl, String sha256, long sizeBytes) {
            this.schemaVersion = schemaVersion; this.applicationId = applicationId;
            this.versionCode = versionCode; this.versionName = versionName; this.minSdk = minSdk;
            this.apkUrl = apkUrl; this.sha256 = sha256; this.sizeBytes = sizeBytes;
        }
    }

    public static void validateRelease(Release release, String installedPackage, long installedVersion, int sdk) {
        require(release != null && release.schemaVersion == 1, "manifest_schema");
        require(APPLICATION_ID.equals(installedPackage) && APPLICATION_ID.equals(release.applicationId), "package_mismatch");
        require(release.versionCode > 0 && release.versionCode <= Integer.MAX_VALUE, "manifest_version");
        require(release.versionCode > installedVersion, "version_not_newer");
        require(release.versionName != null && release.versionName.matches("[A-Za-z0-9][A-Za-z0-9._+ -]{0,79}"), "manifest_name");
        require(release.minSdk >= 26 && release.minSdk <= sdk, "unsupported_android");
        require(release.sizeBytes > 0 && release.sizeBytes <= MAX_APK_BYTES, "manifest_size");
        require(release.sha256 != null && release.sha256.matches("[a-fA-F0-9]{64}"), "manifest_hash");
        require(isOfficialAsset(release.apkUrl, false), "untrusted_source");
    }

    public static boolean isOfficialAsset(String value, boolean manifest) {
        URI uri = secureUri(value);
        if (uri == null || !"github.com".equals(uri.getHost()) || uri.getRawQuery() != null) return false;
        String path = uri.getRawPath();
        if (manifest && MANIFEST_URL.equals(value)) return true;
        if (path == null || !path.startsWith(RELEASE_PREFIX)) return false;
        String[] parts = path.substring(RELEASE_PREFIX.length()).split("/", -1);
        return parts.length == 2 && segment(parts[0]) && segment(parts[1])
                && (manifest ? MANIFEST_NAME.equals(parts[1]) : parts[1].endsWith(".apk"));
    }

    /** Every hop must stay HTTPS. Only a trusted repo asset can originate a CDN request. */
    public static boolean allowedRedirect(String origin, String destination, boolean manifest) {
        if (!isOfficialAsset(origin, manifest)) return false;
        URI uri = secureUri(destination);
        if (uri == null) return false;
        if ("github.com".equals(uri.getHost())) {
            if (!isOfficialAsset(destination, manifest)) return false;
            // The manifest's latest endpoint may select a release tag; APK hops cannot change tag/name.
            return manifest || origin.equals(destination);
        }
        return ("release-assets.githubusercontent.com".equals(uri.getHost())
                || "objects.githubusercontent.com".equals(uri.getHost()))
                && uri.getRawPath() != null && uri.getRawPath().startsWith("/github-production-release-asset/");
    }

    private static URI secureUri(String value) {
        try {
            if (value == null || value.length() > 8192) return null;
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawFragment() != null) return null;
            return uri;
        } catch (Exception ignored) { return null; }
    }

    private static boolean segment(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}") && !value.contains("..");
    }

    public static void validateArchive(Release release, String installedPackage, long installedVersion,
                                       int sdk, String archivePackage, long archiveVersion,
                                       String archiveName, int archiveMinSdk,
                                       byte[][] installedSigners, byte[][] archiveSigners) {
        validateRelease(release, installedPackage, installedVersion, sdk);
        require(release.applicationId.equals(archivePackage), "package_mismatch");
        require(archiveVersion == release.versionCode && archiveVersion > installedVersion, "archive_version");
        require(release.versionName.equals(archiveName), "archive_name");
        require(archiveMinSdk == release.minSdk && archiveMinSdk <= sdk, "unsupported_android");
        require(sameSigners(installedSigners, archiveSigners), "signer_mismatch");
    }

    /** Exact current signer set, not shared signing-history ancestry. Missing/duplicate certs fail. */
    public static boolean sameSigners(byte[][] installed, byte[][] archive) {
        if (installed == null || archive == null || installed.length == 0 || installed.length != archive.length) return false;
        Set<String> a = signerSet(installed), b = signerSet(archive);
        return a != null && b != null && a.equals(b);
    }

    private static Set<String> signerSet(byte[][] signatures) {
        Set<String> values = new HashSet<>();
        for (byte[] value : signatures) {
            if (value == null || value.length == 0 || !values.add(Arrays.toString(value))) return null;
        }
        return values;
    }

    public static void validateHash(String expected, String actual) {
        require(expected != null && expected.matches("[a-fA-F0-9]{64}") && actual != null
                && expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT)), "hash_mismatch");
    }

    public static boolean sharedFilename(String name) {
        return name != null && name.matches("[a-f0-9]{32}\\.apk");
    }

    private static void require(boolean allowed, String code) {
        if (!allowed) throw new IllegalArgumentException(code);
    }
}
