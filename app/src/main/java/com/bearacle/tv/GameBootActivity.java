package com.bearacle.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Locale;
import java.util.Random;

/** Console-style boot intro, then straight into a Moonlight stream of the gaming PC. */
public class GameBootActivity extends Activity {

    static final float LENGTH = 3.6f; // seconds
    static final float COVER_START = LENGTH - 0.45f; // where the fade-out would begin
    boolean launched;
    String title;
    ValueAnimator anim;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!p.contains("pc_ip")) { // Game Mode was never set up: do that first
            startActivity(new Intent(this, SetupActivity.class).putExtra("game", true));
            finish();
            return;
        }
        String app = p.getString("game_app", "Game Mode");
        title = app.isEmpty() ? "MOONLIGHT" : app.toUpperCase(Locale.ROOT);
        BootView v = new BootView(this, title);
        setContentView(v);
        // With the cover available the intro stops before its fade-out and the cover carries on from there.
        float end = KeyService.instance != null ? COVER_START : LENGTH;
        anim = ValueAnimator.ofFloat(0f, end);
        // The TV's developer "animator duration scale" (0.5x here) would cut the intro short; undo it.
        float scale = ValueAnimator.getDurationScale();
        anim.setDuration((long) (end * 1000 / (scale > 0 ? scale : 1)));
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { v.t = (float) a.getAnimatedValue(); v.invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { launch(); }
        });
        anim.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { anim.cancel(); launched = true; finish(); return true; }
        anim.end(); // any other key skips the intro
        return true;
    }

    void launch() {
        if (launched) return;
        launched = true;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String app = p.getString("game_app", "Game Mode");
        Intent i = new Intent().setClassName("com.limelight", "com.limelight.ShortcutTrampoline")
                .putExtra("Name", p.getString("pc_name", ""))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!app.isEmpty()) i.putExtra("AppName", app);
        // Keep the intro on screen while Moonlight connects underneath.
        boolean covered = KeyService.instance != null && KeyService.instance.showCover(title);
        try {
            startActivity(i);
        } catch (Exception e) {
            Intent l = getPackageManager().getLeanbackLaunchIntentForPackage("com.limelight");
            if (l != null) startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
        overridePendingTransition(0, covered ? 0 : android.R.anim.fade_out);
    }

    static class BootView extends View {
        float t;
        boolean hold; // loading cover: never fade out
        final String title;
        String sub = "Starting stream…";
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final float[][] dots = new float[70][4]; // x, y, radius, speed (fractions of the screen)
        // triangle, circle, cross, square
        static final int[] COLORS = {0xFF3DDBB6, 0xFFFF5468, 0xFF7AA7FF, 0xFFF28AD0};

        BootView(Context c, String title) {
            super(c);
            this.title = title;
            setBackgroundColor(Color.BLACK);
            Random r = new Random(7);
            for (float[] d : dots) { d[0] = r.nextFloat(); d[1] = r.nextFloat(); d[2] = 0.6f + r.nextFloat() * 1.8f; d[3] = 0.02f + r.nextFloat() * 0.06f; }
            wave.setStyle(Paint.Style.STROKE);
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeCap(Paint.Cap.ROUND);
            shape.setStrokeJoin(Paint.Join.ROUND);
            dot.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            glow.setShader(new RadialGradient(w / 2f, h * 0.7f, w * 0.75f,
                    new int[]{0xFF1F5BFF, 0xFF0A1E66, 0xFF000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }

        static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
        static float smooth(float a, float b, float v) { float x = clamp((v - a) / (b - a)); return x * x * (3 - 2 * x); }
        static float overshoot(float x) { x -= 1f; return x * x * (3.2f * x + 2.2f) + 1f; }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float in = smooth(0f, 1.3f, t);
            float out = hold ? 1f : 1f - smooth(COVER_START, LENGTH, t);

            glow.setAlpha((int) (255 * in * out));
            c.drawRect(0, 0, w, h, glow);

            // light ribbons
            for (int k = 0; k < 3; k++) {
                wave.setStrokeWidth(d * (1.2f + k * 0.8f));
                wave.setColor(Color.argb((int) ((70 - k * 18) * in * out), 160, 200, 255));
                path.reset();
                for (int x = 0; x <= w; x += 12) {
                    float y = h * (0.66f + k * 0.04f)
                            + (float) Math.sin(x * (0.004f + k * 0.0015f) + t * (1.4f + k * 0.5f) + k) * h * (0.05f - k * 0.01f);
                    if (x == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                c.drawPath(path, wave);
            }

            // drifting particles
            for (float[] p : dots) {
                float y = ((p[1] - t * p[3]) % 1f + 1f) % 1f;
                float tw = 0.35f + 0.65f * Math.abs((float) Math.sin(t * 2.2f + p[0] * 40));
                dot.setAlpha((int) (200 * tw * in * out));
                c.drawCircle(p[0] * w, y * h, p[2] * d, dot);
            }

            // the four symbols pop in one by one
            float s = h * 0.055f, gap = s * 3.1f, cy = h * 0.44f;
            shape.setStrokeWidth(s * 0.2f);
            for (int i = 0; i < 4; i++) {
                float k = clamp((t - 0.9f - i * 0.17f) / 0.5f);
                if (k <= 0) continue;
                float sc = overshoot(k);
                int col = COLORS[i];
                shape.setColor(col);
                shape.setAlpha((int) (255 * k * out));
                shape.setShadowLayer(s * 0.6f, 0, 0, col);
                float cx = w / 2f + (i - 1.5f) * gap;
                c.save();
                c.translate(cx, cy);
                c.scale(sc, sc);
                drawSymbol(c, i, s);
                c.restore();
            }

            // title
            float ta = smooth(2.0f, 2.6f, t) * out;
            text.setTextSize(h * 0.034f);
            text.setLetterSpacing(0.35f);
            text.setAlpha((int) (255 * ta));
            c.drawText(title, w / 2f, h * 0.62f, text);
            text.setTextSize(h * 0.02f);
            text.setLetterSpacing(0.15f);
            text.setAlpha((int) (150 * ta * (0.6f + 0.4f * (float) Math.sin(t * 6))));
            c.drawText(sub, w / 2f, h * 0.67f, text);
        }

        void drawSymbol(Canvas c, int i, float s) {
            switch (i) {
                case 0:
                    path.reset();
                    path.moveTo(0, -s);
                    path.lineTo(s * 0.95f, s * 0.7f);
                    path.lineTo(-s * 0.95f, s * 0.7f);
                    path.close();
                    c.drawPath(path, shape);
                    break;
                case 1:
                    c.drawCircle(0, 0, s * 0.92f, shape);
                    break;
                case 2:
                    c.drawLine(-s * 0.8f, -s * 0.8f, s * 0.8f, s * 0.8f, shape);
                    c.drawLine(s * 0.8f, -s * 0.8f, -s * 0.8f, s * 0.8f, shape);
                    break;
                default:
                    c.drawRect(-s * 0.8f, -s * 0.8f, s * 0.8f, s * 0.8f, shape);
            }
        }
    }
}
