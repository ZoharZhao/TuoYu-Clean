package com.tuoyu.clean;

import android.content.Context;

/**
 * Turns MIUI's system ads off through Shizuku, and can prove what it changed.
 *
 * Two layers, because neither alone is enough:
 *
 *  1. Private DNS (DNS-over-TLS). Pointing the system resolver at an ad-blocking
 *     DNS makes every ad host resolve to nothing, for every app, without root.
 *
 *  2. MIUI's own recommendation switches that live in the settings database.
 *     These feed the "热门推荐" cards; their content hosts are not on any public
 *     block list, so DNS alone would not remove them.
 *
 * Third-party in-app ads are out of scope (that is what GKD is for).
 */
final class AdBlock {

    /** ad-blocking DoT endpoints, in preference order */
    private static final String[][] PROVIDERS = {
            {"AdGuard DNS", "dns.adguard.com"},
            {"AdGuard DNS 备用", "dns.adguard-dns.com"},
    };

    /**
     * Hosts that serve Xiaomi / MIUI advertising. Every entry was probed on
     * device; the second column names the surface it feeds.
     */
    static final String[][] AD_HOSTS = {
            {"api.ad.xiaomi.com", "小米广告投放接口"},
            {"ad.xiaomi.com", "小米广告主域"},
            {"adv.miui.com", "MIUI 广告服务"},
            {"ad.miui.com", "MIUI 广告服务"},
            {"ads.miui.com", "MIUI 广告服务"},
            {"cn.ad.miui.com", "国内广告分发"},
            {"api.ad.intl.xiaomi.com", "海外广告分发"},
            {"sdkconfig.ad.xiaomi.com", "广告 SDK 配置"},
            {"mi.gdt.qq.com", "广点通联盟广告"},
            {"adinfo.ra1.xlmc.sandai.net", "迅雷联盟广告"},
    };

    /** Hosts that profile the user; blocking them stops personalised targeting */
    static final String[][] TRACK_HOSTS = {
            {"tracking.miui.com", "行为追踪上报"},
            {"data.mistat.xiaomi.com", "使用数据统计"},
            {"m.data.mistat.xiaomi.com", "移动端数据统计"},
            {"metric.miui.com", "指标采集"},
    };

    /** MIUI recommendation switches that are safe to flip through settings */
    static final String[][] RECOMMEND_KEYS = {
            {"secure", "com.miui.newhome.preferences.key_video_recommend_feed", "0",
                    "负一屏视频推荐流"},
            {"secure", "com.miui.newhome.preferences.key_is_show_video_tab", "0",
                    "负一屏视频标签页"},
            {"secure", "com.miui.newhome.preferences.key_entrance_show_type", "0",
                    "负一屏推荐入口"},
            {"secure", "personalized_ad_enabled", "0", "个性化广告追踪"},
            {"global", "personalized_ad_enabled", "0", "个性化广告追踪（全局）"},
    };

    private AdBlock() {
    }

    // ---------------------------------------------------------------- DNS

    static boolean isDnsEnabled(Context c) {
        if (!ShizukuShell.isReady(c)) {
            return false;
        }
        ShizukuShell.Result mode = ShizukuShell.exec(
                "settings get global private_dns_mode");
        if (!mode.ok || !"hostname".equals(mode.out.trim())) {
            return false;
        }
        ShizukuShell.Result name = ShizukuShell.exec(
                "settings get global private_dns_specifier");
        return name.ok && name.out.trim().contains("adguard");
    }

