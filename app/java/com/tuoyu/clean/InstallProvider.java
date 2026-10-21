package com.tuoyu.clean;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the bundled Shizuku APK to the system installer.
 *
 * AndroidX FileProvider is unavailable here (no AndroidX dependency), so this
 * provider exposes exactly one file from our cache dir — nothing else is
 * reachable through it.
 */
public class InstallProvider extends ContentProvider {

    static final String AUTHORITY = "com.tuoyu.clean.install";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || getContext() == null) {
            return null;
        }
        // exactly two files are reachable: the bundled Shizuku and GKD builds
        if (name.equals(ShizukuInstaller.ASSERT_NAME)) {
            return new File(new File(getContext().getCacheDir(), "shizuku"), name);
        }
        if (name.startsWith("gkd-") && name.endsWith(".apk")) {
            return new File(new File(getContext().getCacheDir(), "gkd"), name);
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
