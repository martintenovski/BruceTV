package com.bearacle.tv;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.animation.DecelerateInterpolator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String PREFS = "bearacle";
    static final String PLAY_STORE = "com.android.vending";
    static final String[] DEFAULT_FAVS = {
            PLAY_STORE, "org.smarttube.stable", "com.nuvio.tv", "com.limelight", "com.netflix.ninja",
            "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.stremio.one",
            "com.wbd.stream", "com.apple.atve.androidtv.appletv"};
    static final String DEFAULT_FOLDERS = "{\"Utilities\":[\"cm.aptoidetv.pt\",\"com.downloader.tv.installer.xapk\","
            + "\"com.yablio.sendfilestotv\",\"com.tcl.browser\",\"com.mediatek.miracast\",\"com.mediatek.wwtv.mediaplayer\","
            + "\"com.expressluck.tvhelpmanual\"]}";
    static final int[][] GRADIENTS = {
            {0xFF000428, 0xFF004E92}, {0xFF0F2027, 0xFF2C5364}, {0xFF141E30, 0xFF243B55},
            {0xFF42275A, 0xFF734B6D}, {0xFF3A1C71, 0xFFD76D77, 0xFFFFAF7B}, {0xFF232526, 0xFF414345}};

    static class App { String pkg, label; Drawable art; boolean banner; }
    static final String TIZENTUBE = "io.gh.reisxd.tizentube.cobalt", YOUTUBE = "com.google.android.youtube.tv";
    static final String GAME = "luna.gamemode"; // Game Mode is listed as an app (pin it to Home like any other)

    final ExecutorService bg = Executors.newFixedThreadPool(2);
    final Handler ui = new Handler(Looper.getMainLooper());
    final Map<String, App> apps = new HashMap<>();
    List<App> sorted = new ArrayList<>();
    boolean appsDirty = true;
    long weatherAt;
    String openFolder;
    String moving;   // package being repositioned on the Home row (left/right moves it, OK drops it)
    long wallTry;
    static final String HOME_HINT = "Hold OK on an app for options";
    // Home layouts, picked in Control Center > Home layout (pref "layout"). Classic is the default.
    static final String[][] LAYOUTS = {
            {"classic", "Classic", "Greeting, apps row and buttons"},
            {"dock", "Dock", "Big clock, one floating dock"},
            {"bento", "Bento", "Big tiles, Game Mode as the hero"},
            {"bottom", "Bottom bar", "Labelled icon bar along the bottom"}};
    String layout = "classic";
    // Button/nav look (pref "skin", theme overlays in themes.xml) and app icon size (pref "iconSize", index into SIZES).
    static final String[][] SKINS = {
            {"default", "Default"}, {"glass", "Glassy"}, {"frosted", "Frosted glass"}};
    static final int[] SKIN_STYLE = {0, R.style.SkinGlass, R.style.SkinFrosted};
    static final String[] SIZE_NAMES = {"Smallest", "Small", "Default", "Large", "Larger", "Largest"};
    static final float[] SIZES = {0.75f, 0.88f, 1f, 1.15f, 1.3f, 1.5f};
    List<String[]> contRows = new ArrayList<>();
    String weatherText = "";

    SharedPreferences prefs;
    ImageView wall;
    View header, home, dim, appsOverlay, wallOverlay, recentOverlay, controlOverlay, continueBox;
    View[] overlays;
    LinearLayout favRow, wallRow, recentRow, continueRow;
    GridLayout grid, ccGrid;
    TextView greeting, weather, appsTitle, ramText, recentHint, homeHint;
    TextView contTitle, contSub, gameSub, dockLabel;   // only some layouts have these
    ImageView gameArt;
    WeatherView wxScene;
    int wxCode = -1;
    boolean wxDay = true;
    long lastGameAt;
    View contTile;
    ProgressBar ramBar;

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (!isFinishing()) refreshApps();
        }
    };
    final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); applyDark(); checkDaily(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains("setup_done") && !prefs.contains("setup_started")) {
            if (prefs.getAll().isEmpty()) startActivity(new Intent(this, SetupActivity.class)); // a fresh install: set it up
            else prefs.edit().putBoolean("setup_done", true).apply(); // already in use
        }
        layout = prefs.getString("layout", "classic");
        if (layoutName(layout) == null) layout = "classic"; // a layout that was removed
        int skin = skinIndex();
        if (SKIN_STYLE[skin] != 0) getTheme().applyStyle(SKIN_STYLE[skin], true); // must be on the theme before the layout inflates
        setContentView(layoutRes());
        wall = findViewById(R.id.wall);
        dim = findViewById(R.id.dim);
        header = findViewById(R.id.header);
        home = findViewById(R.id.home);
        appsOverlay = findViewById(R.id.appsOverlay);
        wallOverlay = findViewById(R.id.wallOverlay);
        recentOverlay = findViewById(R.id.recentOverlay);
        controlOverlay = findViewById(R.id.controlOverlay);
        overlays = new View[]{appsOverlay, wallOverlay, recentOverlay, controlOverlay};
        continueBox = findViewById(R.id.continueBox);
        continueRow = findViewById(R.id.continueRow);
        favRow = findViewById(R.id.favRow);
        wallRow = findViewById(R.id.wallRow);
        recentRow = findViewById(R.id.recentRow);
        grid = findViewById(R.id.grid);
        ccGrid = findViewById(R.id.ccGrid);
        greeting = findViewById(R.id.greeting);
        weather = findViewById(R.id.weather);
        appsTitle = findViewById(R.id.appsTitle);
        ramText = findViewById(R.id.ramText);
        ramBar = findViewById(R.id.ramBar);
        recentHint = findViewById(R.id.recentHint);
        homeHint = findViewById(R.id.homeHint);
        contTile = findViewById(R.id.contTile);
        contTitle = findViewById(R.id.contTitle);
        contSub = findViewById(R.id.contSub);
        dockLabel = findViewById(R.id.dockLabel);
        liftDockLabel();
        gameArt = findViewById(R.id.gameArt);
        gameSub = findViewById(R.id.gameSub);
        wxScene = findViewById(R.id.wxAnim);
        // last known weather, so a fresh start (or a layout switch) isn't empty until the network answers
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        weatherText = prefs.getString("wxText", "");
        wxCode = prefs.getInt("wxCode", -1);
        wxDay = prefs.getBoolean("wxDay", hr >= 7 && hr < 19);
        setText(weather, weatherText);
        applyWx();
        tweakNav();

        click(R.id.navHome, v -> closeOverlays());
        click(R.id.navApps, v -> showApps());
        click(R.id.actApps, v -> showApps());
        click(R.id.navRecent, v -> showRecent());
        click(R.id.navInputs, v -> showInputs());
        click(R.id.actInputs, v -> showInputs());
        click(R.id.navGame, v -> startGame());
        click(R.id.actGame, v -> startGame());
        click(R.id.actWall, v -> showWallpapers());
        // Home / Apps / Recent switch as soon as they get focus; the nav bar stays up over Apps and Recent.
        tab(R.id.navHome, () -> { if (home.getVisibility() != View.VISIBLE) closeOverlays(); });
        tab(R.id.navApps, () -> { if (appsOverlay.getVisibility() != View.VISIBLE) showApps(); });
        tab(R.id.navRecent, () -> { if (recentOverlay.getVisibility() != View.VISIBLE) showRecent(); });
        // Inputs / Game / Settings open something on top of Home, so show Home behind them, not Apps or Recent.
        for (int id : new int[]{R.id.navInputs, R.id.navGame, R.id.navSettings})
            tab(id, () -> { if (home.getVisibility() != View.VISIBLE) closeOverlays(false); });
        click(R.id.navSettings, v -> showControl());
        click(R.id.actOptimize, v -> optimize());
        click(R.id.actRestart, v -> restartTv());
        for (int id : new int[]{R.id.actOptimize, R.id.actRestart})
            findViewById(id).setOnFocusChangeListener((v, f) -> zoom(v, f, 1.06f));
        click(R.id.actSettings, v -> { if (layout.equals("dock")) showControl(); else openSettings(); }); // Dock: the gear at the top right is the Control Center
        click(R.id.actRecent, v -> showRecent());
        click(R.id.actSleep, v -> sleep());
        click(R.id.contTile, v -> resumeFirst());
        click(R.id.statusPill, v -> showControl());
        for (int id : new int[]{R.id.actGame, R.id.navGame}) {
            View g = findViewById(id);
            if (g != null) g.setOnLongClickListener(v -> { gameMenu(); return true; });
        }
        float zoom = layout.equals("bento") ? 1.03f : 1.06f;
        for (int id : new int[]{R.id.actApps, R.id.actInputs, R.id.actGame, R.id.actWall, R.id.actSettings, R.id.actRecent,
                R.id.actSleep, R.id.contTile}) {
            View v0 = findViewById(id);
            if (v0 == null) continue;
            v0.setOnFocusChangeListener((v, f) -> zoom(v, f, zoom));
            if (!(v0 instanceof TextView)) continue;
            TextView tv = (TextView) v0;
            if (tv.getCompoundDrawablesRelative()[0] == null || tv.getText().length() == 0) continue; // icon-only or icon-over-label
            // pull the icon in next to the centered label, whenever the button's width is (re)known
            tv.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> {
                if (r - l == or - ol) return;
                Drawable d = tv.getCompoundDrawablesRelative()[0];
                float content = tv.getPaint().measureText(tv.getText().toString()) + d.getIntrinsicWidth() + tv.getCompoundDrawablePadding();
                int pad = (int) Math.max(0, (r - l - content) / 2);
                tv.post(() -> tv.setPaddingRelative(pad, 0, pad, 0));
            });
        }

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);

        // HorizontalScrollView moves focus itself, so the system's focus "pop" never plays on the home rows; play it here.
        home.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            if (dockLabel != null && now != null) // Dock layout: name of the focused app or tool above the dock
                dockLabel.setText(now.getParent() == favRow || now.getParent() == favScroll().getParent() ? now.getContentDescription() : "");
            if (old == null || now == null || old.getParent() != now.getParent()) return;
            if (now.getParent() != favRow && now.getParent() != continueRow) return;
            ViewGroup row = (ViewGroup) now.getParent();
            now.playSoundEffect(row.indexOfChild(now) > row.indexOfChild(old)
                    ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        });

        applyStoredWallpaper();
        refreshApps();
        home.post(this::focusFirstFav); // after layout, or the window gives first focus to the Home pill
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGreeting();
        updateNet();
        updateTileInfo();
        loadLastGame();
        applyDark();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        refreshWeather();
        loadContinue();
        checkDaily();
        autoCheckUpdate();
        String want = prefs.getString("layout", "classic"); // Setup may have changed it
        if (layoutName(want) == null) want = "classic";
        if (!want.equals(layout)) recreate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        endMove();
        unregisterReceiver(tick);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: reset to the home screen.
        endMove();
        closeOverlays();
        ((View) favRow.getParent()).scrollTo(0, 0);
        focusFirstFav();
    }

    @Override
    public void onBackPressed() {
        if (moving != null) endMove();
        else if (appsOverlay.getVisibility() == View.VISIBLE && openFolder != null) { renderGrid(null); focusFirst(grid); }
        else if (home.getVisibility() != View.VISIBLE) closeOverlays();
        // A launcher never finishes on Back.
    }

    // ---------- apps ----------

    void refreshApps() {
        if (appsDirty) { loadApps(); appsDirty = false; }
        renderFavs(-1);
        updateTileInfo();
        if (appsOverlay.getVisibility() == View.VISIBLE) renderGrid(openFolder);
    }

    void loadApps() {
        PackageManager pm = getPackageManager();
        apps.clear();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                boolean game = p.equals(getPackageName());
                if (game) {
                    if (!ri.activityInfo.name.endsWith(".GameBootActivity")) continue;
                    p = GAME;
                }
                if (apps.containsKey(p)) continue;
                App a = new App();
                a.pkg = p;
                a.label = ri.loadLabel(pm).toString();
                a.art = ri.activityInfo.loadBanner(pm);
                a.banner = a.art != null;
                if (!a.banner) a.art = ri.loadIcon(pm);
                if (p.equals(TIZENTUBE)) { // wears the stock YouTube tile
                    try { Drawable yt = pm.getApplicationBanner(YOUTUBE); if (yt != null) { a.art = yt; a.banner = true; } } catch (Exception e) { /* keep its own */ }
                }
                apps.put(p, a);
            }
        }
        sorted = new ArrayList<>(apps.values());
        sorted.sort((x, y) -> x.pkg.equals(PLAY_STORE) ? -1 : y.pkg.equals(PLAY_STORE) ? 1
                : x.label.compareToIgnoreCase(y.label));
    }

    List<String> favs() {
        String s = prefs.getString("favs", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_FAVS));
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    void saveFavs(List<String> f) { prefs.edit().putString("favs", TextUtils.join(",", f)).apply(); }

    void renderFavs(int focusIndex) {
        favRow.removeAllViews();
        for (String p : favs()) {
            App a = apps.get(p);
            if (a == null) continue;
            int[] sz = favTile();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(sz[0]), px(sz[1]));
            lp.setMarginEnd(px(sz[2]));
            View t = tile(a, iconScale());
            if (p.equals(moving)) t.setForeground(movingRing());
            favRow.addView(t, lp);
        }
        if (focusIndex >= 0 && favRow.getChildCount() > 0)
            favRow.getChildAt(Math.min(focusIndex, favRow.getChildCount() - 1)).requestFocus();
    }

    /** All apps (Play Store first, then folders, then the rest), or the apps of one folder. */
    void renderGrid(String folder) {
        openFolder = folder;
        grid.setColumnCount(Math.max(2, (int) ((getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - 96) / (124 * iconScale() + 14))));
        appsTitle.setText(folder == null ? "All apps" : folder);
        grid.removeAllViews();
        JSONObject fs = folders();
        if (folder != null) {
            for (App a : folderApps(fs, folder)) addGrid(tile(a, iconScale()));
            return;
        }
        App store = apps.get(PLAY_STORE);
        if (store != null) addGrid(tile(store, iconScale()));
        for (Iterator<String> it = fs.keys(); it.hasNext(); ) {
            String n = it.next();
            List<App> in = folderApps(fs, n);
            if (!in.isEmpty()) addGrid(folderTile(n, in, iconScale()));
        }
        for (App a : sorted)
            if (!a.pkg.equals(PLAY_STORE) && folderOf(fs, a.pkg) == null) addGrid(tile(a, iconScale()));
    }

    void addGrid(View v) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = px(Math.round(124 * iconScale()));
        lp.height = px(Math.round(70 * iconScale()));
        lp.setMargins(px(7), px(7), px(7), px(7));
        grid.addView(v, lp);
    }

    FrameLayout baseTile(CharSequence desc) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setBackgroundResource(R.drawable.tile_bg);
        f.setForeground(getDrawable(R.drawable.tile_focus));
        f.setClipToOutline(true);
        f.setContentDescription(desc);
        f.setOnFocusChangeListener((v, has) -> zoom(v, has, 1.12f));
        return f;
    }

    Drawable copy(Drawable d) {
        return d.getConstantState() != null ? d.getConstantState().newDrawable(getResources()) : d;
    }

    View tile(App a) { return tile(a, 1f); }

    View tile(App a, float s) {
        FrameLayout f = baseTile(a.label);
        if (a.banner) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(a.art));
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(copy(a.art));
            col.addView(iv, new LinearLayout.LayoutParams(px(Math.round(30 * s)), px(Math.round(30 * s))));
            col.addView(label(a.label, Math.round(10 * s)), new LinearLayout.LayoutParams(-2, -2));
            f.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        f.setOnClickListener(v -> launch(a.pkg));
        f.setOnLongClickListener(v -> { appMenu(a); return true; });
        f.setOnKeyListener((v, k, e) -> {
            if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { appMenu(a); return true; }
            return false;
        });
        return f;
    }

    View folderTile(String name, List<App> in, float s) {
        FrameLayout f = baseTile(name);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(2);
        for (int i = 0; i < Math.min(4, in.size()); i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(in.get(i).banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(copy(in.get(i).art));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(Math.round(44 * s));
            lp.height = px(Math.round(22 * s));
            lp.setMargins(px(2), px(2), px(2), px(2));
            g.addView(iv, lp);
        }
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        glp.topMargin = px(4);
        f.addView(g, glp);
        TextView tv = label(name, Math.round(10 * s));
        tv.setGravity(Gravity.CENTER);
        f.addView(tv, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(0xFF2A3040);
        bgd.setCornerRadius(px(12));
        f.setBackground(bgd);
        f.setOnClickListener(v -> { renderGrid(name); focusFirst(grid); });
        View.OnLongClickListener menu = v -> {
            dialog().setTitle(name).setItems(new String[]{"Delete folder (apps stay installed)"}, (d, w) -> {
                JSONObject fs = folders();
                fs.remove(name);
                saveFolders(fs);
                renderGrid(null);
                focusFirst(grid);
            }).show();
            return true;
        };
        f.setOnLongClickListener(menu);
        f.setOnKeyListener((v, k, e) -> k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && menu.onLongClick(v));
        return f;
    }

    TextView label(CharSequence text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(sp);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setShadowLayer(6, 0, 1, 0xCC000000);
        tv.setPadding(px(6), px(3), px(6), px(3));
        return tv;
    }

    void appMenu(App a) {
        List<String> f = favs();
        int idx = f.indexOf(a.pkg);
        boolean inFolder = folderOf(folders(), a.pkg) != null;
        List<String> opts = new ArrayList<>();
        opts.add(idx >= 0 ? "Remove from Home" : "Add to Home");
        if (idx >= 0 && f.size() > 1) opts.add("Move on Home");
        opts.add(inFolder ? "Remove from folder" : "Add to folder…");
        if (!GAME.equals(a.pkg)) { opts.add("App info"); opts.add("Uninstall"); }
        dialog().setTitle(a.label).setItems(opts.toArray(new String[0]), (dlg, w) -> {
            switch (opts.get(w)) {
                case "Remove from Home": f.remove(idx); saveFavs(f); renderFavs(-1); break;
                case "Add to Home": f.add(a.pkg); saveFavs(f); renderFavs(-1); toast(a.label + " added to Home"); break;
                case "Move on Home": startMove(a); break;
                case "Add to folder…": pickFolder(a); break;
                case "Remove from folder": moveToFolder(a.pkg, null); break;
                case "App info": start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))); break;
                case "Uninstall": start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); break;
            }
        }).show();
    }

    GradientDrawable movingRing() {
        GradientDrawable g = new GradientDrawable();
        g.setStroke(px(4), 0xFF4FA3FF);
        g.setCornerRadius(px(12));
        return g;
    }

    /** Home row reorder: the app lifts out (blue ring) and follows left/right until OK or Back drops it. */
    void startMove(App a) {
        moving = a.pkg;
        if (home.getVisibility() != View.VISIBLE) closeOverlays(false);
        setText(homeHint, "◀ ▶  move " + a.label + "   ·   OK to drop it here");
        renderFavs(shown(favs(), favs().indexOf(a.pkg)));
    }

    void endMove() {
        if (moving == null) return;
        String p = moving;
        moving = null;
        setText(homeHint, HOME_HINT);
        renderFavs(shown(favs(), favs().indexOf(p)));
    }

    void moveFav(int dir) {
        List<String> f = favs();
        int i = f.indexOf(moving), j = i + dir;
        while (j >= 0 && j < f.size() && !apps.containsKey(f.get(j))) j += dir; // skip uninstalled packages
        if (i < 0 || j < 0 || j >= f.size()) return;
        f.remove(i);
        f.add(j, moving);
        saveFavs(f);
        renderFavs(shown(f, j));
    }

    /** Position in the favorites row, which skips uninstalled packages. */
    int shown(List<String> f, int i) {
        int n = 0;
        for (int j = 0; j < i; j++) if (apps.containsKey(f.get(j))) n++;
        return n;
    }

    // ---------- folders ----------

    JSONObject folders() {
        try { return new JSONObject(prefs.getString("folders", DEFAULT_FOLDERS)); }
        catch (Exception e) { return new JSONObject(); }
    }

    void saveFolders(JSONObject f) { prefs.edit().putString("folders", f.toString()).apply(); }

    String folderOf(JSONObject fs, String pkg) {
        for (Iterator<String> it = fs.keys(); it.hasNext(); ) {
            String n = it.next();
            JSONArray arr = fs.optJSONArray(n);
            for (int i = 0; arr != null && i < arr.length(); i++) if (pkg.equals(arr.optString(i))) return n;
        }
        return null;
    }

    List<App> folderApps(JSONObject fs, String name) {
        List<App> out = new ArrayList<>();
        JSONArray arr = fs.optJSONArray(name);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            App a = apps.get(arr.optString(i));
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Moves pkg out of any folder and, when target is not null, into target. */
    void moveToFolder(String pkg, String target) {
        JSONObject fs = folders();
        try {
            for (Iterator<String> it = fs.keys(); it.hasNext(); ) {
                String n = it.next();
                JSONArray arr = fs.getJSONArray(n), keep = new JSONArray();
                for (int i = 0; i < arr.length(); i++) if (!pkg.equals(arr.optString(i))) keep.put(arr.optString(i));
                fs.put(n, keep);
            }
            if (target != null) {
                JSONArray arr = fs.optJSONArray(target);
                fs.put(target, (arr == null ? new JSONArray() : arr).put(pkg));
            }
        } catch (Exception ignored) { }
        saveFolders(fs);
        if (appsOverlay.getVisibility() == View.VISIBLE) { renderGrid(openFolder); focusFirst(grid); }
    }

    void pickFolder(App a) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = folders().keys(); it.hasNext(); ) names.add(it.next());
        names.add("New folder…");
        dialog().setTitle("Add " + a.label + " to").setItems(names.toArray(new String[0]), (d, w) -> {
            if (w < names.size() - 1) { moveToFolder(a.pkg, names.get(w)); return; }
            EditText et = new EditText(this);
            et.setSingleLine(true);
            et.setHint("Folder name");
            dialog().setTitle("New folder").setView(et).setPositiveButton("Create", (d2, w2) -> {
                String n = et.getText().toString().trim();
                if (!n.isEmpty()) moveToFolder(a.pkg, n);
            }).show();
        }).show();
    }

    void launch(String pkg) {
        if (GAME.equals(pkg)) { startGame(); return; }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { toast("Can't open this app"); return; }
        start(i);
    }

    void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { toast("Not available on this TV"); }
    }

    // ---------- overlays ----------

    // Short fade (hardware layer, so it's just a texture blend) then hide; a cancelled fade does nothing.
    void fadeOut(View v, int hidden) {
        v.animate().cancel();
        v.animate().alpha(0f).translationY(0f).setDuration(110).withLayer().setListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                v.animate().setListener(null);
                if (cancelled) return;
                v.setVisibility(hidden);
                if (v == appsOverlay) grid.removeAllViews(); // free hidden tiles
                else if (v == recentOverlay) recentRow.removeAllViews();
            }
        }).start();
    }

    void showOverlay(View o, boolean blur) {
        boolean fresh = o.getVisibility() != View.VISIBLE;
        for (View v : overlays) if (v != o && v.getVisibility() == View.VISIBLE) fadeOut(v, View.GONE);
        if (home.getVisibility() == View.VISIBLE) fadeOut(home, View.INVISIBLE);
        header.setVisibility(o == appsOverlay || o == recentOverlay ? View.VISIBLE : View.GONE);
        o.animate().cancel();
        if (fresh) { o.setAlpha(0f); o.setTranslationY(px(14)); }
        o.setVisibility(View.VISIBLE);
        o.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).withLayer().setListener(null).start();
        if (Build.VERSION.SDK_INT >= 31)
            wall.setRenderEffect(blur ? RenderEffect.createBlurEffect(px(18), px(18), Shader.TileMode.CLAMP) : null);
    }

    void closeOverlays() { closeOverlays(true); }

    void closeOverlays(boolean refocus) {
        boolean wasOpen = home.getVisibility() != View.VISIBLE || home.getAlpha() < 1f;
        for (View v : overlays) if (v.getVisibility() == View.VISIBLE) fadeOut(v, View.GONE);
        home.animate().cancel();
        home.setVisibility(View.VISIBLE);
        if (wasOpen) {
            home.setAlpha(0f);
            home.animate().alpha(1f).setDuration(200).withLayer().setListener(null).start();
        }
        header.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31) wall.setRenderEffect(null);
        openFolder = null;
        if (wasOpen && refocus) focusFirstFav();
    }

    void focusFirst(ViewGroup g) { if (g.getChildCount() > 0) g.getChildAt(0).requestFocus(); }

    void focusFirstFav() {
        if (favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
        else { View a = findViewById(R.id.actApps); (a != null ? a : home).requestFocus(); }
    }

    void showApps() {
        renderGrid(null);
        showOverlay(appsOverlay, false);
        ((View) grid.getParent()).scrollTo(0, 0);
        focusFirst(grid);
    }

    // ---------- recent apps ----------

    void showRecent() {
        recentRow.removeAllViews();
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        List<UsageStats> stats = um == null ? null : um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 86_400_000L, now);
        if (stats != null) for (UsageStats u : stats)
            if (apps.containsKey(u.getPackageName()) && u.getLastTimeUsed() > 0)
                last.merge(u.getPackageName(), u.getLastTimeUsed(), Math::max);
        List<String> order = new ArrayList<>(last.keySet());
        order.sort((x, y) -> Long.compare(last.get(y), last.get(x)));
        for (String p : order.subList(0, Math.min(12, order.size()))) recentRow.addView(recentCard(apps.get(p)));
        recentHint.setText(order.isEmpty() ? "No recent apps yet" : "Hold OK on an app to close it  ·  OK to open");
        updateRam();
        showOverlay(recentOverlay, false);
        if (recentRow.getChildCount() > 0) ((ViewGroup) recentRow.getChildAt(0)).getChildAt(1).requestFocus();
        else findViewById(R.id.actOptimize).requestFocus();
    }

    View recentCard(App a) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        TextView name = label(a.label, 13);
        col.addView(name, new LinearLayout.LayoutParams(px(240), -2));
        View t = tile(a);
        t.setOnFocusChangeListener((v, has) -> zoom(v, has, 1.08f));
        t.setOnLongClickListener(v -> { recentMenu(col, a); return true; });
        t.setOnKeyListener((v, k, e) -> {
            if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) { recentMenu(col, a); return true; }
            return false;
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(240), px(135));
        tlp.topMargin = px(4);
        col.addView(t, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(px(20));
        col.setLayoutParams(lp);
        return col;
    }

    void recentMenu(View card, App a) {
        dialog().setTitle(a.label).setItems(new String[]{"Close app", "More options…"}, (d, w) -> {
            if (w == 0) closeApp(card, a); else appMenu(a);
        }).show();
    }

    long availMem() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem;
    }

    /** Stops every launchable app that is still sitting in memory (this launcher is not one of them). */
    void optimize() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        long before = availMem();
        for (String p : apps.keySet()) am.killBackgroundProcesses(p);
        toast("Optimizing…");
        ui.postDelayed(() -> {
            updateRam();
            long freed = (availMem() - before) / 1_000_000;
            toast(freed > 20 ? "Freed " + freed + " MB" : "Memory is already clean");
        }, 1200);
    }

    void restartTv() {
        dialog().setTitle("Restart the TV?").setMessage("Everything will close and the TV will start again.")
                .setPositiveButton("Restart", (d, w) -> {
                    if (KeyService.powerMenu()) toast("Choose Restart");
                    else { toast("Turn on \"Luna TV Home button\" in Accessibility to use Restart"); start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                }).setNegativeButton("Cancel", null).show();
    }

    void closeApp(View card, App a) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        am.killBackgroundProcesses(a.pkg);
        int i = recentRow.indexOfChild(card);
        card.animate().translationY(px(120)).alpha(0f).setDuration(180).withEndAction(() -> {
            recentRow.removeView(card);
            int n = recentRow.getChildCount();
            if (n == 0) { recentHint.setText("No recent apps"); findViewById(R.id.actOptimize).requestFocus(); return; }
            ((ViewGroup) recentRow.getChildAt(Math.min(i, n - 1))).getChildAt(1).requestFocus();
        }).start();
        ui.postDelayed(this::updateRam, 700);
    }

    void updateRam() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        double used = (mi.totalMem - mi.availMem) / 1e9, total = mi.totalMem / 1e9;
        ramText.setText(String.format(Locale.US, "RAM %.1f GB used of %.1f GB", used, total));
        ramBar.setProgress((int) (100 * used / total));
    }

    // ---------- control center ----------

    void showControl() {
        ccGrid.removeAllViews();
        String net = netName();
        String bt = "Unavailable";
        try {
            BluetoothAdapter ad = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
            if (ad != null) bt = ad.isEnabled() ? "On" : "Off";
        } catch (Exception ignored) { }
        ccTile(R.drawable.ic_power, "Sleep", "Turn the TV off", v -> sleep());
        ccTile(R.drawable.ic_wifi, "Wi-Fi", net, v -> start(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        ccTile(R.drawable.ic_bt, "Bluetooth", bt, v -> start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        ccTile(R.drawable.ic_input, "Inputs", "HDMI sources", v -> showInputs());
        ccTile(R.drawable.ic_moon, "Dark mode", darkLabel(), v -> {
            String next = darkMode().equals("auto") ? "on" : darkMode().equals("on") ? "off" : "auto";
            prefs.edit().putString("dark", next).apply();
            applyDark();
            ((TextView) ((ViewGroup) ((ViewGroup) v).getChildAt(1)).getChildAt(1)).setText(darkLabel());
        });
        ccTile(R.drawable.ic_fire, "Fire pit", "Cozy standby", v -> fireplace());
        ccTile(R.drawable.ic_brightness, "Brighter", brightLabel(), v -> stepBrightness(1, v));
        ccTile(R.drawable.ic_brightness, "Dimmer", brightLabel(), v -> stepBrightness(-1, v));
        ccTile(R.drawable.ic_tv, "Screensaver", "Start now", v -> screensaver());
        ccTile(R.drawable.ic_game, "Game Mode", "Stream your PC", v -> startGame());
        ccTile(R.drawable.ic_tv, "Setup", "Run the setup again", v -> start(new Intent(this, SetupActivity.class)));
        ccTile(R.drawable.ic_image, "Wallpaper", "Change", v -> showWallpapers());
        ccTile(R.drawable.ic_home, "Home layout", layoutName(layout), v -> pickLayout());
        ccTile(R.drawable.ic_apps, "Button style", SKINS[skinIndex()][1], v -> {
            prefs.edit().putString("skin", SKINS[(skinIndex() + 1) % SKINS.length][0]).apply();
            toast("Button style: " + SKINS[skinIndex()][1]);
            recreate(); // the style is a theme, so it needs a fresh inflate
        });
        ccTile(R.drawable.ic_apps, "Icon size", SIZE_NAMES[sizeIndex()], v -> {
            prefs.edit().putInt("iconSize", (sizeIndex() + 1) % SIZES.length).apply();
            ((TextView) ((ViewGroup) ((ViewGroup) v).getChildAt(1)).getChildAt(1)).setText(SIZE_NAMES[sizeIndex()]);
            renderFavs(-1);
            renderGrid(openFolder);
            liftDockLabel();
        });
        ccTile(R.drawable.ic_sound, "Sound", "Audio settings", v -> start(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        ccTile(R.drawable.ic_settings, "Settings", "All settings", v -> openSettings());
        ccTile(R.drawable.ic_home, "Updates", updateLabel(), v -> {
            if (update != null) showUpdate(update);
            else { toast("Checking for updates..."); checkUpdate(true); }
        });
        showOverlay(controlOverlay, true);
        focusFirst(ccGrid);
    }

    void ccTile(int icon, String title, String sub, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFocusable(true);
        t.setBackgroundResource(R.drawable.cc_tile);
        t.setPadding(px(12), 0, px(10), 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(getColorStateList(R.color.pill_text));
        iv.setDuplicateParentStateEnabled(true);
        t.addView(iv, new LinearLayout.LayoutParams(px(20), px(20)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(10), 0, 0, 0);
        col.setDuplicateParentStateEnabled(true);
        for (int i = 0; i < 2; i++) {
            TextView tv = new TextView(this);
            tv.setText(i == 0 ? title : sub);
            tv.setTextSize(i == 0 ? 13 : 11);
            tv.setTextColor(getColorStateList(R.color.pill_text));
            tv.setAlpha(i == 0 ? 1f : 0.7f);
            tv.setSingleLine(true);
            tv.setDuplicateParentStateEnabled(true);
            if (i == 0) tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            col.addView(tv);
        }
        t.addView(col);
        t.setOnClickListener(l);
        t.setOnFocusChangeListener((v, f) -> zoom(v, f, 1.04f));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = px(156);
        lp.height = px(54);
        lp.setMargins(px(5), px(5), px(5), px(5));
        ccGrid.addView(t, lp);
    }

    // ---------- updates (GitHub releases, see Updater) ----------

    Updater.Release update; // the newer release found by the last check, else null
    long updTry;

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    String updateLabel() { return update != null ? "v" + update.version + " available" : "Up to date · v" + version(); }

    /** On Home resume, at most every 6 hours; a new version pops the "What's new" modal once, the Control Center tile stays. */
    void autoCheckUpdate() {
        long now = System.currentTimeMillis();
        if (now - updTry < 6 * 3_600_000L) return;
        updTry = now;
        checkUpdate(false);
    }

    void checkUpdate(boolean manual) {
        bg.execute(() -> {
            try {
                Updater.Release r = Updater.latest();
                boolean fresh = r != null && Updater.newer(r.version, version());
                ui.post(() -> {
                    update = fresh ? r : null;
                    setTileSub("Updates", updateLabel());
                    if (fresh && (manual || !r.version.equals(prefs.getString("updSeen", "")))) {
                        prefs.edit().putString("updSeen", r.version).apply();
                        showUpdate(r);
                    } else if (manual) toast("Luna TV is up to date (v" + version() + ")");
                });
            } catch (Exception e) {
                if (manual) ui.post(() -> toast("Couldn't check for updates"));
            }
        });
    }

    void setTileSub(String title, String sub) {
        for (int i = 0; i < ccGrid.getChildCount(); i++) {
            ViewGroup col = (ViewGroup) ((ViewGroup) ccGrid.getChildAt(i)).getChildAt(1);
            if (((TextView) col.getChildAt(0)).getText().toString().equals(title)) ((TextView) col.getChildAt(1)).setText(sub);
        }
    }

    /** "What's new" modal with the release notes; Update now downloads and hands over to the system installer. */
    void showUpdate(Updater.Release r) {
        String notes = r.notes.isEmpty() ? "No release notes." : r.notes
                .replaceAll("(?m)^#+\\s*", "").replace("**", "").replaceAll("(?m)^[-*]\\s+", "•  ");
        TextView body = label(notes, 14);
        body.setSingleLine(false);
        body.setEllipsize(null);
        body.setLineSpacing(0, 1.15f);
        body.setPadding(px(24), px(8), px(24), px(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(body);
        dialog().setTitle("What's new in v" + r.version + "  (you have v" + version() + ")").setView(sv)
                .setPositiveButton("Update now", (d, w) -> installUpdate(r))
                .setNegativeButton("Later", null).show();
    }

    void installUpdate(Updater.Release r) {
        if (!getPackageManager().canRequestPackageInstalls()) { // build.ps1 grants this over adb; otherwise ask once
            toast("Allow Luna TV to install apps, then try again");
            start(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setPadding(px(24), px(16), px(24), px(16));
        AlertDialog dlg = dialog().setTitle("Downloading v" + r.version).setView(bar).setCancelable(false).show();
        bg.execute(() -> {
            try {
                File apk = Updater.download(this, r, p -> ui.post(() -> bar.setProgress(p)));
                Updater.install(this, apk);
                ui.post(dlg::dismiss);
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); toast("Update failed: " + e.getMessage()); });
            }
        });
    }

    void fireplace() {
        if (!FirePitActivity.video(this).exists()) { toast("Copy " + FirePitActivity.FILE + " to the TV first (see build.ps1 -Video)"); return; }
        closeOverlays();
        startActivity(new Intent(this, FirePitActivity.class));
    }

    int brightness() { return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128); }

    String brightLabel() { return "Backlight " + Math.round(brightness() * 100 / 255f) + "%"; }

    /** Real TV backlight (needs "modify system settings", which build.ps1 grants over adb). Never goes fully dark. */
    void stepBrightness(int dir, View tile) {
        if (!Settings.System.canWrite(this)) {
            toast("Allow Luna TV to modify system settings");
            start(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        int b = Math.max(15, Math.min(255, brightness() + dir * 26));
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, b);
        for (int i = 0; i < ccGrid.getChildCount(); i++) { // both brightness tiles show the level
            ViewGroup col = (ViewGroup) ((ViewGroup) ccGrid.getChildAt(i)).getChildAt(1);
            String title = ((TextView) col.getChildAt(0)).getText().toString();
            if (title.equals("Brighter") || title.equals("Dimmer")) ((TextView) col.getChildAt(1)).setText(brightLabel());
        }
    }

    void sleep() {
        closeOverlays();
        if (KeyService.sleep()) return;
        toast("Turn on \"Luna TV Home button\" in Accessibility to use Sleep");
        start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    void screensaver() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
        } catch (Exception e) {
            start(new Intent(Settings.ACTION_DREAM_SETTINGS));
        }
    }

    String darkMode() { return prefs.getString("dark", "auto"); }

    String darkLabel() {
        String m = darkMode();
        return m.equals("on") ? "On" : m.equals("off") ? "Off" : "Auto · 7pm to 7am";
    }

    void applyDark() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String m = darkMode();
        boolean dark = m.equals("on") || (m.equals("auto") && (h >= 19 || h < 7));
        dim.setVisibility(dark ? View.VISIBLE : View.GONE);
        // the dim layer sits under the home content, so dim the app rows and buttons themselves
        float a = dark ? 0.72f : 1f;
        for (int id : new int[]{R.id.favRow, R.id.continueRow, R.id.actApps, R.id.actInputs, R.id.actGame, R.id.actWall,
                R.id.actSettings, R.id.actRecent, R.id.actSleep, R.id.contTile}) {
            View v = findViewById(id);
            if (v != null) v.setAlpha(a);
        }
    }

    // ---------- continue watching ----------

    void loadContinue() {
        bg.execute(() -> {
            List<String[]> rows = new ArrayList<>();
            String[] cols = {TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
                    TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                    TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                    TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS};
            try (Cursor c = getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                    TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
                while (c != null && c.moveToNext() && rows.size() < 10) {
                    if (getPackageName().equals(c.getString(0)) || c.getString(4) == null) continue;
                    String[] r = new String[7];
                    for (int i = 0; i < 7; i++) r[i] = c.getString(i);
                    rows.add(r);
                }
            } catch (Exception ignored) { } // no access on this TV: the row stays hidden
            ui.post(() -> renderContinue(rows));
        });
    }

    void renderContinue(List<String[]> rows) {
        contRows = rows;
        if (contTile != null) { // Bento: the first item's poster fills its tile
            ((ImageView) ((ViewGroup) contTile).getChildAt(0)).setImageDrawable(null);
            if (!rows.isEmpty() && rows.get(0)[3] != null) loadThumb(contTile, poster(rows.get(0)[3]), 640);
        }
        updateHero();
        if (continueRow == null) return;
        continueRow.removeAllViews();
        continueBox.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        int[] cc = contCard();
        for (String[] r : rows) {
            String title = r[2] != null ? r[1] + " · " + r[2] : r[1];
            FrameLayout f = baseTile(title);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(this);
            shade.setBackgroundResource(R.drawable.scrim_bottom);
            f.addView(shade, new FrameLayout.LayoutParams(-1, px(40), Gravity.BOTTOM));
            TextView tv = label(title, 10);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tlp.bottomMargin = px(4);
            f.addView(tv, tlp);
            long pos = parse(r[5]), dur = parse(r[6]);
            if (pos > 0 && dur > 0) {
                View bar = new View(this);
                bar.setBackgroundColor(Color.WHITE);
                f.addView(bar, new FrameLayout.LayoutParams((int) (px(cc[0]) * Math.min(1.0, pos / (double) dur)), px(3), Gravity.BOTTOM));
            }
            f.setOnClickListener(v -> resume(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(cc[0]), px(cc[1]));
            lp.setMarginEnd(px(12));
            continueRow.addView(f, lp);
            if (r[3] != null) loadThumb(f, poster(r[3]));
        }
    }

    Bytes poster(String s) {
        return () -> {
            Uri u = Uri.parse(s);
            if (u.getScheme() != null && u.getScheme().startsWith("http")) return http(s);
            try (InputStream in = getContentResolver().openInputStream(u)) { return readAll(in); }
        };
    }

    void resume(String[] r) {
        try { start(Intent.parseUri(r[4], Intent.URI_INTENT_SCHEME)); } catch (Exception e) { launch(r[0]); }
    }

    void resumeFirst() { if (!contRows.isEmpty()) resume(contRows.get(0)); }

    /** Bento tile: what to resume, or the greeting over an animated weather scene when there is nothing. */
    void updateHero() {
        String[] r = contRows.isEmpty() ? null : contRows.get(0);
        String title = r != null ? r[1] : greetText();
        String sub = r != null ? episodeLine(r) : weatherText;
        if (contTile != null) {
            contTitle.setText(title);
            contSub.setText(sub);
            contTile.setFocusable(r != null);
            wxScene.setVisibility(r == null ? View.VISIBLE : View.GONE);
        }
    }

    void applyWx() { if (wxScene != null) wxScene.set(wxCode, wxDay); }

    /** Bento: the Game Mode tile wears the cover of the game last played on the PC (cached, so it shows with the PC off too). */
    void loadLastGame() {
        if (gameArt == null) return;
        File img = new File(getFilesDir(), "lastgame.jpg");
        if (gameArt.getDrawable() == null && img.exists()) bg.execute(() -> {
            try {
                Bitmap bm = decode(Files.readAllBytes(img.toPath()), 600);
                ui.post(() -> showGame(bm, prefs.getString("lastGame", "")));
            } catch (Exception ignored) { }
        });
        long now = System.currentTimeMillis();
        if (now - lastGameAt < 5 * 60_000) return;
        lastGameAt = now;
        bg.execute(() -> {
            try {
                String ip = prefs.getString("pc_ip", "");
                if (ip.isEmpty()) return; // Setup hasn't found the PC yet
                String pc = "http://" + ip + ":8766"; // pc/game-mode/last-game.py, always on
                JSONObject g = new JSONObject(new String(http(pc + "/api/last")));
                if (!g.has("art")) return;
                String id = g.getString("art"), name = g.getString("name");
                if ((id + name).equals(prefs.getString("lastGameKey", "")) && img.exists()) return;
                byte[] data;
                try { data = http((g.optBoolean("local") ? pc + "/art/" : "https://cdn.cloudflare.steamstatic.com/steam/apps/") + id + "/library_600x900.jpg"); }
                catch (Exception e) { data = http("https://cdn.cloudflare.steamstatic.com/steam/apps/" + id + "/header.jpg"); }
                Bitmap bm = decode(data, 600);
                if (bm == null) return;
                try (FileOutputStream out = new FileOutputStream(img)) { out.write(data); }
                prefs.edit().putString("lastGameKey", id + name).putString("lastGame", name).apply();
                ui.post(() -> showGame(bm, name));
            } catch (Exception e) { android.util.Log.w("Bearacle", "last game", e); } // PC is off: keep the cached cover
        });
    }

    void showGame(Bitmap bm, String name) {
        gameArt.setImageBitmap(bm);
        if (!name.isEmpty()) gameSub.setText("Last played: " + name);
    }

    static String episodeLine(String[] r) {
        long pos = parse(r[5]), dur = parse(r[6]);
        String ep = r[2] != null ? r[2] : "", left = pos > 0 && dur > pos ? (dur - pos) / 60_000 + " min left" : "";
        return ep.isEmpty() || left.isEmpty() ? ep + left : ep + "  ·  " + left;
    }

    static long parse(String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }

    // ---------- inputs, settings, game ----------

    void showInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (tim != null) for (TvInputInfo in : tim.getTvInputList()) {
            if (!in.isPassthroughInput() || in.isHidden(this)) continue;
            CharSequence l = in.loadCustomLabel(this);
            if (TextUtils.isEmpty(l)) l = in.loadLabel(this);
            list.add(in);
            names.add(l.toString());
        }
        if (list.isEmpty()) { launch("com.mediatek.tv.oneworld.tvcenter"); return; }
        dialog().setTitle("Inputs").setItems(names.toArray(new String[0]), (d, w) ->
                start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(list.get(w).getId()))))
                .show();
    }

    void openSettings() { start(new Intent(Settings.ACTION_SETTINGS)); }

    void startGame() { startActivity(new Intent(this, GameBootActivity.class)); }

    void gameMenu() {
        String[] labels = {"Game Mode (console menu)", "Steam Big Picture", "Desktop", "Just open Moonlight"};
        String[] values = {"Game Mode", "Steam Big Picture", "Desktop", ""};
        dialog().setTitle("Game Mode streams…").setItems(labels, (d, w) -> {
            prefs.edit().putString("game_app", values[w]).apply();
            toast("Game Mode → " + labels[w]);
        }).show();
    }

    // ---------- wallpaper ----------

    File wallFile() { return new File(getFilesDir(), "wall.jpg"); }

    void applyStoredWallpaper() {
        String mode = prefs.getString("wall", "nature");
        if (mode.startsWith("grad:")) { wall.setImageDrawable(gradient(Integer.parseInt(mode.substring(5)))); return; }
        wall.setImageDrawable(gradient(0));
        if (!wallFile().exists()) return;
        bg.execute(() -> {
            try {
                Bitmap bm = decode(Files.readAllBytes(wallFile().toPath()), 1920);
                ui.post(() -> wall.setImageBitmap(bm));
            } catch (Exception ignored) { }
        });
    }

    GradientDrawable gradient(int i) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, GRADIENTS[i % GRADIENTS.length]);
    }

    /** Fetch today's photo for a daily mode ("nature" or "bing") and make it the wallpaper. */
    void setDaily(String mode, boolean say) {
        String day = today();
        bg.execute(() -> {
            try {
                String[] pick = mode.equals("nature") ? natureToday() : null;
                byte[] data = pick != null ? http(pick[0]) : http("https://www.bing.com" + bingImages(1).getJSONObject(0).getString("url"));
                saveWallpaper(data, mode);
                prefs.edit().putString("bingDay", day).apply(); // only a success counts: a failed try (network not up after wake) is retried
                if (say && pick != null) ui.post(() -> toast(pick[1]));
            } catch (Exception e) { android.util.Log.w("Bearacle", "daily wallpaper", e); }
        });
    }

    /** Daily photo modes: fetch today's photo if the current one is older (retries every 2 min until it works). */
    void checkDaily() {
        long now = System.currentTimeMillis();
        String mode = prefs.getString("wall", "nature");
        if (!(mode.equals("nature") || mode.equals("bing")) || today().equals(prefs.getString("bingDay", "")) || now - wallTry < 120_000) return;
        wallTry = now;
        setDaily(mode, false);
    }

    // Wikimedia Commons "featured pictures": landscapes first, then birds and other wildlife. No account or key needed.
    static final String[] NATURE = {"landscapes", "mountains", "lakes", "beaches", "waterfalls", "forests", "sunsets", "nature",
            "birds_in_flight", "animals_in_flight", "Passeriformes", "Galliformes", "Anseriformes", "Cetacea", "Crocodilia", "Testudines", "Squamata"};

    /** Wide JPEGs of at least 1920 px from those categories, as {1920 px url, title}; kept on the TV for a month. */
    List<String[]> natureList() throws Exception {
        File cache = new File(getFilesDir(), "nature.txt");
        List<String[]> out = new ArrayList<>();
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 30L * 86_400_000) {
            for (String ln : new String(Files.readAllBytes(cache.toPath()), "UTF-8").split("\n")) {
                String[] p = ln.split("\t", 2);
                if (p.length == 2) out.add(p);
            }
            if (!out.isEmpty()) return out;
        }
        Map<String, String[]> found = new HashMap<>();
        for (String c : NATURE) {
            try {
                JSONObject pages = new JSONObject(new String(http("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers"
                        + "&gcmtitle=Category:Featured_pictures_of_" + c + "&gcmtype=file&gcmlimit=500&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=1920"), "UTF-8"))
                        .getJSONObject("query").getJSONObject("pages");
                for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
                    JSONObject page = pages.getJSONObject(it.next());
                    JSONObject ii = page.getJSONArray("imageinfo").getJSONObject(0);
                    int w = ii.getInt("width"), h = ii.getInt("height");
                    if (!"image/jpeg".equals(ii.optString("mime")) || w < 1920 || w < h * 1.4 || w > h * 2.3) continue; // fills a TV screen
                    String title = page.getString("title").replaceFirst("^File:", "").replaceAll("\\.\\w+$", "").replace('_', ' ');
                    found.put(ii.getString("thumburl"), new String[]{ii.getString("thumburl"), title});
                }
            } catch (Exception ignored) { } // one category failing doesn't stop the rest
        }
        out.addAll(found.values());
        if (out.isEmpty()) throw new Exception("no nature photos");
        StringBuilder sb = new StringBuilder();
        for (String[] p : out) sb.append(p[0]).append('\t').append(p[1]).append('\n');
        Files.write(cache.toPath(), sb.toString().getBytes("UTF-8"));
        return out;
    }

    /** One photo per day: the same list order on every TV, shuffled once, walked one step a day (no repeats until it's used up). */
    String[] natureToday() throws Exception {
        List<String[]> l = natureList();
        l.sort((a, b) -> a[0].compareTo(b[0]));
        Collections.shuffle(l, new Random(7));
        long now = System.currentTimeMillis(), day = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
        return l.get((int) (day % l.size()));
    }

    JSONArray bingImages(int n) throws Exception {
        return new JSONObject(new String(http("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=" + n + "&mkt=en-US")))
                .getJSONArray("images");
    }

    /** Background thread: store bytes as the wallpaper and show it. */
    void saveWallpaper(byte[] data, String mode) throws Exception {
        Bitmap bm = decode(data, 1920);
        if (bm == null) throw new Exception("bad image");
        try (FileOutputStream out = new FileOutputStream(wallFile())) { out.write(data); }
        prefs.edit().putString("wall", mode).apply();
        ui.post(() -> wall.setImageBitmap(bm));
    }

    void showWallpapers() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1);
        wallRow.removeAllViews();
        showOverlay(wallOverlay, false);

        View nature = wallTile("Nature daily", null, -1);
        nature.setOnClickListener(v -> { setDaily("nature", true); toast("Nature photo of the day"); });
        nature.requestFocus();
        bg.execute(() -> {
            try { String[] n = natureToday(); loadThumb(nature, () -> http(n[0].replace("/1920px-", "/500px-"))); } catch (Exception ignored) { }
        });
        View daily = wallTile("Bing daily", null, -1);
        daily.setOnClickListener(v -> { setDaily("bing", false); toast("Bing photo of the day"); });
        for (int i = 0; i < GRADIENTS.length; i++) {
            int gi = i;
            View t = wallTile(null, gradient(i), -1);
            t.setOnClickListener(v -> {
                prefs.edit().putString("wall", "grad:" + gi).apply();
                wall.setImageDrawable(gradient(gi));
            });
        }
        for (File f : localImages()) {
            View t = wallTile(f.getName(), null, -1);
            loadThumb(t, () -> Files.readAllBytes(f.toPath()));
            t.setOnClickListener(v -> bg.execute(() -> {
                try { saveWallpaper(Files.readAllBytes(f.toPath()), "file"); } catch (Exception e) { ui.post(() -> toast("Can't read image")); }
            }));
        }
        bg.execute(() -> {
            try {
                JSONArray imgs = bingImages(8);
                ui.post(() -> {
                    for (int i = 0; i < imgs.length(); i++) {
                        String url = "https://www.bing.com" + imgs.optJSONObject(i).optString("url");
                        View t = wallTile(i == 0 ? "Today" : null, null, 1 + i);
                        loadThumb(t, () -> http(url));
                        t.setOnClickListener(v -> bg.execute(() -> {
                            try { saveWallpaper(http(url), "file"); } catch (Exception e) { ui.post(() -> toast("Download failed")); }
                        }));
                    }
                    loadThumb(daily, () -> http("https://www.bing.com" + imgs.optJSONObject(0).optString("url")));
                });
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (wallOverlay.getVisibility() == View.VISIBLE && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED)
            showWallpapers();
    }

    List<File> localImages() {
        List<File> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        for (String dir : new String[]{"Wallpapers", "Pictures", "Download", "DCIM"}) {
            File[] fs = new File(root, dir).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
            }
        }
        return out;
    }

    View wallTile(String text, Drawable d, int at) {
        FrameLayout f = baseTile(text);
        f.setOnFocusChangeListener((v, has) -> zoom(v, has, 1.1f));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (d != null) iv.setImageDrawable(d);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (text != null) f.addView(label(text, 11), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(160), px(90));
        lp.setMarginEnd(px(14));
        wallRow.addView(f, at, lp);
        return f;
    }

    interface Bytes { byte[] get() throws Exception; }

    /** Decodes src off the main thread into the tile's first child (an ImageView). */
    void loadThumb(View tile, Bytes src) { loadThumb(tile, src, 320); }

    void loadThumb(View tile, Bytes src, int maxW) {
        bg.execute(() -> {
            try {
                Bitmap bm = decode(src.get(), maxW);
                ui.post(() -> ((ImageView) ((ViewGroup) tile).getChildAt(0)).setImageBitmap(bm));
            } catch (Exception ignored) { }
        });
    }

    // ---------- greeting & weather ----------

    String greetText() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "Good Night" : h < 12 ? "Good Morning" : h < 18 ? "Good Afternoon" : h < 22 ? "Good Evening" : "Good Night";
    }

    void updateGreeting() {
        setText(greeting, greetText());
        updateHero();
    }

    void refreshWeather() {
        long now = System.currentTimeMillis();
        if (now - weatherAt < 30 * 60_000) return;
        weatherAt = now;
        bg.execute(() -> {
            try {
                JSONObject loc = new JSONObject(new String(http("http://ip-api.com/json/?fields=lat,lon")));
                JSONObject w = new JSONObject(new String(http(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day"
                                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                        loc.getDouble("lat"), loc.getDouble("lon")))));
                JSONObject c = w.getJSONObject("current");
                JSONObject d = w.getJSONObject("daily");
                String s = wx(c.getInt("weather_code")) + "  " + Math.round(c.getDouble("temperature_2m")) + "°C  (H "
                        + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "° / L "
                        + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°)";
                int code = c.getInt("weather_code");
                boolean isDay = c.optInt("is_day", 1) == 1;
                prefs.edit().putString("wxText", s).putInt("wxCode", code).putBoolean("wxDay", isDay).apply();
                ui.post(() -> { weatherText = s; wxCode = code; wxDay = isDay; setText(weather, s); applyWx(); updateHero(); });
            } catch (Exception e) {
                android.util.Log.w("Bearacle", "weather", e);
                weatherAt = 0; // retry on next resume
            }
        });
    }

    static String wx(int c) {
        if (c == 0) return "☀️ Clear";
        if (c <= 2) return "🌤️ Partly cloudy";
        if (c == 3) return "☁️ Cloudy";
        if (c <= 48) return "🌫️ Fog";
        if (c <= 57) return "🌦️ Drizzle";
        if (c <= 67) return "🌧️ Rain";
        if (c <= 77) return "❄️ Snow";
        if (c <= 82) return "🌦️ Showers";
        if (c <= 86) return "🌨️ Snow showers";
        return "⛈️ Thunderstorm";
    }

    // ---------- home layouts ----------

    int layoutRes() {
        switch (layout) {
            case "dock": return R.layout.layout_dock;
            case "bento": return R.layout.layout_bento;
            case "bottom": return R.layout.layout_bottom;
            default: return R.layout.activity_main;
        }
    }

    /** Width, height and gap (dp) of the app tiles on Home. */
    int[] favTile() {
        int[] t;
        switch (layout) {
            case "dock": t = new int[]{96, 54, 10}; break;
            case "bento": t = new int[]{128, 72, 10}; break;
            case "bottom": t = new int[]{128, 72, 12}; break;
            default: t = new int[]{112, 63, 12};
        }
        t[0] = Math.round(t[0] * iconScale());
        t[1] = Math.round(t[1] * iconScale());
        return t;
    }

    /** The dock grows with the icon size, so lift its name label (and the focused tile's zoom) clear of it. */
    void liftDockLabel() {
        if (dockLabel == null) return;
        FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dockLabel.getLayoutParams();
        dl.bottomMargin = px(136 + Math.round((favTile()[1] - 54) * 1.1f));
        dockLabel.setLayoutParams(dl);
    }

    int sizeIndex() { return Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("iconSize", 2))); }

    float iconScale() { return SIZES[sizeIndex()]; }

    int skinIndex() {
        String k = prefs.getString("skin", "default");
        for (int i = 0; i < SKINS.length; i++) if (SKINS[i][0].equals(k)) return i;
        return 0;
    }

    /** Width and height (dp) of a Continue Watching card. */
    int[] contCard() { return layout.equals("bottom") ? new int[]{144, 81} : new int[]{160, 90}; }

    View favScroll() { return findViewById(R.id.favScroll); }

    /** Apps and Recent are laid out for the classic header; move them clear of this layout's own nav. */
    void tweakNav() {
        int dy, bottom = 0;
        switch (layout) {
            case "dock": case "bento": dy = -64; break;
            case "bottom": dy = -64; bottom = 84; break;
            default: return;
        }
        View h = findViewById(R.id.navHost);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.getLayoutParams();
        lp.topMargin = px(dy);
        h.setLayoutParams(lp);
        h.setPaddingRelative(0, 0, 0, px(bottom));
    }

    static String layoutName(String key) {
        for (String[] l : LAYOUTS) if (l[0].equals(key)) return l[1];
        return null;
    }

    /** One card per layout, each with a wireframe of it; OK picks, Back cancels. */
    void pickLayout() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setClipToPadding(false); // the focused card grows into the padding
        row.setClipChildren(false);
        row.setPadding(px(12), px(20), px(12), px(22)); // room for the focused card to grow
        AlertDialog dlg = dialog().setTitle("Home layout").setView(row).create();
        View current = null;
        for (String[] l : LAYOUTS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setBackgroundResource(R.drawable.bento_tile);
            card.setPadding(px(8), px(8), px(8), px(8));
            card.addView(new LayoutPreview(this, l[0]), new LinearLayout.LayoutParams(px(180), px(101)));
            boolean cur = l[0].equals(layout);
            TextView name = label((cur ? "✓  " : "") + l[1], 14);
            name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            name.setPadding(0, px(8), 0, 0);
            card.addView(name);
            TextView sub = label(l[2], 11);
            sub.setSingleLine(false);
            sub.setAlpha(0.7f);
            sub.setPadding(0, 0, 0, 0);
            card.addView(sub);
            card.setOnFocusChangeListener((v, f) -> zoom(v, f, 1.04f));
            card.setOnClickListener(v -> {
                dlg.dismiss();
                if (cur) return;
                prefs.edit().putString("layout", l[0]).apply();
                toast("Home layout: " + l[1]);
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(px(7), 0, px(7), 0);
            row.addView(card, lp);
            if (cur) current = card;
        }
        dlg.show();
        dlg.getWindow().setLayout(px(900), -2); // wide enough for all the cards
        if (current != null) current.requestFocus();
    }

    String netName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null) return nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Connected";
        } catch (Exception ignored) { }
        return "Not connected";
    }

    void updateNet() { setText(findViewById(R.id.netLabel), netName()); }

    /** Live sub-lines of the Bento tiles. */
    void updateTileInfo() {
        TextView t = findViewById(R.id.recentSub);
        if (t != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            t.setText(String.format(Locale.US, "RAM %.1f / %.1f GB", (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9));
        }
        setText(findViewById(R.id.appsSub), apps.size() + " installed");
    }

    // ---------- helpers ----------

    // Not every home layout has every button, so a missing view is simply skipped.
    void click(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    static void setText(TextView t, CharSequence s) { if (t != null) t.setText(s); }

    boolean tabbing;
    // Run `open` when the nav pill gets focus, then take focus back so the pills stay navigable.
    void tab(int id, Runnable open) {
        View pill = findViewById(id);
        if (pill != null) pill.setOnFocusChangeListener((v, f) -> {
            if (!f || tabbing) return;
            tabbing = true;
            open.run();
            v.requestFocus();
            tabbing = false;
        });
    }

    // Up (Down on the bottom bar) from the Apps/Recent content goes to that screen's own pill, not whichever pill is geometrically nearest
    // (which would switch screens).
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (moving != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { // reorder mode owns the d-pad
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) moveFav(-1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) moveFav(1);
                else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) endMove();
            }
            return true;
        }
        View f = getCurrentFocus();
        int toNav = layout.equals("bottom") ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == toNav && f != null) {
            View mine = appsOverlay.getVisibility() == View.VISIBLE ? findViewById(R.id.navApps)
                    : recentOverlay.getVisibility() == View.VISIBLE ? findViewById(R.id.navRecent) : null;
            View up = mine == null ? null : f.focusSearch(toNav == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP);
            if (up != null && up != mine && up.getParent() != null && header.findViewById(up.getId()) == up) {
                mine.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    void zoom(View v, boolean focused, float s) {
        v.animate().scaleX(focused ? s : 1f).scaleY(focused ? s : 1f).setDuration(140).start();
    }

    AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static byte[] http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "LunaTV/2.0");
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    /** Decode with power-of-two downsampling so the width stays >= maxW (saves RAM on this 2 GB TV). */
    static Bitmap decode(byte[] b, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        o.inSampleSize = s;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }
}
