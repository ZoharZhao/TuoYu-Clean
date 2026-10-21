package com.tuoyu.clean;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Headless entry point for the launcher shortcuts.
 *
 * A shortcut intent has to name an activity; naming MainActivity would open the
 * whole UI just to flip a switch. This one performs the change and closes, so
 * long-pressing the icon leaves the user where they were.
 */
public class ShortcutActivity extends Activity {

    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        dismiss();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
        dismiss();
    }

    private void dismiss() {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 900);
    }

    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (MainActivity.ACTION_TOGGLE_TIMEOUT.equals(action)) {
            boolean next = !TuoYuClean.isOn(this, MainActivity.FEAT_TIMEOUT);
            TuoYuClean.set(this, MainActivity.FEAT_TIMEOUT, next);
            toast(next);
            republish();
            return;
        }
        if (MainActivity.ACTION_TOGGLE_DIM.equals(action)) {
            boolean next = !TuoYuClean.isOn(this, MainActivity.FEAT_DIM);
            TuoYuClean.set(this, MainActivity.FEAT_DIM, next);
            toast(next);
            republish();
            return;
        }
        if (MainActivity.ACTION_TOGGLE_AREA.equals(action)) {
            // the filter is a VPN now, so the shortcut starts or stops the service
            final boolean next = !AdBlockVpnService.wasRunning(this);
            if (next) {
                if (android.net.VpnService.prepare(this) != null) {
                    // consent is needed; send the user to the app instead of
                    // silently doing nothing
                    Intent open = new Intent(this, MainActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(open);
                    return;
                }
                Intent i = new Intent(this, AdBlockVpnService.class);
                i.setAction(AdBlockVpnService.ACTION_START);
                try {
                    startForegroundService(i);
                } catch (Throwable ignored) {
                }
            } else {
                Intent i = new Intent(this, AdBlockVpnService.class);
                i.setAction(AdBlockVpnService.ACTION_STOP);
                try {
                    startService(i);
                } catch (Throwable ignored) {
                }
            }
            toast(next);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    republish();
                }
            }, 1200);
        }
    }

    private void toast(boolean on) {
        android.widget.Toast.makeText(this,
                getString(on ? R.string.toast_applied
                        : R.string.sc_turned_off),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** rebuilds all three shortcuts from the current state */
    private void republish() {
        MainActivity.publishShortcuts(this);
    }
}
