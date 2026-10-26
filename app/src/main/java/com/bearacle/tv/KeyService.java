package com.bearacle.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.TimeAnimator;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Sends the remote's Home button to Bruce TV (Google TV otherwise forces its own home), lets Control Center sleep
 * the TV, and holds the Game Mode loading screen over Moonlight until the stream is actually running.
 */
public class KeyService extends AccessibilityService {

    static KeyService instance;

    final Handler ui = new Handler(Looper.getMainLooper());
    GameBootActivity.BootView cover;
    TimeAnimator coverAnim;
    long coverSince;
    boolean sawGame, exitCover;
    int clearChecks;

    @Override protected void onServiceConnected() { instance = this; filterKeys(false); }

    @Override public boolean onUnbind(Intent i) { hideCover(); instance = null; return super.onUnbind(i); }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (e.getAction() == KeyEvent.ACTION_UP)
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        // Apollo never creates a virtual pad for this remote, so while a stream is up and the Game Mode server
        // answers, its keys go straight to the menu over the LAN instead of into Moonlight (where Back would
        // quit the stream and OK would launch whatever Moonlight's app list has focused).
        String k = remoteKey(e.getKeyCode());
        if (k == null) return false;
        boolean stream = cover == null && inStream();
        if (stream) startPolling();
        if (e.getAction() == KeyEvent.ACTION_DOWN)
            android.util.Log.i("LunaTV", "key " + e.getKeyCode() + " rep=" + e.getRepeatCount() + " -> " + k + " stream=" + stream + " pc=" + pcAlive + " menu=" + menuFocused);
        if (!stream || !pcAlive) return false;
        boolean held = e.getRepeatCount() > 0;
        if (e.getAction() == KeyEvent.ACTION_DOWN && (!held || k.equals("up") || k.equals("down") || k.equals("left") || k.equals("right")))
            net.execute(() -> { try { pc("POST", "/api/key/" + k); } catch (Exception ignored) { } });
        return true;
    }

    static String remoteKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "ok";
            case KeyEvent.KEYCODE_BACK: return "back";
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO: return "menu";
            default: return null;
        }
    }

    // ---------- Game Mode remote bridge ----------

    final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();
    volatile boolean pcAlive, menuFocused;
    boolean polling;
    long streamSince;
    String moonlightCls = "";

    /** Moonlight is the app in front and not on its own PC/app list (decided live: window events can be missed). */
    boolean inStream() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null && "com.limelight".contentEquals(root.getPackageName())
                && !moonlightCls.equals("com.limelight.AppView") && !moonlightCls.equals("com.limelight.PcView");
    }

    String pc(String method, String path) throws Exception {
        String ip = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("pc_ip", "");
        if (ip.isEmpty()) throw new Exception("PC not set up");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://" + ip + ":8765" + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(800);
        c.setReadTimeout(800);
        try (java.io.InputStream in = c.getInputStream()) { return new String(MainActivity.readAll(in)); }
        finally { c.disconnect(); }
    }

    /**
     * Key filtering makes the system stop auto-repeating volume keys (a held volume key steps once), so it is only on
     * while a stream is up, where the remote's keys have to be intercepted. Elsewhere Home is handled by window events.
     */
    void filterKeys(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int flags = on ? info.flags | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                : info.flags & ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        if (flags == info.flags) return;
        info.flags = flags;
        setServiceInfo(info);
    }

    void startPolling() {
        filterKeys(true);
        if (polling) return;
        polling = true;
        streamSince = SystemClock.uptimeMillis();
        ui.post(pollFocus);
    }

    /** While Moonlight is in front, ask the PC once a second whether the Game Mode menu is up. */
    final Runnable pollFocus = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (cover == null && (root == null || root.getPackageName() == null || !"com.limelight".contentEquals(root.getPackageName()))) {
                polling = false;
                pcAlive = menuFocused = false;
                filterKeys(false);
                return;
            }
            boolean early = SystemClock.uptimeMillis() - streamSince < 20_000;
            net.execute(() -> {
                try {
                    String r = pc("GET", "/api/focus");
                    pcAlive = r.contains("\"alive\": true");
                    menuFocused = r.contains("\"menu\": true");
                    // Exit Game Mode was picked: hide the PC desktop that shows while the stream winds down.
                    if (r.contains("\"exiting\": true")) ui.post(() -> { if (inStream()) showCover("SEE YOU SOON", true); });
                    // The menu window may appear after the stream does: keep asking the PC to raise it for a bit.
                    if (!menuFocused && early) pc("POST", "/api/front");
                } catch (Exception e) {
                    pcAlive = menuFocused = false;
                    android.util.Log.w("LunaTV", "Game Mode PC unreachable: " + e);
                }
            });
            ui.postDelayed(this, 1000);
        }
    };

    /** The system power menu (Power off / Restart on Google TV). */
    static boolean powerMenu() { return instance != null && instance.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); }

    static boolean sleep() { return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }

    /** Google TV opens its own home before key filters see Home, so swap it out the moment it appears. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getClassName() == null) return;
        String cls = e.getClassName().toString();
        boolean googleHome = cls.startsWith("com.google.android.apps.tv.launcherx.home.") && cls.endsWith("HomeActivity");
        if (googleHome) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (cls.startsWith("com.limelight.")) {
            moonlightCls = cls;
            if (cls.equals("com.limelight.Game")) {
                // Any stream counts, however it was started (Bruce's Game Mode button or Moonlight itself).
                sawGame = true;
                startPolling();
            } else if (sawGame && (cls.equals("com.limelight.AppView") || cls.equals("com.limelight.PcView"))) {
                // The stream ended and Moonlight fell back to its own app list: go home to Bruce TV instead.
                sawGame = false;
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else if (cls.equals(MainActivity.class.getName())) {
            sawGame = false;
        }
    }

    @Override public void onInterrupt() { }

    // ---------- Game Mode loading cover ----------

    boolean showCover(String title) { return showCover(title, false); }

    /** exit: the closing screen, shown from the start of the intro until Bruce TV's home is back. */
    boolean showCover(String title, boolean exit) {
        if (cover != null) return true;
        try {
            cover = new GameBootActivity.BootView(this, title);
            cover.hold = true;
            if (exit) cover.sub = "Closing Game Mode…";
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(cover, lp);
        } catch (Exception e) {
            cover = null;
            return false;
        }
        GameBootActivity.BootView v = cover;
        float start = exit ? 0.4f : GameBootActivity.COVER_START;
        coverAnim = new TimeAnimator();
        coverAnim.setTimeListener((a, total, dt) -> { v.t = start + total / 1000f; v.invalidate(); });
        coverAnim.start();
        coverSince = SystemClock.uptimeMillis();
        exitCover = exit;
        clearChecks = 0;
        if (!exit) { sawGame = false; startPolling(); }
        ui.postDelayed(this::checkCover, 500);
        return true;
    }

    /**
     * Moonlight connects inside ShortcutTrampoline, then its Game activity, both behind a progress dialog.
     * Lift the cover once Game is up with no dialog left, at once for an error dialog (so it can be read),
     * when the user backs out of Moonlight, or after 40 s whatever happens.
     */
    void checkCover() {
        if (cover == null) return;
        long age = SystemClock.uptimeMillis() - coverSince;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean moonlight = root != null && root.getPackageName() != null && "com.limelight".contentEquals(root.getPackageName());
        boolean spinner = moonlight && !root.findAccessibilityNodeInfosByViewId("android:id/progress").isEmpty();
        boolean errorDialog = moonlight && !root.findAccessibilityNodeInfosByViewId("android:id/button1").isEmpty();
        if (exitCover) {
            // Stream over (Moonlight's list or its "stream ended" dialog), or it is taking too long: go home.
            if (moonlight && (errorDialog || !inStream() || age > 8000))
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if ((root != null && !moonlight) || age > 20_000) hideCover();
            else ui.postDelayed(this::checkCover, 300);
            return;
        }
        boolean leftMoonlight = root != null && !moonlight && age > 4000;
        clearChecks = sawGame && moonlight && !spinner ? clearChecks + 1 : 0;
        if (errorDialog || leftMoonlight || clearChecks >= 3 || age > 40_000) hideCover();
        else ui.postDelayed(this::checkCover, 300);
    }

    void hideCover() {
        if (cover == null) return;
        // The stream is up (or resumed): make sure the menu, not some other PC window, is in front.
        if (!exitCover) net.execute(() -> { try { pc("POST", "/api/front"); } catch (Exception ignored) { } });
        GameBootActivity.BootView v = cover;
        TimeAnimator a = coverAnim;
        cover = null;
        v.animate().alpha(0f).setDuration(350).withEndAction(() -> {
            a.cancel();
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) { }
        }).start();
    }
}
