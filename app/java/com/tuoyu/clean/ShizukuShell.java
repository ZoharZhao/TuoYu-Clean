package com.tuoyu.clean;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Thin wrapper around the Shizuku API:
 *   - permission check / request (Shizuku 11+ style, fallback to the old API)
 *   - "is Shizuku usable right now" probing
 *   - running shell commands through a Shizuku-created process
 *
 * Every command path re-checks the permission first, because the binder can
 * die (Shizuku stopped / phone rebooted) at any moment.
 */
final class ShizukuShell {

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final int code;
        final String out;
        final String err;
        final boolean ok;
        final String error;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
            this.ok = code == 0;
            this.error = ok ? null : (this.err.trim().isEmpty() ? this.out.trim() : this.err.trim());
        }

        Result(String error) {
            this.code = -1;
            this.out = "";
            this.err = "";
            this.ok = false;
            this.error = error;
        }

        @Override
        public String toString() {
            return ok ? out.trim() : ("[" + code + "] " + (error == null ? "" : error));
        }
    }

    static final int PERMISSION_GRANTED = PackageManager.PERMISSION_GRANTED;
    private static final int REQUEST_CODE = 0x5348; // "SH"

    private ShizukuShell() {
    }

    // ------------------------------------------------------------ availability

    /** Shizuku app installed (regardless of running state) */
    private static final long EXEC_TIMEOUT_MS = 4000L;

    static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** binder alive = Shizuku service is running and reachable */
    static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** permission already granted to this app */
    static boolean hasPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** everything needed to run commands is in place */
    static boolean isReady(Context c) {
        return isInstalled(c) && isBinderAlive() && hasPermission();
    }

    /** human readable reason why it is not ready (null when ready) */
    static String state(Context c) {
        if (!isInstalled(c)) {
            return "not_installed";
        }
        if (hasPermission()) {
            return null;
        }
        // no binder and no permission: either the service is stopped or this app
        // was never authorized (the binder is only broadcast to trusted apps)
        return hasBinder() ? "no_permission" : "waiting";
    }

    // ------------------------------------------------------------- permission

    /**
     * Opens Shizuku's own permission screen for this package.
     *
     * The Shizuku binder is delivered by a broadcast that only authorized apps
     * may receive, so a never-authorized app cannot ask for the permission over
     * the binder — the request has to start from the Shizuku app. Its
     * RequestPermissionActivity takes the target package as data.
     */
    static void openPermissionScreen(Context c) {
        // RequestPermissionActivity is registered for this action and shows the
        // grant dialog directly for the package in the data URI. Naming the
        // class does not work: the activity is not exported under a stable name.
        try {
            android.content.Intent i = new android.content.Intent(
                    "moe.shizuku.privileged.api.intent.action.REQUEST_AUTHORIZATION");
            i.setPackage("moe.shizuku.privileged.api");
            i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        openManager(c);
    }

    /**
     * Probes the Shizuku provider once, so a binder that is already waiting for
     * this process gets delivered (the provider answers "getBinder").
     */
    static void warmUp(Context context) {
        try {
            context.getContentResolver().call(
                    android.net.Uri.parse("content://" + context.getPackageName() + ".shizuku"),
                    "getBinder", null, null);
        } catch (Throwable ignored) {
        }
    }

    /** asks Shizuku for the permission; result arrives in {@code listener} */
    static void requestPermission(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
            // Shizuku not running: the caller falls back to opening the app
        }
    }

    /**
     * True when the Shizuku binder has been delivered to this process.
     * The permission dialog can only be raised over that binder.
     */
    static boolean canRequest() {
        try {
            return Shizuku.getBinder() != null && Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** true when our provider has been queried at least once (binder delivered) */
    static boolean hasBinder() {
        try {
            return Shizuku.getBinder() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    static void removePermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Throwable ignored) {
        }
    }

    /** opens the Shizuku app so the user can start the service */
    static void openManager(Context c) {
        try {
            android.content.Intent i = c.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- commands

    /** IShizukuService proxy bound to the live Shizuku binder, or null */
    private static moe.shizuku.server.IShizukuService service() {
        try {
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null || !binder.pingBinder()) {
                return null;
            }
            return moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
        } catch (Throwable e) {
            return null;
        }
    }

    /** a Process-like view over Shizuku's IRemoteProcess */
    private static final class RemoteProcess {
        final moe.shizuku.server.IRemoteProcess rp;
        final java.io.InputStream in;
        final java.io.InputStream err;
        final OutputStream out;

        RemoteProcess(moe.shizuku.server.IRemoteProcess rp) throws java.io.IOException {
            this.rp = rp;
            try {
                this.in = new java.io.FileInputStream(rp.getInputStream().getFileDescriptor());
                this.err = new java.io.FileInputStream(rp.getErrorStream().getFileDescriptor());
                this.out = new java.io.FileOutputStream(rp.getOutputStream().getFileDescriptor());
            } catch (android.os.RemoteException e) {
                throw new java.io.IOException(e);
            }
        }

        void destroy() {
            try {
                rp.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Result runRemote(String[] argv) {
        if (!hasPermission()) {
            return new Result("no_permission");
        }
        moe.shizuku.server.IShizukuService svc = service();
        if (svc == null) {
            return new Result("service unavailable");
        }
        RemoteProcess proc = null;
        try {
            proc = new RemoteProcess(svc.newProcess(argv, null, null));
            try {
                proc.out.close();
            } catch (Throwable ignored) {
            }
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            final java.io.InputStream in = proc.in;
            final java.io.InputStream er = proc.err;
            final CountDownLatch done = new CountDownLatch(2);
            Thread t1 = new Thread(new Runnable() {
                @Override
                public void run() {
                    readAll(in, out, done);
                }
            }, "shizuku-out");
            Thread t2 = new Thread(new Runnable() {
                @Override
                public void run() {
                    readAll(er, err, done);
                }
            }, "shizuku-err");
            t1.start();
            t2.start();
            // waitFor() has no timeout of its own and blocks forever when the
            // remote process is suspended, which used to hang the watcher
            // thread. Run it on a helper thread with a hard deadline instead.
            final int[] code = {-1};
            final RemoteProcess target = proc;
            Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        code[0] = target.rp.waitFor();
                    } catch (Throwable ignored) {
                    }
                }
            }, "shizuku-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(EXEC_TIMEOUT_MS);
            boolean finished = !waiter.isAlive();
            done.await(2, TimeUnit.SECONDS);
            if (!finished) {
                return new Result(EXEC_TIMEOUT_MS + ": timed out");
            }
            return new Result(code[0], out.toString(), err.toString());
        } catch (Throwable e) {
            return new Result(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
    }

    /**
     * Runs one shell command synchronously (never call on the main thread).
     * Returns a Result instead of throwing so callers can show the error text.
     */
    static Result exec(String command) {
        return runRemote(new String[]{"sh", "-c", command});
    }

    /** same as {@link #exec} but with the command already split into argv */
    static Result execArgs(String... argv) {
        if (!hasPermission()) {
            return new Result("no_permission");
        }
        return runRemote(argv);
    }

    private static String join(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static void readAll(java.io.InputStream in, StringBuilder sb, CountDownLatch done) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
            done.countDown();
        }
    }

    /** runs several commands in one shell, returns the merged output */
    static Result execAll(List<String> commands) {
        StringBuilder sb = new StringBuilder();
        for (String c : commands) {
            sb.append(c).append('\n');
        }
        return exec(sb.toString());
    }

    static List<String> list(String... items) {
        List<String> out = new ArrayList<>();
        for (String s : items) {
            out.add(s);
        }
        return out;
    }
}
