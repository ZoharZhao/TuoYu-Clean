package com.tuoyu.clean;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Installs the bundled GKD build.
 *
 * GKD is a separate app; this only hands the installer a copy of the APK that
 * ships in our assets, the same way ShizukuInstaller does. No tutorial is
 * bundled on purpose — the app points at the project and lets the user read.
 */
final class GkdInstaller {

    static final String ASSERT_NAME = "gkd.apk";
    /** version of the bundled build; matches the GitHub release tag */
    static final String BUNDLED_VERSION = "1.12.1";
    static final String PACKAGE = "li.songe.gkd";
    /**
     * Subscription URL.
     *
     * The address GKD's README recommends points at fastly.jsdelivr.net, which is
     * unreliable from mainland China (and blocked outright in some networks).
     * This is the same package served by npmmirror, Alibaba's npm mirror, which
     * resolves quickly domestically. `latest` keeps it current without pinning a
     * version number.
     */
    static final String SUBSCRIPTION =
            "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5";
    static final String PROJECT_URL = "https://github.com/gkd-kit/gkd";

    private static final String SUB_DIR = "gkd";
    private static final String CACHE_NAME = "gkd-" + BUNDLED_VERSION + ".apk";

    private GkdInstaller() {
    }

    static String bundledVersion() {
        return BUNDLED_VERSION;
    }

    /** true when GKD is already installed on the device */
    static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** installed version name, or null when absent */
    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
        } catch (Throwable e) {
            return null;
        }
    }

    /** copies assets/gkd.apk into the cache dir the provider serves */
    static File extract(Context c) throws Exception {
        File dir = new File(c.getCacheDir(), SUB_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File out = new File(dir, CACHE_NAME);
        if (out.exists() && out.length() > 1024 * 1024) {
            return out;
        }
        InputStream in = null;
        FileOutputStream os = null;
        try {
            in = c.getAssets().open(ASSERT_NAME);
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /**
     * Starts the system installer for the bundled APK.
     * Returns null on success, or a short reason on failure.
     */
    static String install(Context c) {
        try {
            File apk = extract(c);
            Uri uri = Uri.parse("content://" + InstallProvider.AUTHORITY + "/" + CACHE_NAME);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
