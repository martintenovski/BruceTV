package com.bearacle.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Self-update from the latest GitHub release of the public releases repo (tag "v2.1", one .apk asset, notes in the body). */
final class Updater {
    static final String REPO = "martintenovski/BruceTV";
    static final String ACTION = "com.bearacle.tv.INSTALL_STATUS";

    static class Release { String version, notes, apkUrl; long size; }

    interface Progress { void at(int percent); }

    /** The newest release, or null when the repo has none yet. */
    static Release latest() throws Exception {
        JSONObject j = new JSONObject(new String(MainActivity.http("https://api.github.com/repos/" + REPO + "/releases/latest"), "UTF-8"));
        JSONArray assets = j.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (!a.getString("name").endsWith(".apk")) continue;
            Release r = new Release();
            r.version = j.getString("tag_name").replaceFirst("^[vV]", "");
            r.notes = j.optString("body", "").trim();
            r.apkUrl = a.getString("browser_download_url");
            r.size = a.optLong("size");
            return r;
        }
        return null;
    }

    /** Dotted numbers compared part by part, so 2.10 > 2.9. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    static File download(Context c, Release r, Progress p) throws Exception {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "LunaTV");
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size, done = 0;
        try (InputStream in = h.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            for (int n, last = -1; (n = in.read(buf)) > 0; ) {
                os.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (100 * done / total) : 0;
                if (pct != last) { last = pct; p.at(pct); }
            }
        } finally { h.disconnect(); }
        if (total > 0 && done != total) throw new java.io.IOException("Download cut short");
        return out;
    }

    /** Hands the APK to the system installer; Result below shows its confirm screen. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream os = s.openWrite("luna.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
                s.fsync(os);
            }
            // mutable: the system adds the status extras to this intent
            s.commit(PendingIntent.getBroadcast(c, id, new Intent(c, Result.class).setAction(ACTION), // explicit: the receiver has no intent filter
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
        }
    }

    /** Install status from the system: opens the "Update this app?" screen, or says why it failed. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask != null) c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st != PackageInstaller.STATUS_SUCCESS) { // success kills this process, so nobody sees it
                Toast.makeText(c, "Update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
            }
        }
    }
}
