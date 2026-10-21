package com.tuoyu.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * Single screen with two independent switches:
 *   screen_off_timeout  (public)   -> 屏幕常亮 / Screen stay awake
 *   dim_screen          (private)  -> 自动变暗 / Dim screen
 *
 * Appearance selector: follow system (default) / day / night, persisted.
 * Desktop shortcuts are rebuilt on every state change, so their label always
 * reads like the action it performs ("开启屏幕常亮" vs "关闭屏幕常亮").
 * UI language is Chinese (simplified/traditional) or English, following the
 * system language.
 */
public class MainActivity extends Activity {

    static final String ACTION_TOGGLE_TIMEOUT = "com.tuoyu.clean.TOGGLE_TIMEOUT";
    static final String ACTION_TOGGLE_DIM = "com.tuoyu.clean.TOGGLE_DIM";
    /** kept so old shortcuts do not crash: act as "open the app" */
    static final String ACTION_TOGGLE_STAY_ON = "com.tuoyu.clean.TOGGLE_STAY_ON";

    static final String FEAT_TIMEOUT = "timeout";
    static final String FEAT_DIM = "dim";

    private static final String ID_TIMEOUT_ON = "timeout_on";
    private static final String ID_TIMEOUT_OFF = "timeout_off";
    private static final String ID_DIM_ON = "dim_on";
    private static final String ID_DIM_OFF = "dim_off";

    private static final int THEME_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;

    private static final String PREFS = "ui";
    private static final String P_THEME = "theme";
    private static final String P_ASKED = "asked_permission";

    private static final int ACCENT = 0xFFF0A020;
    private static final String TAG = "TuoYuClean";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean suppressSpinner;

    // palette, recomputed by applyTheme()
    private boolean dark;
    private int cBg;
    private int cCard;
    private int cText;
    private int cError;
    private int cDim;
    private int cSubtle;
    private int cStroke;

    private ScrollView scroll;
    private TextView titleView;
    private TextView subtitleView;
    private Spinner themeSpinner;
    private TextView themeLabel;
    private boolean envChecked;
    private boolean granting;
    private Switch adBigSwitch;
    private static final int REQ_VPN = 3001;
    private TextView appearanceLabel;
    private TextView powerValue;
    private TextView timeoutValue;
    private TextView timeoutTip;
    private TextView dimTip;
    private TextView noteView;
    private TextView footer1;
    private TextView footer2;
    private TextView timeoutState;
    private TextView dimState;
    private Switch swTimeout;
    private Switch swDim;
    private Button grantButton;
    private Button refreshButton;

    // ---- bottom tab bar ----
    /** long-press shortcut actions */
    static final String ACTION_TOGGLE_AREA = "com.tuoyu.clean.TOGGLE_SAFE_AREA";
    static final String ACTION_REFRESH_SHORTCUTS = "com.tuoyu.clean.REFRESH_SHORTCUTS";
    private static final String ID_AREA = "id_area";

    private static final int TAB_HOME = 0;
    private static final int TAB_AREA = 1;
    private LinearLayout tabBarWrapper;
    private LinearLayout tabBarHolder;
    private android.widget.FrameLayout pageHolder;
    private LinearLayout tabBar;
    private LinearLayout tabHome;
    private LinearLayout tabApps;
    private int currentTab = TAB_HOME;
    private android.view.GestureDetector swipeDetector;
    private String consumedAction;

