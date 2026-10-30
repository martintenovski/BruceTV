package com.bearacle.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * First-time setup: welcome, home layout, then (optionally) Game Mode: Moonlight, finding the gaming PC on the home
 * network (its always-on helper, port 8766), pairing, and how many games it found. Runs by itself on a fresh install,
 * and from Control Center > Setup. Started with "game" it opens at the Game Mode part (Game Mode used before it was set up).
 */
public class SetupActivity extends Activity {

    static final int WELCOME = 0, LAYOUT = 1, GAME = 2, MOONLIGHT = 3, FIND = 4, PAIR = 5, GAMES = 6, DONE = 7, HOME = 8;
    static final String MOONLIGHT_PKG = "com.limelight";

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newCachedThreadPool();
    final ArrayDeque<Integer> history = new ArrayDeque<>();
    SharedPreferences prefs;
    LinearLayout box;
    int step, scanGen;
    boolean gameOnly;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean("setup_started", true).apply();
        gameOnly = getIntent().getBooleanExtra("game", false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(px(40), px(24), px(40), px(24));
        box.setClipChildren(false); // a focused button grows a little
        scroll.setClipChildren(false);
        scroll.addView(box, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        go(gameOnly ? GAME : WELCOME, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (step == MOONLIGHT || step == HOME) go(step, false); // back from the Play Store / Accessibility settings
    }

    @Override
    protected void onDestroy() {
        scanGen++;
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (history.isEmpty()) { finishSetup(); return; }
        go(history.pop(), false);
    }

    // ---------- pages ----------

    void go(int s, boolean remember) {
        if (remember) history.push(step);
        step = s;
        scanGen++;
        box.removeAllViews();
        switch (s) {
            case WELCOME: welcome(); break;
            case LAYOUT: layout(); break;
            case HOME: home(); break;
            case GAME: game(); break;
            case MOONLIGHT: moonlight(); break;
            case FIND: find(); break;
            case PAIR: pair(); break;
            case GAMES: games(); break;
            default: done();
        }
    }

    void next(int s) { go(s, true); }

    void head(String title, String body) {
        box.addView(text(title, 26, 1f, true), lp(0, 6));
        if (body != null) box.addView(text(body, 15, 0.8f, false), lp(0, 14));
    }

    void welcome() {
        box.setGravity(Gravity.CENTER);
        head("Welcome to Bruce TV", "Let's set up your TV. It takes about a minute, and you can run it again any time from Control Center › Setup.");
        row(button("Start", () -> next(LAYOUT)), button("Skip for now", () -> finishSetup()));
    }

    void layout() {
        box.setGravity(Gravity.CENTER);
        head("Pick your home screen", "You can change it later in Control Center › Home layout.");
        LinearLayout cards = new LinearLayout(this);
        cards.setGravity(Gravity.CENTER_HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        cards.setPadding(0, px(14), 0, px(14));
        String cur = prefs.getString("layout", "dock");
        View focus = null;
        for (String[] l : MainActivity.LAYOUTS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setBackgroundResource(R.drawable.bento_tile);
            card.setPadding(px(8), px(8), px(8), px(8));
            card.addView(new LayoutPreview(this, l[0]), new LinearLayout.LayoutParams(px(150), px(84)));
            TextView name = text((l[0].equals(cur) ? "✓  " : "") + l[1], 14, 1f, true);
            name.setPadding(0, px(8), 0, 0);
            card.addView(name);
            card.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(140).start());
            card.setOnClickListener(v -> { prefs.edit().putString("layout", l[0]).apply(); next(HOME); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(px(7), 0, px(7), 0);
            cards.addView(card, lp);
            if (l[0].equals(cur)) focus = card;
        }
        box.addView(cards);
        if (focus != null) focus.requestFocus();
    }

    /** The remote's Home button only opens Bruce TV while its accessibility service is on; apps can't switch it on themselves, so ask and open the screen. */
    boolean homeKeyOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/");
    }

    void home() {
        box.setGravity(Gravity.CENTER);
        if (homeKeyOn()) {
            head("The Home button opens Bruce TV \u2713", "Pressing Home on the remote brings you back here.");
            row(button("Next", () -> next(GAME)));
            return;
        }
        head("Make the Home button open Bruce TV", "Google TV insists on its own home screen. To bring yours back, turn on \u201cBruce TV Home button\u201d in Accessibility. "
                + "Bruce TV only uses it for the Home button, Sleep and Restart.\n\nOpen Accessibility, pick \u201cBruce TV Home button\u201d and switch it On.");
        row(button("Open Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), button("Skip", () -> next(GAME)));
    }

    void game() {
        box.setGravity(Gravity.CENTER);
        head("Play your PC games on this TV", "Game Mode streams games from your gaming PC with Moonlight, so a console-style menu opens on the TV.\n\n"
                + "The PC needs Apollo (the streaming host) and Bruce TV's PC setup, which finds your games. Run pc\\setup.ps1 on the PC first if you haven't.");
        row(button("Set up Game Mode", () -> next(MOONLIGHT)), button(gameOnly ? "Not now" : "Not now, finish", () -> finishSetup()));
    }

    boolean hasMoonlight() {
        return getPackageManager().getLaunchIntentForPackage(MOONLIGHT_PKG) != null
                || getPackageManager().getLeanbackLaunchIntentForPackage(MOONLIGHT_PKG) != null;
    }

    void moonlight() {
        box.setGravity(Gravity.CENTER);
        if (hasMoonlight()) {
            head("Moonlight is installed ✓", "Moonlight is the app that shows the stream from your PC.");
            row(button("Next", () -> next(FIND)));
            return;
        }
        head("Install Moonlight", "Moonlight is the free app that shows the stream from your PC. Get it from the Play Store, then come back here.");
        row(button("Open the Play Store", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + MOONLIGHT_PKG))); }
            catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + MOONLIGHT_PKG))); }
        }), button("Check again", () -> go(MOONLIGHT, false)), button("Skip", () -> next(FIND)));
    }

    void find() {
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        head("Find your gaming PC", null);
        TextView status = text("", 15, 0.8f, false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        box.addView(status, lp(0, 12));
        box.addView(list, lp(0, 8));
        row(button("Scan again", () -> scan(status, list)), button("Type its address", () -> askAddress(status, list)), button("Skip", () -> next(PAIR)));
        scan(status, list);
    }

    void pair() {
        box.setGravity(Gravity.CENTER);
        String name = prefs.getString("pc_name", "your PC"), ip = prefs.getString("pc_ip", "");
        head("Pair Moonlight with the PC",
                "1.  Open Moonlight and pick “" + name + "”.\n"
                        + "2.  Moonlight shows a PIN.\n"
                        + "3.  On the PC, open https://localhost:47990, go to PIN and type it in.\n\n"
                        + "If Moonlight doesn't list the PC, add it by its address" + (ip.isEmpty() ? "." : ": " + ip + "."));
        row(button("Open Moonlight", () -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(MOONLIGHT_PKG);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(MOONLIGHT_PKG);
            if (i == null) toast("Moonlight isn't installed yet"); else startActivity(i);
        }), button("I've paired it", () -> next(GAMES)), button("Skip", () -> next(GAMES)));
    }

    void games() {
        box.setGravity(Gravity.CENTER);
        head("Your games", null);
        TextView status = text("Looking at your PC's games…", 17, 1f, false);
        box.addView(status, lp(0, 8));
        box.addView(text("Missing one? In Game Mode press Menu › Settings › Add a game to point to it, or scan again for new ones.", 13, 0.65f, false), lp(0, 14));
        row(button("Scan again", () -> {
            status.setText("Scanning…");
            bg.execute(() -> { try { post(url("/api/rescan")); Thread.sleep(5000); } catch (Exception ignored) { } ui.post(() -> showSummary(status)); });
        }), button("Next", () -> next(DONE)));
        showSummary(status);
    }

    void done() {
        box.setGravity(Gravity.CENTER);
        head("All set", "Start Game Mode from the Game Mode tile or the Game Mode app, and change your home screen any time in Control Center.");
        row(button("Try Game Mode now", () -> { finishSetup(); startActivity(new Intent(this, GameBootActivity.class)); }),
                button("Finish", () -> finishSetup()));
    }

    void finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply();
        finish();
    }

    // ---------- finding the PC ----------

    void scan(TextView status, LinearLayout list) {
        int gen = ++scanGen;
        list.removeAllViews();
        String prefix = localPrefix();
        if (prefix == null) { status.setText("The TV isn't on a network. Connect it to your home Wi-Fi first."); return; }
        status.setText("Looking for your PC on this network…");
        ExecutorService pool = Executors.newFixedThreadPool(48);
        AtomicInteger left = new AtomicInteger(254);
        for (int i = 1; i < 255; i++) {
            String ip = prefix + i;
            pool.execute(() -> {
                String name = hello(ip);
                ui.post(() -> {
                    if (gen != scanGen) return;
                    if (name != null) {
                        list.addView(button(name + "   ·   " + ip, () -> choose(name, ip)), lp(0, 8));
                        if (list.getChildCount() == 1) list.getChildAt(0).requestFocus();
                    }
                    if (left.decrementAndGet() == 0) status.setText(list.getChildCount() == 0
                            ? "I couldn't find your PC. Is it on? Run pc\\setup.ps1 on it once (right-click › Run with PowerShell), then scan again."
                            : "Pick your PC:");
                });
            });
        }
        pool.shutdown();
    }

    void askAddress(TextView status, LinearLayout list) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setHint("192.168.1.x");
        e.setText(prefs.getString("pc_ip", ""));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Your PC's address").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    String ip = e.getText().toString().trim();
                    status.setText("Checking " + ip + "…");
                    bg.execute(() -> {
                        String name = hello(ip);
                        ui.post(() -> {
                            if (name != null) choose(name, ip);
                            else status.setText("Nothing answered at " + ip + ". Is the PC on, and has pc\\setup.ps1 been run on it?");
                        });
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    void choose(String name, String ip) {
        prefs.edit().putString("pc_name", name).putString("pc_ip", ip).apply();
        toast("Using " + name);
        next(PAIR);
    }

    void showSummary(TextView status) {
        bg.execute(() -> {
            String msg;
            try {
                JSONObject j = new JSONObject(get(url("/api/summary"), 4000));
                int total = j.getInt("total");
                StringBuilder sb = new StringBuilder();
                JSONObject c = j.getJSONObject("counts");
                for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                    String k = it.next();
                    sb.append(sb.length() > 0 ? "  ·  " : "").append(label(k)).append(' ').append(c.getInt(k));
                }
                msg = total == 0 ? "No games found yet.\nGame Mode can still find them: open it, then Menu › Settings › Scan for games."
                        : "Found " + total + (total == 1 ? " game" : " games") + (sb.length() > 0 ? "\n" + sb : "");
            } catch (Exception e) {
                msg = "Couldn't reach the PC. Is it on? You can finish setup and scan for games later.";
            }
            String m = msg;
            ui.post(() -> status.setText(m));
        });
    }

    static String label(String src) {
        switch (src) {
            case "steam": return "Steam";
            case "epic": return "Epic";
            case "xbox": return "Microsoft Store";
            case "gog": return "GOG";
            case "ubisoft": return "Ubisoft";
            default: return "Added by hand";
        }
    }

    // ---------- network ----------

    String url(String path) { return "http://" + prefs.getString("pc_ip", "") + ":8766" + path; }

    /** First three numbers of the TV's own address ("192.168.1."), to look for the PC on the same network. */
    String localPrefix() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    String ip = a.getAddress().getHostAddress();
                    return ip.substring(0, ip.lastIndexOf('.') + 1);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** The PC's name if Bruce TV's helper answers at this address, else null. */
    static String hello(String ip) {
        try {
            JSONObject j = new JSONObject(get("http://" + ip + ":8766/api/hello", 400));
            return "luna-game-mode".equals(j.optString("app")) ? j.optString("name", ip) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String get(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(Math.max(timeoutMs, 1500));
        try { return new String(MainActivity.readAll(c.getInputStream())); }
        finally { c.disconnect(); }
    }

    static void post(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(3000);
        try { c.getResponseCode(); } finally { c.disconnect(); }
    }

    // ---------- little view helpers ----------

    TextView text(String s, int sp, float alpha, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setAlpha(alpha);
        t.setGravity(Gravity.CENTER);
        t.setMaxWidth(px(640));
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    TextView button(String label, Runnable r) {
        TextView t = text(label, 15, 1f, true);
        t.setFocusable(true);
        t.setTextColor(0xFF000000); // white buttons, black text; the focused one is brighter and bigger
        t.setBackgroundResource(R.drawable.setup_btn);
        t.setPadding(px(22), px(11), px(22), px(11));
        t.setOnClickListener(v -> r.run());
        t.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start());
        return t;
    }

    /** Buttons side by side; the first gets focus. */
    void row(View... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        r.setGravity(Gravity.CENTER);
        for (View b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(px(6), px(8), px(6), px(8));
            r.addView(b, lp);
        }
        box.addView(r, lp(0, 14));
        if (box.findFocus() == null) buttons[0].requestFocus();
    }

    LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.gravity = Gravity.CENTER_HORIZONTAL;
        l.setMargins(0, px(top), 0, px(bottom));
        return l;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
}
