package com.tuoyu.clean;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * Ad hosts blocked by the local VPN.
 *
 * Two sources:
 *
 *  - {@link #CURATED}: hand-picked Xiaomi hosts, verified one by one on device.
 *    Always active.
 *  - assets/adhosts.txt: ~190k domains merged from StevenBlack/hosts and
 *    GOODBYEADS. Loaded on demand and toggleable, because a list that size
 *    inevitably contains a few functional hosts.
 *
 * {@link #ALLOW} exists because the big list turned out to contain real
 * infrastructure, e.g. t1/t2/t3.a.market.xiaomi.com are the app store's download
 * CDN. Those are matched first and always pass.
 */
final class AdHosts {

    /** hand-picked, verified: never causes collateral damage */
    static final String[] CURATED = {
            // Xiaomi / MIUI advertising
            "api.ad.xiaomi.com",
            "ad.xiaomi.com",
            "adv.miui.com",
            "ad.miui.com",
            "ads.miui.com",
            "cn.ad.miui.com",
            "api.ad.intl.xiaomi.com",
            "sdkconfig.ad.xiaomi.com",
            "mi.gdt.qq.com",
            "adinfo.ra1.xlmc.sandai.net",
            // telemetry
            "tracking.miui.com",
            "data.mistat.xiaomi.com",
            "m.data.mistat.xiaomi.com",
            "metric.miui.com",
            // weather app: wtradv = weather ad. Its sibling
            // weatherapi.market.xiaomi.com serves real forecasts.
            "wtradv.market.xiaomi.com",
            // Domestic ad exchanges. The merged list is overseas-heavy and does
            // not cover these, which is why calendar/weather kept showing cards.
            // Pangle (ByteDance) is what serves the "红果短剧" promo.
            "pangolin-dsp-toutiao.com",
            // Kuaishou ad exchange, serves the calendar's banner ads
            "open.e.kuaishou.com",
            "kwaipaimall.com",
            "marketing.ths123.com",
            "pglstatp-toutiao.com",
            "byteurl.cn",
            "gdt.qq.com",
            "cpro.baidu.com",
            "mobads.baidu.com",
            // MIUI content push behind the recommendation cards
            "newhome.miui.com",
            "recommend.miui.com",
            "rom.pt.miui.com",
    };

    /**
     * Functional hosts that the merged list wrongly blocks.
     * Matched as suffixes, so an entry covers all of its subdomains.
     */
    static final String[] ALLOW = {
            // app store API + download CDN — blocking these breaks installs
            "a.market.xiaomi.com",
            "app.market.xiaomi.com",
            "global.market.xiaomi.com",
            "verify.market.xiaomi.com",
            // weather data (the ad host wtradv.* stays blocked)
            "weatherapi.market.xiaomi.com",
            // browser resources
            "r.browser.miui.com",
            // mainstream sites that the merged list wrongly blocks wholesale
            "baidu.com",
            "taobao.com",
            "youtube.com",
            "google.com",
            // account / cloud / push — needed for normal use
            "account.xiaomi.com",
            "api.account.xiaomi.com",
            "micloud.xiaomi.net",
            "mipush.com",
            "domipush.com",
    };

    private static volatile HashSet<String> big;
    private static volatile boolean loading;

    private AdHosts() {
    }

    /** loads assets/adhosts.txt once; call off the main thread */
    static void load(Context c) {
        if (big != null || loading) {
            return;
        }
        loading = true;
        long t0 = System.currentTimeMillis();
        HashSet<String> set = new HashSet<>(220000);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("adhosts.txt"), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    set.add(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        big = set;
        loading = false;
        android.util.Log.i("TuoYuClean", "adhosts loaded: " + set.size()
                + " in " + (System.currentTimeMillis() - t0) + "ms");
    }

    static boolean isLoaded() {
        return big != null;
    }

    static int loadedCount() {
        HashSet<String> s = big;
        return s == null ? 0 : s.size();
    }

    static void unload() {
        big = null;
        loading = false;
    }

    static int count() {
        return CURATED.length;
    }

    /**
     * True when the host should resolve to 0.0.0.0.
     * Order matters: whitelist wins, then the curated list, then the big list.
     */
    static boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase();
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        // 1. never break functional hosts
        for (String allow : ALLOW) {
            if (h.equals(allow) || h.endsWith("." + allow)) {
                return false;
            }
        }
        // 2. curated, always on
        for (String blocked : CURATED) {
            if (h.equals(blocked) || h.endsWith("." + blocked)) {
                return true;
            }
        }
        // 3. the merged list, when enabled
        HashSet<String> s = big;
        if (s == null) {
            return false;
        }
        if (s.contains(h)) {
            return true;
        }
        // parent-domain walk, so a listed "ad.example.com" also catches
        // "x.y.ad.example.com"
        int dot = h.indexOf('.');
        while (dot > 0 && dot < h.length() - 1) {
            String parent = h.substring(dot + 1);
            if (s.contains(parent)) {
                return true;
            }
            dot = h.indexOf('.', dot + 1);
        }
        return false;
    }
}