    // ---- bottom safe area (Shizuku) ----
    private TextView saStatusView;
    private TextView saShizukuView;
    private Button saPermissionButton;
    private Button saOpenShizukuButton;
    private Button saRecheckButton;
    private Switch saMaster;
    private Switch saLineSwitch;
    private TextView saHeightLabel;
    private TextView saHeightValue;
    private LinearLayout adListBody;
    private Button saVerifyButton;
    private boolean adRebootAsked;
    private Button gkdInstallButton;
    private Button gkdCopyButton;
    private TextView saHeightPick;
    private LinearLayout saConfigGroup;
    private final java.util.Map<String, android.widget.CheckBox> saBoxes = new java.util.HashMap<>();
    private final Shizuku.OnRequestPermissionResultListener saPermissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    boolean granted = grantResult
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    toast(getString(granted
                            ? R.string.sa_permission_granted : R.string.sa_permission_denied));
                    if (granted) {
                        // now that Shizuku answers, it can grant the secure-settings
                        // permission for us; no adb command needed
                        maybeAskPermission();
                    }
                    renderSafeArea();
                }
            };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        return tv;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            g.setStroke(dp(1), stroke);
        }
        return g;
    }

    // --------------------------------------------------------------- theme

    private SharedPreferences uiPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int themePref() {
        return uiPrefs().getInt(P_THEME, THEME_SYSTEM);
    }

    private boolean darkNow() {
        int pref = themePref();
        if (pref == THEME_LIGHT) {
            return false;
        }
        if (pref == THEME_DARK) {
            return true;
        }
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** sets palette + system bar colours; called before super.onCreate too */
    private void applyTheme() {
        dark = darkNow();
        Window win = getWindow();
        win.setStatusBarColor(0x00000000);
        win.setNavigationBarColor(dark ? 0xFF101114 : 0xFFF2F3F5);
        win.setNavigationBarDividerColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = win.getDecorView().getSystemUiVisibility();
            flags = dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (Build.VERSION.SDK_INT >= 26) {
                flags = dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                        : (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            win.getDecorView().setSystemUiVisibility(flags);
        }
        if (dark) {
            cBg = 0xFF101114;
            cCard = 0xFF1A1C20;
            cText = 0xFFF3F4F6;
            cDim = 0xFF8A8F98;
            cSubtle = 0xFF5C626C;
            cStroke = 0x1FFFFFFF;
        cError = 0xFFFF6B5E;
        } else {
            cBg = 0xFFF2F3F5;
            cCard = 0xFFFFFFFF;
            cText = 0xFF17181C;
            cDim = 0xFF6B7280;
        cError = 0xFFD9534F;
            cSubtle = 0xFF8A9099;
            cStroke = 0x14000000;
        }
    }

    private String[] themeOptions() {
        return new String[]{getString(R.string.theme_system), getString(R.string.theme_light),
                getString(R.string.theme_dark)};
    }

    private int themeIndex() {
        int pref = themePref();
        return pref == THEME_LIGHT ? 1 : pref == THEME_DARK ? 2 : 0;
    }

    // ---------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("tab", TAB_HOME);
        }

        applyTheme();
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "buildUi failed", t);
            throw t;
        }
        setupSafeAreaSpinners();
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(saPermissionListener);
        handleIntent(getIntent());
    }

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    renderSafeArea();
                }
            };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    renderSafeArea();
                }
            };

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        ShizukuShell.removePermissionListener(saPermissionListener);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // system language or day/night changed: rebuild with the right strings
        recreate();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumedAction = null;
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        // the activity is recreated whenever the navigation bar height changes,
        // so the tab has to survive it
        out.putInt("tab", currentTab);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpn();
            } else {
                toast(getString(R.string.vpn_denied));
            }
            renderSafeArea();
        }
    }

    protected void onResume() {

        super.onResume();
        // ask the provider for a binder that may already be waiting for us
        ShizukuShell.warmUp(this);
        // re-read every setting each time the screen is shown
        renderSafe();
        renderSafeArea();
        renderGkd();
        publishShortcuts(this);
        maybeAskPermission();
        maybeAskPermission();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkEnvironment();
            }
        }, 700);
        // deliberately no auto-start here: changing a system setting makes the
        // activity resume, and restarting the service from a stale preference
        // turned the safe area on without the user asking for it
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(cBg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        final LinearLayout root = content;
        // the child must be attached to the ScrollView, otherwise the whole
        // layout is never laid out (blank screen)
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // safe-area inset for the status bar (edge-to-edge display)
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // only the status bar is consumed here: the bottom inset belongs
                // to the tab bar, and applying it in both places pushed the tab
                // bar up by an extra navigation-bar height
                int top = systemInset(insets, WindowInsets.Type.statusBars());
                v.setPadding(v.getPaddingLeft(), dp(16) + top, v.getPaddingRight(), dp(24));
                return insets;
            }
        });

        // ---- header ----
        titleView = text(getString(R.string.title), 25, cText, true);
        root.addView(titleView);
        subtitleView = text(getString(R.string.subtitle), 12.5f, cDim, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        root.addView(subtitleView, subLp);

        // ---- Shizuku first: it is what makes the toggles below writable ----
        buildShizukuCard(root);

        // ---- top bar: a standalone refresh button, then a separate theme card ----
        LinearLayout appearance = new LinearLayout(this);
        appearance.setOrientation(LinearLayout.HORIZONTAL);
        appearance.setGravity(Gravity.CENTER_VERTICAL);

        Button refresh = styledButton(getString(R.string.refresh), true);
        refreshButton = refresh;
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderSafe();
                renderSafeArea();
                toast(getString(R.string.toast_refreshed));
            }
        });
        refresh.setMinWidth(dp(96));
        refresh.setMinimumWidth(dp(96));
        appearance.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // spacer pushes the theme card to the right edge
        View flexSpace = new View(this);
        appearance.addView(flexSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        // Its own rounded card, and a dialog instead of a Spinner: the platform
        // Spinner popup ignores the app theme and paints white-on-white in dark mode.
        LinearLayout themeCard = new LinearLayout(this);
        themeCard.setOrientation(LinearLayout.HORIZONTAL);
        themeCard.setGravity(Gravity.CENTER_VERTICAL);
        themeCard.setBackground(roundRect(cCard, 14, cStroke));
        themeCard.setPadding(dp(16), dp(12), dp(14), dp(12));
        themeCard.setMinimumHeight(dp(46));

        themeLabel = text(currentThemeName(), 13, cText, false);
        themeCard.addView(themeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arrowLp.leftMargin = dp(10);
        themeCard.addView(text("\u25be", 11, cDim, false), arrowLp);

        themeCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemePicker();
            }
        });
        appearance.addView(themeCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = dp(16);
        root.addView(appearance, apLp);

        // ---- summary ----
        TextView summary = text(getString(R.string.summary_title), 12, cSubtle, true);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(24);
        sumLp.bottomMargin = dp(10);
        sumLp.leftMargin = dp(4);
        root.addView(summary, sumLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(roundRect(cCard, 18, cStroke));
        powerValue = text("-", 17, cText, true);
        card.addView(powerValue);
        timeoutValue = text("", 12.5f, cDim, false);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(6);
        card.addView(timeoutValue, tvLp);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- the independent switches ----
        TextView sec = text(getString(R.string.section_switches), 12, cSubtle, true);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(22);
        secLp.bottomMargin = dp(10);
        secLp.leftMargin = dp(4);
        root.addView(sec, secLp);

        SwitchRow r1 = addSwitch(root, FEAT_TIMEOUT, getString(R.string.sw_timeout),
                getString(R.string.sw_timeout_tip));
        swTimeout = r1.sw;
        timeoutTip = r1.tip;
        timeoutState = r1.state;
        SwitchRow r2 = addSwitch(root, FEAT_DIM, getString(R.string.sw_dim),
                getString(R.string.sw_dim_tip));
        swDim = r2.sw;
        dimTip = r2.tip;
        dimState = r2.state;

        grantButton = new Button(this);
        grantButton.setText(getString(R.string.grant));
        grantButton.setAllCaps(false);
        grantButton.setTextColor(0xFFFFFFFF);
        grantButton.setTextSize(13.5f);
        grantButton.setBackground(roundRect(ACCENT, 12, 0));
        grantButton.setVisibility(View.GONE);
        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPermissionDialog();
            }
        });
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = dp(14);
        root.addView(grantButton, gLp);

        noteView = text("", 11, cSubtle, false);
        noteView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(16);
        nLp.leftMargin = dp(4);
        nLp.rightMargin = dp(4);
        root.addView(noteView, nLp);

        scroll.setBackgroundColor(cBg);

        // ---- footer: last block of the scrollable content (not pinned) ----
        root.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(cBg);
        // two real pages, swapped by the tab bar (no scrolling between them)
        pageHolder = new android.widget.FrameLayout(this);
        pageHolder.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageHolder.addView(buildSafeAreaPage(), new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(pageHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabBarHolder = buildTabBar();
        page.addView(tabBarHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        attachSwipe();
        setContentView(page);
        showPage(currentTab);
        // the tab bar is built after the safe-area section, so the first
        // renderSafeArea() could not see it yet
        ui.post(new Runnable() {
            @Override
            public void run() {
                updateTabBar();
            }
        });
    }


    /** author / e-mail / WeChat block, shared by both tabs */
    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(cBg);
        footer.setPadding(dp(20), dp(20), dp(20), dp(8));

        View divider = new View(this);
        divider.setBackgroundColor(cStroke);
        footer.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        footer1 = text(getString(R.string.author), 12, cSubtle, false);
        footer1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams f1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f1Lp.topMargin = dp(14);
        footer.addView(footer1, f1Lp);

        footer2 = text(getString(R.string.contact), 12, cSubtle, false);
        footer2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams f2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f2Lp.topMargin = dp(4);
        footer.addView(footer2, f2Lp);

        TextView wechat = text(getString(R.string.wechat), 12, cSubtle, false);
        wechat.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams f3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f3Lp.topMargin = dp(4);
        f3Lp.bottomMargin = dp(8);
        footer.addView(wechat, f3Lp);

        return footer;
    }

    /** second tab: the Shizuku state, the switch and the live height */
    private View buildSafeAreaPage() {
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(cBg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        col.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = systemInset(insets, WindowInsets.Type.statusBars());
                v.setPadding(v.getPaddingLeft(), dp(16) + top, v.getPaddingRight(), dp(24));
                return insets;
            }
        });

        col.addView(text(getString(R.string.ad_title), 25, cText, true));
        buildSafeAreaSection(col);
        col.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageScroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return pageScroll;
    }

    /** the safe-area controls */
    /**
     * The Shizuku card.
     *
     * It lives on the screen-control page, not the ad page: Shizuku is what
     * grants WRITE_SECURE_SETTINGS, and without that permission the toggles on
     * this page cannot write anything. The ad page only needs it as a second
     * step.
     */
    private void buildShizukuCard(LinearLayout parent) {
        saConfigGroup = new LinearLayout(this);
        saConfigGroup.setOrientation(LinearLayout.VERTICAL);
        saConfigGroup.setBackground(roundRect(cCard, 16, cStroke));
        saConfigGroup.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = dp(14);

        saConfigGroup.addView(text("Shizuku", 14, cText, true));
        saShizukuView = text(getString(R.string.sa_state_unknown), 11.5f, cDim, false);
        saShizukuView.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        saConfigGroup.addView(saShizukuView, sLp);

        saRecheckButton = styledButton(getString(R.string.sa_recheck), true);
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rcLp.topMargin = dp(10);
        saConfigGroup.addView(saRecheckButton, rcLp);

        TextView shizukuNote = text(getString(R.string.sa_shizuku_note), 11, cSubtle, false);
        shizukuNote.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams snLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        snLp.topMargin = dp(10);
        saConfigGroup.addView(shizukuNote, snLp);
        parent.addView(saConfigGroup, gLp);
    }

    /** the ad-block controls */
    private void buildSafeAreaSection(LinearLayout parent) {
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(18);
        parent.addView(text(getString(R.string.ad_section), 11.5f, cSubtle, false), secLp);

        // ---- master switch ----
        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setBackground(roundRect(cCard, 16, cStroke));
        masterRow.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout masterText = new LinearLayout(this);
        masterText.setOrientation(LinearLayout.VERTICAL);
        masterText.addView(text(getString(R.string.ad_master), 14, cText, false));
        TextView mSub = text(getString(R.string.ad_master_sub), 11, cDim, false);
        LinearLayout.LayoutParams mSubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mSubLp.topMargin = dp(2);
        masterText.addView(mSub, mSubLp);

        saStatusView = text(getString(R.string.ad_status_off), 11, cDim, false);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(2);
        masterText.addView(saStatusView, stLp);

        masterRow.addView(masterText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        saMaster = new Switch(this);
        saMaster.setShowText(false);
        saMaster.setClickable(false);
        saMaster.setFocusable(false);
        masterRow.addView(saMaster);

        masterRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSafeAreaEnabled(!saMaster.isChecked());
            }
        });
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrLp.topMargin = dp(10);
        parent.addView(masterRow, mrLp);

        // ---- honest limit note ----
        TextView limitNote = text(getString(R.string.ad_limit_note), 11, cSubtle, false);
        limitNote.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lnLp.topMargin = dp(8);
        lnLp.leftMargin = dp(2);
        lnLp.rightMargin = dp(2);
        parent.addView(limitNote, lnLp);

        // ---- full list (experimental) ----
        LinearLayout bigRow = new LinearLayout(this);
        bigRow.setOrientation(LinearLayout.HORIZONTAL);
        bigRow.setGravity(Gravity.CENTER_VERTICAL);
        bigRow.setBackground(roundRect(cCard, 14, cStroke));
        bigRow.setPadding(dp(16), dp(12), dp(14), dp(12));

        LinearLayout bigText = new LinearLayout(this);
        bigText.setOrientation(LinearLayout.VERTICAL);
        bigText.addView(text(getString(R.string.ad_big_title), 13.5f, cText, false));
        TextView bigSub = text(getString(R.string.ad_big_sub), 11, cDim, false);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bsLp.topMargin = dp(2);
        bigText.addView(bigSub, bsLp);
        bigRow.addView(bigText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        adBigSwitch = new Switch(this);
        adBigSwitch.setShowText(false);
        adBigSwitch.setClickable(false);
        adBigSwitch.setFocusable(false);
        bigRow.addView(adBigSwitch);

        bigRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBigList(!adBigSwitch.isChecked());
            }
        });
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bigLp.topMargin = dp(10);
        parent.addView(bigRow, bigLp);

        // ---- DNS info row ----
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setBackground(roundRect(cCard, 14, cStroke));
        infoRow.setPadding(dp(14), 0, dp(14), 0);
        infoRow.setMinimumHeight(dp(44));
        saHeightLabel = text(getString(R.string.ad_dns_label), 12, cText, false);
        saHeightLabel.setSingleLine(true);
        infoRow.addView(saHeightLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        saHeightValue = text("-", 12.5f, ACCENT, true);
        infoRow.addView(saHeightValue);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        infoLp.topMargin = dp(10);
        parent.addView(infoRow, infoLp);

        // ---- what got blocked ----
        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setBackground(roundRect(cCard, 16, cStroke));
        listCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(10);
        listCard.addView(text(getString(R.string.ad_blocked_title), 12, cSubtle, false));
        adListBody = new LinearLayout(this);
        adListBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(6);
        listCard.addView(adListBody, bodyLp);
        parent.addView(listCard, listLp);

        // ---- verify button ----
        saVerifyButton = styledButton(getString(R.string.ad_verify), true);
        saVerifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runVerify();
            }
        });
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vbLp.topMargin = dp(10);
        parent.addView(saVerifyButton, vbLp);

        // ---- GKD ----
        LinearLayout.LayoutParams gkdSecLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gkdSecLp.topMargin = dp(18);
        parent.addView(text(getString(R.string.gkd_section), 11.5f, cSubtle, false), gkdSecLp);

        LinearLayout gkdCard = new LinearLayout(this);
        gkdCard.setOrientation(LinearLayout.VERTICAL);
        gkdCard.setBackground(roundRect(cCard, 16, cStroke));
        gkdCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams gkdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gkdLp.topMargin = dp(8);

        gkdCard.addView(text(getString(R.string.gkd_title), 14, cText, true));
        TextView gkdDesc = text(getString(R.string.gkd_desc,
                GkdInstaller.bundledVersion()), 11.5f, cDim, false);
        gkdDesc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams gdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gdLp.topMargin = dp(4);
        gkdCard.addView(gkdDesc, gdLp);

        gkdInstallButton = styledButton(getString(R.string.gkd_install), true);
        gkdInstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installGkd();
            }
        });
        LinearLayout.LayoutParams giLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        giLp.topMargin = dp(12);
        gkdCard.addView(gkdInstallButton, giLp);

        gkdCopyButton = styledButton(getString(R.string.gkd_copy_sub), false);
        gkdCopyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copySubscription();
            }
        });
        LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gcLp.topMargin = dp(8);
        gkdCard.addView(gkdCopyButton, gcLp);

        TextView gkdNote = text(getString(R.string.gkd_note), 11, cSubtle, false);
        gkdNote.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams gnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gnLp.topMargin = dp(10);
        gkdCard.addView(gkdNote, gnLp);

        TextView gkdWarn = text(getString(R.string.gkd_warn), 11, cSubtle, false);
        gkdWarn.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams gwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gwLp.topMargin = dp(6);
        gkdCard.addView(gkdWarn, gwLp);
        parent.addView(gkdCard, gkdLp);

        // ---- note ----
        TextView note = text(getString(R.string.ad_note), 11, cSubtle, false);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(12);
        noteLp.leftMargin = dp(2);
        noteLp.rightMargin = dp(2);
        parent.addView(note, noteLp);
    }

    /** the two-page tab bar */
    private LinearLayout buildTabBar() {
        tabBarWrapper = new LinearLayout(this);
        tabBarWrapper.setOrientation(LinearLayout.VERTICAL);
        tabBarWrapper.setBackgroundColor(cBg);

        View line = new View(this);
        line.setBackgroundColor(cStroke);
        tabBarWrapper.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(cCard);
        tabBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        // the window is edge-to-edge, so the tab bar is the one view that must
        // clear the navigation / gesture area
        tabBar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(dp(10), dp(6), dp(10), dp(6) + bottomInset(insets));
                return insets;
            }
        });

        tabHome = tabItem(getString(R.string.tab_home), R.drawable.ic_tab_home);
        tabApps = tabItem(getString(R.string.tab_area), R.drawable.ic_tab_ad);
        tabHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_HOME);
            }
        });
        tabApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_AREA);
            }
        });
        tabBar.addView(tabHome, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabBar.addView(tabApps, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabBarWrapper.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tabBarWrapper;
    }

    /** one tab: icon above label, tinted together */
    private LinearLayout tabItem(String label, int iconRes) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(4), 0, dp(4));

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        item.addView(icon, iLp);

        TextView tv = text(label, 10.5f, cDim, false);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(2);
        item.addView(tv, tLp);
        return item;
    }

    /** paints one tab's icon and label in the accent colour when selected */
    private void paintTab(LinearLayout tab, boolean on) {
        if (tab == null || tab.getChildCount() < 2) {
            return;
        }
        android.widget.ImageView icon = (android.widget.ImageView) tab.getChildAt(0);
        TextView label = (TextView) tab.getChildAt(1);
        int color = on ? ACCENT : cDim;
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        label.setTextColor(color);
    }

    /** shows one of the two pages (0 = home, 1 = safe area) */
    /** the tab the user is currently looking at, for animation direction */
    private int shownTab = -1;

    /**
     * Swaps the visible page with a short slide + fade.
     *
     * The pages are stacked in a FrameLayout, so both can be visible during the
     * transition. Direction follows the tab that is being entered, which makes
     * a left swipe feel like it moves left.
     */
    private void showPage(int tab) {
        if (pageHolder == null || pageHolder.getChildCount() < 2) {
            return;
        }
        final View home = pageHolder.getChildAt(0);
        final View area = pageHolder.getChildAt(1);
        final View in = tab == TAB_HOME ? home : area;
        final View out = tab == TAB_HOME ? area : home;

        if (shownTab == tab || in.getVisibility() == View.VISIBLE) {
            home.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
            area.setVisibility(tab == TAB_HOME ? View.GONE : View.VISIBLE);
            shownTab = tab;
            return;
        }
        boolean toRight = tab > shownTab;
        int offset = dp(48);

        in.animate().cancel();
        out.animate().cancel();

        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(toRight ? offset : -offset);
        in.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        if (out.getVisibility() == View.VISIBLE) {
            out.animate()
                    .alpha(0f)
                    .translationX(toRight ? -offset : offset)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            out.setVisibility(View.GONE);
                            out.setAlpha(1f);
                            out.setTranslationX(0f);
                        }
                    })
                    .start();
        } else {
            out.setVisibility(View.GONE);
            out.setAlpha(1f);
            out.setTranslationX(0f);
        }
        shownTab = tab;
    }

    /** switches page and repaints the tab bar */
    private void selectTab(int tab) {
        currentTab = tab;
        applyTabState();
        showPage(tab);
    }

    private void applyTabState() {
        paintTab(tabHome, currentTab == TAB_HOME);
        paintTab(tabApps, currentTab == TAB_AREA);
    }

    /** the tab bar is shown whenever Shizuku is ready: it is navigation, not state */
    private void updateTabBar() {
        if (tabBarWrapper == null) {
            return;
        }
        // The tab bar is navigation, not a Shizuku feature: hiding it when the
        // service is not ready made the whole second page unreachable and looked
        // like the app was broken. Keep it, and let that page explain itself.
        tabBarWrapper.setVisibility(View.VISIBLE);
        selectTab(currentTab);
    }

    /** turns the safe area on or off */
    /** turns ad blocking on or off */
    /** turns the local DNS filter on or off */
    private void setSafeAreaEnabled(boolean on) {
        if (on) {
            android.content.Intent prep = android.net.VpnService.prepare(this);
            if (prep != null) {
                // first run: the system asks for VPN consent
                startActivityForResult(prep, REQ_VPN);
                return;
            }
            startVpn();
        } else {
            stopVpn();
        }
    }

    private void startVpn() {
        android.content.Intent i = new android.content.Intent(this, AdBlockVpnService.class);
        i.setAction(AdBlockVpnService.ACTION_START);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            toast(getString(R.string.vpn_starting));
        } catch (Throwable t) {
            toast(getString(R.string.sa_toast_failed, t.getMessage()));
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderSafeArea();
            }
        }, 1200);
    }

    private void stopVpn() {
        android.content.Intent i = new android.content.Intent(this, AdBlockVpnService.class);
        i.setAction(AdBlockVpnService.ACTION_STOP);
        try {
            startService(i);
        } catch (Throwable ignored) {
        }
        toast(getString(R.string.vpn_stopping));
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderSafeArea();
            }
        }, 800);
    }

    /** returns a user-facing reason when the safe area cannot be enabled */
    private String safeAreaBlocker() {
        if (!ShizukuShell.isInstalled(this)) {
            return getString(R.string.sa_block_no_shizuku);
        }
        if (!ShizukuShell.isBinderAlive()) {
            return getString(R.string.sa_block_shizuku_stopped);
        }
        if (!ShizukuShell.hasPermission()) {
            return getString(R.string.sa_block_no_permission);
        }
        if (navigationMode() == 0) {
            return getString(R.string.sa_block_three_button);
        }
        return null;
    }

    /** 0 = three-button, 1 = two-button, 2 = gesture */
    private int navigationMode() {
        try {
            return android.provider.Settings.Secure.getInt(
                    getContentResolver(), "navigation_mode", 2);
        } catch (Throwable e) {
            return 2;
        }
    }


    

    

    /** paints the Shizuku state, the switch and the live height */


    /** label for the current theme preference */
    private String currentThemeName() {
        int p = themePref();
        String[] opts = themeOptions();
        if (p == THEME_LIGHT) {
            return opts[1];
        }
        if (p == THEME_DARK) {
            return opts[2];
        }
        return opts[0];
    }

    /** theme picker dialog; themed by the app, unlike the platform Spinner popup */
    private void showThemePicker() {
        final int[] values = {THEME_SYSTEM, THEME_LIGHT, THEME_DARK};
        final String[] labels = themeOptions();
        int cur = themePref();
        int checked = cur == THEME_LIGHT ? 1 : cur == THEME_DARK ? 2 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.appearance))
                .setSingleChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int which) {
                                dlg.dismiss();
                                int wanted = values[which];
                                if (wanted != themePref()) {
                                    uiPrefs().edit().putInt(P_THEME, wanted).apply();
                                    recreate();
                                }
                            }
                        })
                .show();
    }


    /**
     * Warns once when the app runs inside ZhuoYiTong.
     * Shizuku needs platform binder access that the VM may not provide, so the
     * user should know why features are missing instead of assuming a bug.
     */
    private void checkEnvironment() {
        if (envChecked) {
            return;
        }
        envChecked = true;
        if (!EnvironmentCheck.isZhuoyi(this)) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.env_zhuoyi_title))
                .setMessage(getString(R.string.env_zhuoyi_msg))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** paints one row per blocked surface */
    private static final int[] AD_ROWS = {
            R.string.ad_row_hosts, R.string.ad_row_track, R.string.ad_row_switches};

    private void fillBlockedList() {
        if (adListBody == null) {
            return;
        }
        adListBody.removeAllViews();
        addListRow(AD_ROWS[0], String.valueOf(AdBlock.AD_HOSTS.length), true);
        addListRow(AD_ROWS[1], String.valueOf(AdBlock.TRACK_HOSTS.length), true);
        // MIUI's settings namespace only exists on MIUI / HyperOS; show how many
        // of those switches are actually off right now
        addListRow(AD_ROWS[2], recOff + "/" + AdBlock.RECOMMEND_KEYS.length,
                AdBlock.isMiui());
    }

    private int recOff;

    private void addListRow(int labelRes, String count, boolean applicable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(text(getString(labelRes), 12, cText, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (applicable) {
            // "10 个" for lists, bare "5/5" for the ratio row
            row.addView(text(count.contains("/") ? count
                    : count + getString(R.string.ad_count_unit), 12, cDim, false));
            LinearLayout.LayoutParams tickLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tickLp.leftMargin = dp(8);
            row.addView(text("OK", 11, ACCENT, true), tickLp);
        } else {
            row.addView(text(getString(R.string.ad_not_applicable), 11.5f, cSubtle, false));
        }
        adListBody.addView(row);
    }

    /** probes every host and shows the evidence */
    private void runVerify() {
        String blocker = shizukuBlocker();
        if (blocker != null) {
            toast(blocker);
            return;
        }
        saVerifyButton.setEnabled(false);
        saVerifyButton.setText(getString(R.string.ad_verifying));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<AdBlock.Probe> probes = AdBlock.verify();
                final boolean normalOk = AdBlock.verifyNormal("www.baidu.com");
                int blocked = 0;
                for (AdBlock.Probe p : probes) {
                    if (p.blocked) {
                        blocked++;
                    }
                }
                final int blockedCount = blocked;
                final int total = probes.size();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (saVerifyButton == null) {
                            return;
                        }
                        saVerifyButton.setEnabled(true);
                        saVerifyButton.setText(getString(R.string.ad_verify));
                        showVerifyResult(probes, blockedCount, total, normalOk);
                    }
                });
            }
        }, "verify-ads").start();
    }

    private void showVerifyResult(java.util.List<AdBlock.Probe> probes,
                                  int blocked, int total, boolean normalOk) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.ad_verify_summary, blocked, total)).append("\n\n");
        for (AdBlock.Probe p : probes) {
            sb.append(p.blocked ? "[x] " : "[ ] ")
              .append(p.host)
              .append("  -  ").append(p.purpose)
              .append('\n');
        }
        sb.append('\n').append(getString(normalOk
                ? R.string.ad_verify_normal_ok : R.string.ad_verify_normal_bad));

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ad_verify_title))
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** suggests a reboot once, after the first successful enable */
    private void maybeSuggestReboot() {
        if (adRebootAsked) {
            return;
        }
        adRebootAsked = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ad_reboot_title))
                .setMessage(getString(R.string.ad_reboot_msg))
                .setPositiveButton(getString(R.string.ad_reboot_later), null)
                .show();
    }




    /** enables or disables the merged ~190k domain list */
    private void toggleBigList(boolean on) {
        prefs().edit().putBoolean("big_list", on).apply();
        if (on) {
            toast(getString(R.string.ad_big_loading));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AdHosts.load(MainActivity.this);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            renderSafeArea();
                            toast(getString(R.string.ad_big_ready, AdHosts.loadedCount()));
                        }
                    });
                }
            }, "load-big").start();
        } else {
            AdHosts.unload();
            toast(getString(R.string.ad_big_off));
            renderSafeArea();
        }
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("adblock", MODE_PRIVATE);
    }

    /** offers to retry without the reachability probe (proxy / split tunnel) */
    private void askForceEnable() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ad_force_title))
                .setMessage(getString(R.string.ad_force_msg))
                .setPositiveButton(getString(R.string.ad_force_yes),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                forceEnableAdBlock();
                            }
                        })
                .setNegativeButton(getString(R.string.ad_force_no), null)
                .show();
    }

    private void forceEnableAdBlock() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int rc = AdBlock.enableForce();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (rc == AdBlock.ERR_BROKEN) {
                            toast(getString(R.string.ad_err_broken));
                        } else if (rc != AdBlock.OK) {
                            toast(getString(R.string.sa_toast_failed, "settings"));
                        } else {
                            toast(getString(R.string.ad_toast_on));
                            maybeSuggestReboot();
                        }
                        renderSafeArea();
                    }
                });
            }
        }, "force-adblock").start();
    }

    /** hands the bundled GKD build to the system installer */
    private void installGkd() {
        String err = GkdInstaller.install(this);
        if (err != null) {
            toast(getString(R.string.gkd_install_failed, err));
        }
    }

    /** copies the subscription URL GKD's own project recommends */
    private void copySubscription() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "gkd", GkdInstaller.SUBSCRIPTION));
        toast(getString(R.string.gkd_copied));
    }

    /** repaints the GKD button from the installed state */
    private void renderGkd() {
        if (gkdInstallButton == null) {
            return;
        }
        if (GkdInstaller.isInstalled(this)) {
            String v = GkdInstaller.installedVersion(this);
            gkdInstallButton.setText(getString(R.string.gkd_installed, v == null ? "?" : v));
            gkdInstallButton.setEnabled(false);
            gkdInstallButton.setAlpha(0.6f);
        } else {
            gkdInstallButton.setText(getString(R.string.gkd_install));
            gkdInstallButton.setEnabled(true);
            gkdInstallButton.setAlpha(1f);
        }
    }

    /** paints the Shizuku state and the ad-block status */
    private void renderSafeArea() {
        if (saMaster == null) {
            return;
        }
        boolean ready = ShizukuShell.isReady(this);
        String state = ShizukuShell.state(this);
        if (state == null) {
            saShizukuView.setText(getString(R.string.sa_state_ok));
            saShizukuView.setTextColor(ACCENT);
        } else {
            int res = R.string.sa_state_unknown;
            if ("not_installed".equals(state)) {
                res = R.string.sa_state_not_installed;
            } else if ("waiting".equals(state)) {
                res = R.string.sa_state_waiting;
            } else if ("no_permission".equals(state)) {
                res = R.string.sa_state_no_permission;
            }
            saShizukuView.setText(getString(res));
            saShizukuView.setTextColor(cError);
        }

        // The button is the way forward in every state — install, authorise or
        // re-check — so it is never disabled and the card is never dimmed.
        boolean shizukuInstalled = ShizukuShell.isInstalled(this);
        saConfigGroup.setAlpha(1f);
        saMaster.setEnabled(ready);
        saRecheckButton.setEnabled(true);
        saRecheckButton.setAlpha(1f);
        // before authorisation the useful action is "authorise", not "recheck"
        if (ready) {
            saRecheckButton.setText(getString(R.string.sa_recheck));
            saRecheckButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ShizukuShell.warmUp(MainActivity.this);
                    renderSafeArea();
                    toast(getString(R.string.toast_refreshed));
                }
            });
        } else if (!shizukuInstalled) {
            saRecheckButton.setText(getString(R.string.sa_install));
            saRecheckButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String err = ShizukuInstaller.install(MainActivity.this);
                    if (err != null) {
                        toast(getString(R.string.sa_toast_failed, err));
                    }
                }
            });
        } else {
            saRecheckButton.setText(getString(R.string.sa_authorize));
            saRecheckButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Shizuku delivers its binder through our provider; a freshly
                    // installed package has not received it yet, so ask for it and
                    // re-check a moment later. Only then can the permission dialog
                    // be raised over that binder.
                    ShizukuShell.warmUp(MainActivity.this);
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (ShizukuShell.canRequest()) {
                                ShizukuShell.requestPermission(saPermissionListener);
                            } else {
                                // service not running: send the user to Shizuku
                                toast(getString(R.string.sa_start_service));
                                ShizukuShell.openPermissionScreen(MainActivity.this);
                            }
                        }
                    }, 400);
                }
            });
        }

        if (ready) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                        final boolean on = AdBlockVpnService.wasRunning(MainActivity.this);
                    final String dns = on ? "0.0.0.0" : null;
                    final int off = AdBlock.recommendationsOff();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (saMaster == null) {
                                return;
                            }
                            saMaster.setChecked(on);
                            styleSwitch(saMaster, on);
                            saStatusView.setText(getString(
                                    on ? R.string.ad_status_on : R.string.ad_status_off));
                            saStatusView.setTextColor(on ? ACCENT : cDim);
                            if (saHeightValue != null) {
                                saHeightValue.setText(dns == null ? "-" : dns);
                            }
                            recOff = off;
                            fillBlockedList();
                            // nothing to verify while the filter is off
                            if (saVerifyButton != null) {
                                saVerifyButton.setEnabled(on);
                                saVerifyButton.setAlpha(on ? 1f : 0.45f);
                            }
                            if (adBigSwitch != null) {
                                boolean bigOn = prefs().getBoolean("big_list", false);
                                adBigSwitch.setChecked(bigOn);
                                styleSwitch(adBigSwitch, bigOn);
                            }
                        }
                    });
                }
            }, "adblock-state").start();
        } else {
            saMaster.setChecked(false);
            styleSwitch(saMaster, false);
            saStatusView.setText(getString(R.string.ad_status_off));
            saStatusView.setTextColor(cDim);
            if (saVerifyButton != null) {
                saVerifyButton.setEnabled(false);
                saVerifyButton.setAlpha(0.45f);
            }
        }

        fillBlockedList();
        updateTabBar();
    }



    /** repaints the screen-timeout / auto-dim section from the system state */
    private void renderSafe() {
        try {
            render();
        } catch (Throwable ignored) {
        }
    }

    /** the safe-area size comes from the navigation-bar overlay */
    private void setupSafeAreaSpinners() {
    }

    /** handles shortcut intents delivered to this activity */
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (action.equals(consumedAction)) {
            return;
        }
        consumedAction = action;
        if (ACTION_REFRESH_SHORTCUTS.equals(action)) {
            updateShortcuts(true, TuoYuClean.isOn(this, FEAT_TIMEOUT),
                    TuoYuClean.isOn(this, FEAT_DIM));
            finish();
            return;
        }
        final String feature;
        if (ACTION_TOGGLE_STAY_ON.equals(action)) {
            return;
        } else if (ACTION_TOGGLE_TIMEOUT.equals(action)) {
            feature = FEAT_TIMEOUT;
        } else if (ACTION_TOGGLE_DIM.equals(action)) {
            feature = FEAT_DIM;
        } else {
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                setFeature(feature, !TuoYuClean.isOn(MainActivity.this, feature));
            }
        }, 150);
    }

    /** makes every child view (non-)interactive */
    private void setChildrenEnabled(android.view.ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            v.setEnabled(enabled);
            if (v instanceof android.view.ViewGroup) {
                setChildrenEnabled((android.view.ViewGroup) v, enabled);
            }
        }
    }


    /** reads one system-bar inset, with a fallback for old API levels */
    private int systemInset(WindowInsets insets, int type) {
        try {
            android.graphics.Insets i = insets.getInsets(type);
            return Math.max(i.top, Math.max(i.bottom, Math.max(i.left, i.right)));
        } catch (Throwable ignored) {
            return insets.getSystemWindowInsetBottom();
        }
    }

    /** the bottom inset, used by the tab bar */
    private int bottomInset(WindowInsets insets) {
        int v;
        try {
            v = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        } catch (Throwable ignored) {
            v = insets.getSystemWindowInsetBottom();
        }
        android.util.Log.i("TuoYuClean", "bottomInset = " + v);
        return v;
    }

    /**
     * Swipe left / right anywhere to switch tabs.
     *
     * The tab bar sits at the very bottom, which is exactly where a system
     * gesture area can make taps unreliable, so the pages stay reachable by
     * swiping too.
     */
    private void attachSwipe() {
        swipeDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy) * 1.4f) {
                            return false;
                        }
                        selectTab(dx < 0 ? TAB_AREA : TAB_HOME);
                        return true;
                    }
                });
    }

    /** the one button style used everywhere */
    private Button styledButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(primary ? 0xFFFFFFFF : cText);
        b.setBackground(roundRect(primary ? ACCENT : cCard, 12, primary ? 0 : cStroke));
        // Height comes from the vertical padding, not the platform minHeight:
        // the themed minimum differs per style and made the buttons render at
        // different heights on the same screen.
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setStateListAnimator(null);
        b.setElevation(0);
        return b;
    }

    /** tints a switch to match the theme */
    private void styleSwitch(android.widget.Switch s, boolean on) {
        if (s == null) {
            return;
        }
        android.content.res.ColorStateList track = android.content.res.ColorStateList
                .valueOf(on ? ACCENT : 0xFF9AA0A6);
        try {
            s.setThumbTintList(android.content.res.ColorStateList.valueOf(on ? ACCENT : 0xFFB0B4BA));
            s.setTrackTintList(track);
        } catch (Throwable ignored) {
        }
    }

    /** one label + value row used by the two dropdowns */
    private View spinnerRow(int position, boolean dropdown) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView tv = text(themeOptions()[position], 13, cText, false);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** holder for one switch row */
    private static final class SwitchRow {
        final LinearLayout container;
        final android.widget.Switch sw;
        final TextView state;
        final TextView tip;

        SwitchRow(LinearLayout container, android.widget.Switch sw, TextView state, TextView tip) {
            this.container = container;
            this.sw = sw;
            this.state = state;
            this.tip = tip;
        }
    }

    /** builds one switch card and returns its parts */
    private SwitchRow addSwitch(LinearLayout parent, final String feature, String title,
                                String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(roundRect(cCard, 16, cStroke));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 14, cText, false));
        TextView sub = text(subtitle, 11, cDim, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        texts.addView(sub, subLp);
        // the tip doubles as the state line, so no extra row is needed
        TextView state = sub;

        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setShowText(false);
        sw.setClickable(false);
        sw.setFocusable(false);
        card.addView(sw);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFeature(feature, !TuoYuClean.isOn(MainActivity.this, feature));
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(card, lp);
        return new SwitchRow(card, sw, state, sub);
    }

    /** applies one feature and refreshes the screen */
    /**
     * Why writing a system setting cannot work right now.
     *
     * Four distinct causes produce the same SecurityException, and "grant the
     * permission" tells the user nothing about which one they hit. Each case
     * names the exact next action instead.
     *
     * @return null when writing is possible, otherwise a user-facing reason
     */
    private String permissionBlocker() {
        if (TuoYuClean.hasPermission(this)) {
            return null;
        }
        String shizuku = shizukuBlocker();
        return shizuku != null ? shizuku : getString(R.string.block_unknown);
    }

    /** why Shizuku cannot run commands right now; null when it can */
    private String shizukuBlocker() {
        if (!ShizukuShell.isInstalled(this)) {
            return getString(R.string.block_no_shizuku);
        }
        if (!ShizukuShell.isBinderAlive()) {
            return getString(R.string.block_service_stopped);
        }
        if (!ShizukuShell.hasPermission()) {
            return getString(R.string.block_not_authorized);
        }
        return null;
    }

    private void setFeature(String feature, boolean on) {
        String blocker = permissionBlocker();
        if (blocker != null) {
            toast(blocker);
            maybeAskPermission();
            renderSafe();
            return;
        }
        if (!TuoYuClean.set(MainActivity.this, feature, on)) {
            toast(getString(R.string.toast_need_permission));
            maybeAskPermission();
            renderSafe();
            return;
        }
        renderSafe();
        if (FEAT_TIMEOUT.equals(feature) || FEAT_DIM.equals(feature)) {
            updateShortcuts(true, TuoYuClean.isOn(this, FEAT_TIMEOUT),
                    TuoYuClean.isOn(this, FEAT_DIM));
        }
        toast(getString(R.string.toast_applied));
    }


    /**
     * Publishes the three launcher shortcuts.
     *
     * Static so the headless {@link ShortcutActivity} can refresh them after it
     * flips a setting. Called on every resume: dynamic shortcuts are dropped by
     * the launcher when an app is updated, so re-publishing each launch is what
     * keeps them present.
     */
    static void publishShortcuts(android.content.Context c) {
        if (android.os.Build.VERSION.SDK_INT < 25) {
            return;
        }
        android.content.pm.ShortcutManager sm =
                c.getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null) {
            return;
        }
        java.util.List<android.content.pm.ShortcutInfo> list = new java.util.ArrayList<>();

        boolean timeoutOn = TuoYuClean.isOn(c, FEAT_TIMEOUT);
        list.add(buildShortcut(c, "timeout_on", timeoutOn,
                c.getString(timeoutOn ? R.string.sc_timeout_turn_off
                        : R.string.sc_timeout_turn_on),
                c.getString(timeoutOn ? R.string.sc_timeout_turn_off_long
                        : R.string.sc_timeout_turn_on_long),
                ACTION_TOGGLE_TIMEOUT, R.drawable.ic_sc_timeout, 0));

        boolean dimOn = TuoYuClean.isOn(c, FEAT_DIM);
        list.add(buildShortcut(c, "dim_on", dimOn,
                c.getString(dimOn ? R.string.sc_dim_turn_off : R.string.sc_dim_turn_on),
                c.getString(dimOn ? R.string.sc_dim_turn_off_long
                        : R.string.sc_dim_turn_on_long),
                ACTION_TOGGLE_DIM, R.drawable.ic_sc_dim, 1));

        boolean vpnOn = AdBlockVpnService.wasRunning(c);
        list.add(buildShortcut(c, "id_area", vpnOn,
                c.getString(vpnOn ? R.string.sc_area_turn_off : R.string.sc_area_turn_on),
                c.getString(vpnOn ? R.string.sc_area_turn_off_long
                        : R.string.sc_area_turn_on_long),
                ACTION_TOGGLE_AREA, R.drawable.ic_sc_block, 2));

        try {
            sm.setDynamicShortcuts(list);
        } catch (Throwable ignored) {
        }
    }

    private static android.content.pm.ShortcutInfo buildShortcut(
            android.content.Context c, String id, boolean on, String label,
            String longLabel, String action, int iconRes, int rank) {
        android.content.Intent intent = new android.content.Intent(action);
        intent.setClass(c, ShortcutActivity.class);
        return new android.content.pm.ShortcutInfo.Builder(c, id)
                .setShortLabel(label)
                .setLongLabel(longLabel)
                .setIcon(android.graphics.drawable.Icon.createWithResource(c, iconRes))
                .setIntent(intent)
                .setRank(rank)
                .build();
    }

    /** publishes the dynamic shortcuts with state-aware labels */
    private void updateShortcuts(boolean granted, boolean timeoutOn, boolean dimOn) {
        if (Build.VERSION.SDK_INT < 25) {
            return;
        }
        android.content.pm.ShortcutManager sm =
                getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null) {
            return;
        }
        java.util.List<android.content.pm.ShortcutInfo> list = new java.util.ArrayList<>();
        if (granted) {
            list.add(shortcut(sm, ID_TIMEOUT_ON, timeoutOn,
                    getString(timeoutOn ? R.string.sc_timeout_turn_off : R.string.sc_timeout_turn_on),
                    getString(timeoutOn ? R.string.sc_timeout_turn_off_long
                            : R.string.sc_timeout_turn_on_long),
                    ACTION_TOGGLE_TIMEOUT, R.drawable.ic_sc_timeout));
            list.add(shortcut(sm, ID_DIM_ON, dimOn,
                    getString(dimOn ? R.string.sc_dim_turn_off : R.string.sc_dim_turn_on),
                    getString(dimOn ? R.string.sc_dim_turn_off_long : R.string.sc_dim_turn_on_long),
                    ACTION_TOGGLE_DIM, R.drawable.ic_sc_dim));
        }
        {
            // a VPN, not a Shizuku feature: always offered
            boolean areaOn = AdBlockVpnService.wasRunning(this);
            list.add(shortcut(sm, ID_AREA, areaOn,
                    getString(areaOn ? R.string.sc_area_turn_off : R.string.sc_area_turn_on),
                    getString(areaOn ? R.string.sc_area_turn_off_long
                            : R.string.sc_area_turn_on_long),
                    ACTION_TOGGLE_AREA, R.drawable.ic_sc_area));
        }
        try {
            sm.setDynamicShortcuts(list);
        } catch (Throwable ignored) {
        }
    }

    /** builds one shortcut pointing at the headless activity */
    private android.content.pm.ShortcutInfo shortcut(
            android.content.pm.ShortcutManager sm, String id, boolean on, String label,
            String longLabel, String action, int iconRes) {
        Intent intent = new Intent(action);
        // headless: flipping a system setting must not open the UI
        intent.setClass(this, ShortcutActivity.class);
        return new android.content.pm.ShortcutInfo.Builder(this, id)
                .setShortLabel(label)
                .setLongLabel(longLabel)
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, iconRes))
                .setIntent(intent)
                .setRank(on ? 0 : 1)
                .build();
    }


    /** paints the two screen-timeout switches from the system state */
    private void render() {
        boolean timeoutOn = TuoYuClean.isOn(this, FEAT_TIMEOUT);
        boolean dimOn = TuoYuClean.isOn(this, FEAT_DIM);
        if (swTimeout != null) {
            swTimeout.setChecked(timeoutOn);
            styleSwitch(swTimeout, timeoutOn);
        }
        if (swDim != null) {
            swDim.setChecked(dimOn);
            styleSwitch(swDim, dimOn);
        }
        if (powerValue != null) {
            boolean forever = TuoYuClean.isForever(this);
            powerValue.setText(TuoYuClean.describeHead(this));
            // same accent as the "已开启" captions when the screen never sleeps
            powerValue.setTextColor(forever ? ACCENT : cText);
            if (timeoutValue != null) {
                timeoutValue.setText(TuoYuClean.describeSub(this));
                timeoutValue.setTextColor(forever ? ACCENT : cDim);
                timeoutValue.setAlpha(forever ? 0.75f : 1f);
            }
        }
        if (timeoutTip != null) {
            timeoutTip.setText(getString(timeoutOn ? R.string.state_on : R.string.state_off));
            timeoutTip.setTextColor(timeoutOn ? ACCENT : cSubtle);
        }
        // the Shizuku card sits on this page, so its state has to be refreshed here
        renderSafeArea();
        if (dimTip != null) {
            dimTip.setText(getString(dimOn ? R.string.state_on : R.string.state_off));
            dimTip.setTextColor(dimOn ? ACCENT : cSubtle);
        }
    }

    /** layout params with only a top margin */
    private LinearLayout.LayoutParams headerLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    /**
     * Feeds every touch to the swipe detector before any child handles it.
     *
     * Child ScrollViews consume vertical drags, but they never claim a
     * horizontal fling, and observing here means the swipe works on both pages.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (swipeDetector != null) {
            swipeDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Runs the long-press shortcut action.
     *
     * Long-pressing the icon is the only way to reach these without opening the
     * app, which is what the safe area needs: it is a system-wide setting, so
     * turning it off has to be quick.
     */
    private void handleShortcutAction(android.content.Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        // the same intent is delivered to onCreate and again through the
        // deferred runnable below; consume it once so the switch cannot flip
        // twice and end up back where it started
        if (action.equals(consumedAction)) {
            return;
        }
        consumedAction = action;
        if (ACTION_REFRESH_SHORTCUTS.equals(action)) {
            // the headless shortcut activity asks for a label refresh; nothing
            // to show, so close straight away
            finish();
            return;
        }
        final String feature;
        if (ACTION_TOGGLE_STAY_ON.equals(action)) {
            return; // removed feature: just show the screen
        } else if (ACTION_TOGGLE_TIMEOUT.equals(action)) {
            feature = FEAT_TIMEOUT;
        } else if (ACTION_TOGGLE_DIM.equals(action)) {
            feature = FEAT_DIM;
        } else {
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                // evaluate the current state right before toggling
                setFeature(feature, !TuoYuClean.isOn(MainActivity.this, feature));
            }
        }, 150);
    }

    private void toast(String msg) {
        // never add FLAG_KEEP_SCREEN_ON here: it would override the very
        // screen_off_timeout / dim_screen behaviour this app is about
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------- permission

    /**
     * Makes sure we can write secure settings.
     *
     * Shizuku already runs as the shell user, which is allowed to grant this
     * permission, so when it is available the grant happens silently and the
     * user never sees an adb command. The manual dialog is only a fallback for
     * devices where Shizuku is not set up yet.
     */
    private void maybeAskPermission() {
        if (TuoYuClean.hasPermission(this)) {
            return;
        }
        if (ShizukuShell.isReady(this)) {
            autoGrantPermission();
            return;
        }
        if (!uiPrefs().getBoolean(P_ASKED, false)) {
            uiPrefs().edit().putBoolean(P_ASKED, true).apply();
            ui.postDelayed(permissionTask, 350);
        }
    }

    /** grants WRITE_SECURE_SETTINGS through Shizuku, without any UI */
    private void autoGrantPermission() {
        if (granting) {
            return;
        }
        granting = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String pkg = getPackageName();
                ShizukuShell.exec("pm grant " + pkg
                        + " android.permission.WRITE_SECURE_SETTINGS");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        granting = false;
                        if (TuoYuClean.hasPermission(MainActivity.this)) {
                            renderSafe();
                            renderSafeArea();
                        } else if (!uiPrefs().getBoolean(P_ASKED, false)) {
                            // Shizuku could not do it; fall back to the manual way
                            uiPrefs().edit().putBoolean(P_ASKED, true).apply();
                            showPermissionDialog();
                        }
                    }
                });
            }
        }, "grant-secure-settings").start();
    }

    private final Runnable permissionTask = new Runnable() {
        @Override
        public void run() {
            showPermissionDialog();
        }
    };

    /**
     * First-run guidance.
     *
     * Deliberately does NOT show the adb command. A user who just installed the
     * app cannot act on it, and it makes the app look broken to anyone without a
     * PC. Shizuku is bundled and installs in one tap, so that is offered first;
     * the manual command lives behind "other ways" for power users.
     */
    private void showPermissionDialog() {
        if (isFinishing()) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        boolean installed = ShizukuShell.isInstalled(this);
        TextView body = text(getString(installed
                ? R.string.guide_body_installed : R.string.guide_body), 13.5f, cText, false);
        body.setLineSpacing(dp(4), 1f);
        box.addView(body);

        TextView hint = text(getString(installed
                ? R.string.guide_hint_installed : R.string.guide_hint), 11.5f, cSubtle, false);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(12);
        box.addView(hint, hLp);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.guide_title))
                .setView(box)
                .setPositiveButton(getString(installed
                                ? R.string.guide_open_shizuku : R.string.guide_install_shizuku),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int w) {
                                if (ShizukuShell.isInstalled(MainActivity.this)) {
                                    // already there: the user just needs to start it
                                    ShizukuShell.openManager(MainActivity.this);
                                } else {
                                    String err = ShizukuInstaller.install(MainActivity.this);
                                    if (err != null) {
                                        toast(getString(R.string.sa_toast_failed, err));
                                    }
                                }
                            }
                        })
                .setNeutralButton(getString(R.string.guide_other_way),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dlg, int w) {
                                showManualCommandDialog();
                            }
                        })
                .setNegativeButton(getString(R.string.guide_later), null)
                .show();
    }

    /** the adb fallback, only reached from "other ways" */
    private void showManualCommandDialog() {
        final String cmd = TuoYuClean.permissionCommand(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        TextView body = text(getString(R.string.guide_manual_body), 12.5f, cDim, false);
        body.setLineSpacing(dp(3), 1f);
        box.addView(body);

        TextView code = text(cmd, 12, dark ? ACCENT : 0xFFB06A00, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setBackground(roundRect(dark ? 0xFF07080A : 0xFFF1F2F4, 12, cStroke));
        code.setPadding(dp(12), dp(12), dp(12), dp(12));
        code.setTextIsSelectable(true);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(14);
        box.addView(code, cLp);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.guide_manual_title))
                .setView(box)
                .setPositiveButton(getString(R.string.copy), null)
                .setNeutralButton(getString(R.string.recheck), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
                        toast(getString(R.string.copied));
                    }
                });
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        renderSafe();
                        if (TuoYuClean.hasPermission(MainActivity.this)) {
                            dlg.dismiss();
                            toast(getString(R.string.toast_refreshed));
                        } else {
                            toast(getString(R.string.state_unknown));
                        }
                    }
                });
            }
        });
        dlg.show();
    }
}
