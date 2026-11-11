package com.motonav.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Only random, verified APK filenames in a dedicated private directory are ever readable. */
public final class UpdateApkProvider extends ContentProvider {
    public static final String MIME = "application/vnd.android.package-archive";
    static File directory(android.content.Context context) { return new File(context.getCacheDir(), "verified-updates"); }
    static Uri uri(android.content.Context context, File file) {
        if (!UpdatePolicy.sharedFilename(file.getName())) throw new IllegalArgumentException("invalid_update_file");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(file.getName()).build();
    }
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPathSegments().size() != 1
                || !UpdatePolicy.sharedFilename(uri.getLastPathSegment())
                || !("/" + uri.getLastPathSegment()).equals(uri.getEncodedPath())) {
            throw new FileNotFoundException("invalid_update_uri");
        }
        try {
            File root = directory(getContext()).getCanonicalFile();
            File file = new File(root, uri.getLastPathSegment());
            if (!file.getCanonicalFile().equals(file) || !root.equals(file.getCanonicalFile().getParentFile())
                    || !file.isFile() || file.length() <= 0 || file.length() > UpdatePolicy.MAX_APK_BYTES
                    || System.currentTimeMillis() - file.lastModified() > UpdatePolicy.CACHE_TTL_MS) {
                throw new FileNotFoundException("update_expired");
            }
            return file;
        } catch (IOException e) { throw new FileNotFoundException("invalid_update_file"); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read_only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {
        try { resolve(uri); return MIME; }
        catch (FileNotFoundException e) { throw new IllegalArgumentException("invalid_update_uri"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = resolve(uri);
            String[] requested = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(requested, 1);
            Object[] row = new Object[requested.length];
            for (int i = 0; i < requested.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(requested[i])) row[i] = "MotoNav-update.apk";
                else if (OpenableColumns.SIZE.equals(requested[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) { throw new IllegalArgumentException("invalid_update_uri"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
