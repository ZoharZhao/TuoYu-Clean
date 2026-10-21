package com.tuoyu.clean;

import android.content.Context;
import android.os.Build;

/**
 * Detects whether the app is running inside ZhuoYiTong (卓易通).
 *
 * ZhuoYiTong is the Android virtual machine Huawei ships on HarmonyOS NEXT so
 * that APKs still run on a system without AOSP. Apps installed through it report
 * its built-in store as their installer, which is the only reliable signal —
 * Build.MANUFACTURER / BRAND / MODEL all still look like a normal Android device.
 *
 * Shizuku needs to talk to the platform over a binder that the VM may not expose,
 * so the UI warns instead of failing silently.
 */
final class EnvironmentCheck {

    /** ZhuoYiTong's built-in app store */
    private static final String ZHUOYI_STORE = "com.zhuoyi.appstore.lite";

    private EnvironmentCheck() {
    }

    /** true when this APK was installed by ZhuoYiTong */
    static boolean isZhuoyi(Context c) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            String installer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.InstallSourceInfo info =
                        pm.getInstallSourceInfo(c.getPackageName());
                installer = info == null ? null : info.getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(c.getPackageName());
            }
            return ZHUOYI_STORE.equals(installer);
        } catch (Throwable e) {
            return false;
        }
    }

    /** the installer package name, for the diagnostics line */
    static String installerName(Context c) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.InstallSourceInfo info =
                        pm.getInstallSourceInfo(c.getPackageName());
                return info == null ? null : info.getInstallingPackageName();
            }
            return pm.getInstallerPackageName(c.getPackageName());
        } catch (Throwable e) {
            return null;
        }
    }
}
