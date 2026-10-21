package com.tuoyu.clean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;

/**
 * Local DNS filter.
 *
 * A tun device is created that only routes the virtual DNS address, so all real
 * traffic bypasses the VPN entirely — this is a DNS filter, not a tunnel. Every
 * query that reaches us is answered from {@link AdHosts} or forwarded upstream.
 *
 * Works without root and without reaching any block-list service, which is why
 * it is used instead of private DNS: overseas DoT endpoints are unusable here
 * (their TLS handshake is reset) and domestic ones do not filter ads.
 */
public class AdBlockVpnService extends VpnService {

    static final String ACTION_START = "com.tuoyu.clean.VPN_START";
    static final String ACTION_STOP = "com.tuoyu.clean.VPN_STOP";

    private static final String CHANNEL = "adblock_vpn";
    private static final int NOTI_ID = 23294;
    /** address of the tun device itself */
    private static final String TUN_ADDRESS = "10.111.222.1";
    /** the fake resolver we advertise to the system */
    private static final String DNS_ADDRESS = "10.111.222.2";

    private static volatile boolean active;

    private ParcelFileDescriptor tun;
    private Thread worker;
    private DatagramSocket upstream;

    static boolean isActive() {
        return active;
    }

    private void setFlag(boolean on) {
        getSharedPreferences("adblock", MODE_PRIVATE).edit()
                .putBoolean("vpn_running", on).apply();
    }

    /**
     * True when the filter is really running.
     *
     * The stored flag alone is not enough: force-stopping the app (or the system
     * reclaiming it) tears the tun down without running onDestroy, leaving the
     * flag set. /proc/net/dev is world-readable, so the interface can be checked
     * directly.
     */
    static boolean wasRunning(android.content.Context c) {
        if (active) {
            return true;
        }
        boolean flagged = c.getSharedPreferences("adblock", MODE_PRIVATE)
                .getBoolean("vpn_running", false);
        if (!flagged) {
            return false;
        }
        boolean up = tunExists();
        if (!up) {
            c.getSharedPreferences("adblock", MODE_PRIVATE).edit()
                    .putBoolean("vpn_running", false).apply();
        }
        return up;
    }

    private static boolean tunExists() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/dev"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("tun0:")) {
                    return true;
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
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    /** Must be called from an Activity after the system VPN consent dialog. */
    private void startVpn() {
        if (active) {
            return;
        }
        startForeground(NOTI_ID, buildNotification());
        Builder b = new Builder();
        b.setSession(getString(R.string.app_name));
        b.addAddress(TUN_ADDRESS, 32);
        b.addDnsServer(DNS_ADDRESS);
        // route ONLY the fake resolver: real traffic never enters the tun
        b.addRoute(DNS_ADDRESS, 32);
        b.setBlocking(true);
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Throwable ignored) {
        }
        // Create the upstream socket BEFORE the tun exists: once establish()
        // returns, an unprotect()-ed bind fails with EPERM because the route
        // table now sends everything through the tun.
        try {
            upstream = new DatagramSocket();
            } catch (Throwable e) {
            android.util.Log.e("TuoYuClean", "vpn: socket failed " + e);
            stopVpn();
            return;
        }
        try {
            tun = b.establish();
        } catch (Throwable e) {
            android.util.Log.e("TuoYuClean", "vpn: establish threw " + e);
            stopVpn();
            return;
        }
        if (tun == null) {
            android.util.Log.e("TuoYuClean", "vpn: establish returned null");
            stopVpn();
            return;
        }
        try {
            protect(upstream);
        } catch (Throwable e) {
            android.util.Log.e("TuoYuClean", "vpn: protect failed " + e);
            stopVpn();
            return;
        }
        active = true;
        setFlag(true);
        // load the merged list only when the experimental switch is on
        if (getSharedPreferences("adblock", MODE_PRIVATE)
                .getBoolean("big_list", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AdHosts.load(AdBlockVpnService.this);
                }
            }, "load-adhosts").start();
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "dns-filter");
        worker.start();
    }

    /** reads packets from the tun and writes the answers back */
    private void pump() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(tun.getFileDescriptor());
            out = new FileOutputStream(tun.getFileDescriptor());
            byte[] buf = new byte[32767];
            while (active) {
                int len = in.read(buf);
                if (len <= 0) {
                    break;
                }
                byte[] reply = DnsPacket.handle(buf, len, upstream);
                if (reply != null) {
                    out.write(reply);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private void stopVpn() {
        active = false;
        setFlag(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (upstream != null) {
            try {
                upstream.close();
            } catch (Throwable ignored) {
            }
            upstream = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Throwable ignored) {
            }
            tun = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // another VPN took over, or the user revoked consent
        stopVpn();
        super.onRevoke();
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.vpn_noti_title))
                .setContentText(getString(R.string.vpn_noti_text, AdHosts.count()))
                .setSmallIcon(R.drawable.ic_tab_ad)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
