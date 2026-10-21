package com.tuoyu.clean;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Ships Shizuku inside the APK and installs it on demand.
 *
 * The APK lives in {@code assets/shizuku.apk}. It is copied into our cache dir
 * first, because the installer takes a {@code content://} URI that we own.
 * {@link InstallProvider} (a tiny ContentProvider backed by openFile) serves
 * that URI — AndroidX FileProvider is not used, this project has no AndroidX.
 */
final class ShizukuInstaller {

    static final String ASSERT_NAME = "shizuku.apk";
    private static final String ASSUMED_VERSION = "13.6.0";

    private ShizukuInstaller() {
    }

    static String bundledVersion() {
        return ASSUMED_VERSION;
    }

    /** copies the bundled apk into the cache dir and returns the file */
    static File extract(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "shizuku");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File out = new File(dir, ASSERT_NAME);
        InputStream in = context.getAssets().open(ASSERT_NAME);
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        return out;
    }

    /**
     * Launches the system installer for the bundled copy.
     * Returns null on success, or a short reason when it could not start.
     */
    static String install(Context context) {
        try {
            File apk = extract(context);
            android.net.Uri uri = android.net.Uri.parse(
                    "content://" + InstallProvider.AUTHORITY + "/" + ASSERT_NAME);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
