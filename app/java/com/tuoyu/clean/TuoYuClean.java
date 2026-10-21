package com.tuoyu.clean;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Readers/writers for the two screen settings controlled from the UI:
 *
 *   timeout   screen_off_timeout   (Settings.System, public)
 *             Integer.MAX_VALUE when on, the user's own value when off
 *   dim       dim_screen           (private namespace)
 *             1 when on, 0 when off
 *
 * The private namespace has no public API. It is reached through
 * ContentResolver.insert(), which the platform allows for apps holding
 * WRITE_SECURE_SETTINGS (this works on HyperOS/MIUI even though the shell
 * command is refused there).
 *
 * State is always read back from the system (never cached in a field), so the
 * switches and the shortcut labels can not drift out of sync with reality.
 */
final class TuoYuClean {

    static final String K_STAY = "stay_on_while_plugged_in";
    static final String K_TIMEOUT = "screen_off_timeout";
    static final String K_DIM = "dim_screen";

    static final String ON_TIMEOUT = String.valueOf(Integer.MAX_VALUE);
    static final String DEFAULT_TIMEOUT = "60000";
    static final String ON_DIM = "1";
    static final String OFF_DIM = "0";

    private static final String PREFS = "tuoyuclean";
    private static final String P_TIMEOUT_SAVED = "timeout_saved";
    private static final String P_TIMEOUT = "timeout";
    private static final String P_PRIVATE_OK = "private_writable";
    private static final String P_PRIVATE_PROBED = "private_probed";
    private static final String P_PRIVATE_DENIED = "private_denied";

    private TuoYuClean() {
    }

    // ------------------------------------------------------------- state

    static final class State {
        String stay;
        String timeout;
        String dim;
        boolean privateWritable;
        String error;
        /** the user's own timeout, shown while our MAX_INT marker is inactive */
        String ownTimeout;

