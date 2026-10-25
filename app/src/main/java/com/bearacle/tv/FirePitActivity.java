package com.bearacle.tv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;

/** Standby screen: loops the fire-pit video copied to the app's files folder. Any button brings Luna TV back. */
public class FirePitActivity extends Activity {

    static final String FILE = "fire-pit.mp4";

    static File video(Activity a) { return new File(a.getExternalFilesDir(null), FILE); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        VideoView v = new VideoView(this);
        v.setVideoPath(video(this).getPath());
        v.setOnPreparedListener(mp -> mp.setLooping(true));
        v.setOnErrorListener((mp, what, extra) -> { finish(); return true; });
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);
        v.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        finish();
        return true;
    }
}