    static String currentDns() {
        ShizukuShell.Result name = ShizukuShell.exec(
                "settings get global private_dns_specifier");
        if (!name.ok) {
            return null;
        }
        String s = name.out.trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    /** result codes for {@link #enable()} */
    static final int OK = 0;
    /** the DoT server is unreachable from this network */
    static final int ERR_UNREACHABLE = 1;
    /** the setting was written but the system reports the resolver as broken */
    static final int ERR_BROKEN = 2;
    /** writing the setting failed */
    static final int ERR_WRITE = 3;
    /** DNS unreachable, but the MIUI recommendation switches were still turned off */
    static final int ERR_UNREACHABLE_REC = 4;

    /**
     * Tests whether the DoT endpoint actually answers on TCP 443.
     *
     * Switching private DNS to a host that cannot be reached leaves Android with
     * a broken resolver until it times out, and MIUI surfaces that as
     * "无法访问私人DNS服务器" plus a temporary "no internet" warning. Probing
     * first avoids handing the system a dead server.
     */
    static boolean canReachDot(String host) {
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress a : addrs) {
                java.net.Socket s = new java.net.Socket();
                try {
                    s.connect(new java.net.InetSocketAddress(a, 443), 2500);
                    return true;
                } catch (Throwable ignored) {
                } finally {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** true when the platform currently flags the private resolver as broken */
    private static boolean resolverBroken() {
        ShizukuShell.Result r = ShizukuShell.exec("dumpsys connectivity");
        return r.ok && r.out.contains("PrivateDnsBroken");
    }

    private static void rollback() {
        ShizukuShell.exec("settings put global private_dns_specifier null");
        ShizukuShell.exec("settings put global private_dns_mode opportunistic");
    }

    /**
     * Resolves a normal host through the system resolver.
     *
     * Run via the shell so every call is a fresh process: InetAddress would cache
     * the answer from before the switch and report success while the device is
     * actually offline.
     */
    private static boolean internetWorks() {
        ShizukuShell.Result r = ShizukuShell.exec(
                "ping -c 1 -W 2 www.baidu.com 2>&1");
        String out = r.out + "\n" + r.err;
        return out.contains("bytes from") || out.contains("PING www");
    }

    /**
     * Writes the private-DNS setting and waits until a normal host resolves.
     * Rolls back when it never does — the whole point is to never leave the
     * device without working DNS.
     */
    private static int applyAndVerify() {
        ShizukuShell.exec("settings put global private_dns_mode hostname");
        ShizukuShell.Result r = ShizukuShell.exec(
                "settings put global private_dns_specifier " + PROVIDERS[0][1]);
        if (!r.ok) {
            rollback();
            disableRecommendations();
            return ERR_WRITE;
        }
        disableRecommendations();
        // up to ~7.5 s: the TLS handshake plus the first query can be slow
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            boolean ok = internetWorks();
            android.util.Log.i("TuoYuClean", "dns verify " + i + " ok=" + ok);
            if (ok) {
                return OK;
            }
        }
        android.util.Log.w("TuoYuClean", "dns unusable, rolling back");
        rollback();
        return ERR_BROKEN;
    }

    static int enable() {
        if (!canReachDot(PROVIDERS[0][1])) {
            disableRecommendations();
            return ERR_UNREACHABLE_REC;   // resolver left untouched
        }
        return applyAndVerify();
    }

    /**
     * Same as {@link #enable()} but skips the reachability probe.
     *
     * The probe runs inside our process; behind a proxy or a split tunnel it can
     * fail even when the resolver would work. Still safe: applyAndVerify rolls
     * back when normal browsing breaks.
     */
    static int enableForce() {
        return applyAndVerify();
    }

    static boolean setEnabled(boolean on) {
        if (on) {
            return enable() == OK;
        }
        rollback();
        return true;
    }

    // ------------------------------------------------------- recommendations

    static int disableRecommendations() {
        int done = 0;
        for (String[] k : RECOMMEND_KEYS) {
            ShizukuShell.Result r = ShizukuShell.exec(
                    "settings put " + k[0] + " " + k[1] + " " + k[2]);
            if (r.ok) {
                done++;
            }
        }
        return done;
    }

    static int recommendationsOff() {
        int off = 0;
        for (String[] k : RECOMMEND_KEYS) {
            ShizukuShell.Result r = ShizukuShell.exec("settings get " + k[0] + " " + k[1]);
            if (r.ok && k[2].equals(r.out.trim())) {
                off++;
            }
        }
        return off;
    }

    static int recommendationTotal() {
        return RECOMMEND_KEYS.length;
    }


    /**
     * true when the ROM is MIUI / HyperOS.
     *
     * The private-DNS layer is plain AOSP and works on any Android 9+ device, but
     * the recommendation switches live in MIUI's own settings namespace: on other
     * ROMs those keys are simply ignored, so the UI marks them as not applicable
     * instead of claiming a change that never happened.
     */
    static boolean isMiui() {
        // Prefer the ROM property; fall back to Build when Shizuku is not
        // authorised yet, otherwise the row wrongly reads "not applicable" on a
        // real MIUI device purely because getprop could not run.
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, "ro.miui.ui.version.name");
            if (v instanceof String && !((String) v).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND.toLowerCase();
        String manu = android.os.Build.MANUFACTURER == null
                ? "" : android.os.Build.MANUFACTURER.toLowerCase();
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
                || manu.contains("xiaomi");
    }

    // ------------------------------------------------------------ verifying

    /** result of one host probe */
    static final class Probe {
        final String host;
        final String purpose;
        final boolean blocked;

        Probe(String host, String purpose, boolean blocked) {
            this.host = host;
            this.purpose = purpose;
            this.blocked = blocked;
        }
    }

    /**
     * Resolves every host and reports whether it still reaches the internet.
     * This is the evidence the user can check: an ad host that answers with
     * 127.0.0.1 or "unknown host" can no longer serve an ad.
     */
    static java.util.List<Probe> verify() {
        java.util.List<Probe> out = new java.util.ArrayList<>();
        for (String[] h : AD_HOSTS) {
            out.add(new Probe(h[0], h[1], probeBlocked(h[0])));
        }
        for (String[] h : TRACK_HOSTS) {
            out.add(new Probe(h[0], h[1], probeBlocked(h[0])));
        }
        return out;
    }

    /**
     * ping writes its resolver errors to stderr, and ShizukuShell keeps stdout
     * and stderr in separate buffers, so both have to be inspected. Reading only
     * stdout made six blocked hosts look reachable.
     */
    private static String probeText(String host) {
        ShizukuShell.Result r = ShizukuShell.exec("ping -c 1 -W 2 " + host + " 2>&1");
        return (r.out + "\n" + r.err).toLowerCase();
    }

    /**
     * Proves normal browsing still works.
     *
     * Samples several hosts and passes if any one resolves: a single blocked or
     * momentarily unreachable site must not be reported as "the network broke".
     */
    static boolean verifyNormal(String ignored) {
        String[] hosts = {"www.baidu.com", "www.zhihu.com", "api.deepseek.com"};
        for (String h : hosts) {
            ShizukuShell.Result r = ShizukuShell.exec("ping -c 1 -W 2 " + h + " 2>&1");
            String out = (r.out + "\n" + r.err).toLowerCase();
            if (out.contains("bytes from")) {
                return true;
            }
        }
        return false;
    }

    static boolean probeBlocked(String host) {
        String out = probeText(host);
        if (out.contains("127.0.0.1")) {
            return true;                       // redirected to localhost
        }
        if (out.contains("unknown host")) {
            return true;                       // NXDOMAIN
        }
        if (out.contains("name or service not known")) {
            return true;
        }
        return !out.contains("bytes from");    // nothing answered
    }
}