        boolean stayOn() {
            try {
                return stay != null && (Integer.parseInt(stay.trim()) & 7) != 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        boolean timeoutLong() {
            try {
                return timeout != null && !timeout.trim().isEmpty()
                        && Long.parseLong(timeout.trim()) >= Integer.MAX_VALUE;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        boolean dimOn() {
            return dim != null && "1".equals(dim.trim());
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------- low level

    static String readGlobal(ContentResolver cr, String key) {
        try {
            String v = Settings.Global.getString(cr, key);
            return v != null ? v : "0";
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String readSystem(ContentResolver cr, String key) {
        try {
            return Settings.System.getString(cr, key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** write to the private ("secure settings") namespace */
    static boolean privatePut(ContentResolver cr, String key, String value) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(Settings.NameValueTable.NAME, key);
            cv.put(Settings.NameValueTable.VALUE, value);
            cr.insert(Settings.System.CONTENT_URI, cv);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The raw timeout value.
     *
     * Settings.System.SCREEN_OFF_TIMEOUT is in MILLISECONDS. Reading it as
     * seconds turned the system default of 60000 into "1000 分钟".
     */
    private static int timeoutMs(Context c) {
        State st = read(c);
        if (st == null || st.timeout == null) {
            return -1;
        }
        try {
            return Integer.parseInt(st.timeout.trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    static boolean isForever(Context c) {
        int ms = timeoutMs(c);
        return ms == Integer.MAX_VALUE || ms >= 24 * 60 * 60 * 1000;
    }

    /** first line of the status card: "永久" or "5 分钟" */
    static String describeHead(Context c) {
        int ms = timeoutMs(c);
        if (ms < 0) {
            return "-";
        }
        if (isForever(c)) {
            return c.getString(R.string.power_forever_head);
        }
        if (ms >= 60000) {
            return c.getString(R.string.power_minutes, ms / 60000);
        }
        return c.getString(R.string.power_seconds, ms / 1000);
    }

    /** second line of the status card: what the value means */
    static String describeSub(Context c) {
        int ms = timeoutMs(c);
        if (ms < 0) {
            return "";
        }
        return c.getString(isForever(c) ? R.string.power_forever_sub
                : R.string.power_timed_sub);
    }

    /** kept for callers that still want one string */
    static String describeTimeout(Context c) {
        String head = describeHead(c);
        String sub = describeSub(c);
        return sub.isEmpty() ? head : head + " " + sub;
    }

    static State read(Context c) {
        State s = new State();
        if (!hasPermission(c)) {
            s.error = "no_permission";
            return s;
        }
        ContentResolver cr = c.getContentResolver();
        SharedPreferences sp = prefs(c);
        s.stay = readGlobal(cr, K_STAY);
        s.timeout = readSystem(cr, K_TIMEOUT);
        s.dim = readSystem(cr, K_DIM);
        s.privateWritable = sp.getBoolean(P_PRIVATE_OK, false);
        String own = sp.getBoolean(P_TIMEOUT_SAVED, false) ? sp.getString(P_TIMEOUT, null) : null;
        if (own == null) {
            // nothing stored yet: the current value is the user's, unless it is
            // our own MAX_INT marker
            own = s.timeoutLong() ? DEFAULT_TIMEOUT : s.timeout;
        }
        s.ownTimeout = own;
        return s;
    }

    /** remembers the user's own timeout once */
    static void rememberOwnValues(Context c) {
        SharedPreferences sp = prefs(c);
        if (sp.getBoolean(P_TIMEOUT_SAVED, false)) {
            return;
        }
        String raw = readSystem(c.getContentResolver(), K_TIMEOUT);
        boolean ours = false;
        try {
            ours = raw != null && Long.parseLong(raw.trim()) >= Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
        }
        if (ours) {
            raw = DEFAULT_TIMEOUT;
        }
        sp.edit()
                .putString(P_TIMEOUT, raw != null && !raw.trim().isEmpty() ? raw.trim() : DEFAULT_TIMEOUT)
                .putBoolean(P_TIMEOUT_SAVED, true)
                .apply();
    }

    // ----------------------------------------------------------- features

    static boolean setTimeout(Context c, boolean on) {
        if (!hasPermission(c)) {
            return false;
        }
        rememberOwnValues(c);
        String value = on ? ON_TIMEOUT : prefs(c).getString(P_TIMEOUT, DEFAULT_TIMEOUT);
        try {
            return Settings.System.putString(c.getContentResolver(), K_TIMEOUT, value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** writes dim_screen; a refused write is remembered so the UI can say so */
    static boolean setDim(Context c, boolean on) {
        if (!hasPermission(c)) {
            return false;
        }
        boolean ok = privatePut(c.getContentResolver(), K_DIM, on ? ON_DIM : OFF_DIM);
        prefs(c).edit().putBoolean(P_PRIVATE_DENIED, !ok).apply();
        return ok;
    }

    /** true if this process can write the private settings namespace */
    static boolean canWritePrivate(Context c) {
        if (!hasPermission(c)) {
            return false;
        }
        SharedPreferences sp = prefs(c);
        if (sp.getBoolean(P_PRIVATE_PROBED, false)) {
            return sp.getBoolean(P_PRIVATE_OK, false);
        }
        // probe with the current value so nothing changes but the status is known
        String current = readSystem(c.getContentResolver(), K_DIM);
        boolean ok = privatePut(c.getContentResolver(), K_DIM,
                current != null && !current.trim().isEmpty() ? current.trim() : OFF_DIM);
        sp.edit().putBoolean(P_PRIVATE_OK, ok).putBoolean(P_PRIVATE_PROBED, true).apply();
        return ok;
    }

    static boolean privateDenied(Context c) {
        return prefs(c).getBoolean(P_PRIVATE_DENIED, false);
    }

    /** whether a feature is currently on (also drives the shortcut labels) */
    static boolean isOn(Context c, String feature) {
        State s = read(c);
        if (s.error != null) {
            return false;
        }
        if ("timeout".equals(feature)) {
            return s.timeoutLong();
        }
        if ("dim".equals(feature)) {
            return s.dimOn();
        }
        return false;
    }

    /** sets a feature to `on` (idempotent; used by shortcuts and switches) */
    static boolean set(Context c, String feature, boolean on) {
        if (!hasPermission(c)) {
            return false;
        }
        if ("timeout".equals(feature)) {
            return setTimeout(c, on);
        }
        if ("dim".equals(feature)) {
            return setDim(c, on);
        }
        return false;
    }

    static String permissionCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
    }
}
