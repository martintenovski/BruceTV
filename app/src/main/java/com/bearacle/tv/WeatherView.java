package com.bearacle.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * A small animated weather scene: shaded sky, soft volumetric clouds, sun with glow and rays, moon and stars, rain with
 * ripples, snow, fog, lightning, and hills for depth. All drawn with the canvas; the clouds and flakes are unit radial
 * gradients scaled by the canvas, so a frame costs a few dozen draw calls and no allocations.
 */
public class WeatherView extends View {

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
    // sky gradient (top, middle, horizon) per kind
    static final int[][] DAY = {{0xFF1F6FD8, 0xFF4F9BE8, 0xFFBEE0F7}, {0xFF2F70C2, 0xFF6C9FD2, 0xFFC4DAEC}, {0xFF55697F, 0xFF7C8D9F, 0xFFAAB6C2},
            {0xFF74818E, 0xFF9AA5B0, 0xFFC6CDD4}, {0xFF2E3B4A, 0xFF56677A, 0xFF8497A8}, {0xFF76889F, 0xFFA3B4C6, 0xFFD7E0EA}, {0xFF1F1D33, 0xFF3C3A58, 0xFF5E5A7C}};
    static final int[][] NIGHT = {{0xFF050B22, 0xFF10214F, 0xFF2A4378}, {0xFF0A122C, 0xFF1A2A52, 0xFF334670}, {0xFF141B27, 0xFF263041, 0xFF3C495C},
            {0xFF222A33, 0xFF384350, 0xFF52606D}, {0xFF0F1520, 0xFF222E3E, 0xFF38485B}, {0xFF232E40, 0xFF3D4C62, 0xFF5E7088}, {0xFF0C0A1E, 0xFF221F3C, 0xFF3C3A5C}};
    static final float[] STOPS = {0f, 0.55f, 1f};
    static final float[] PUFF = {0f, 0.45f, 0.8f, 1f};

    final float d = getResources().getDisplayMetrics().density;
    final long t0 = SystemClock.uptimeMillis();
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path clip = new Path(), far = new Path(), near = new Path(), bolt = new Path();
    final RectF rf = new RectF();
    final float[] sx = new float[64], sy = new float[64], ss = new float[64]; // fixed random 0..1 per particle
    // unit (radius 1, centred on the origin) gradients, positioned and sized with the canvas matrix
    final Shader sunGlow = new RadialGradient(0, 0, 1, new int[]{0xFFFFFBE0, 0xEEFFE79A, 0x66FFC857, 0x00FFB347}, new float[]{0, 0.2f, 0.55f, 1}, Shader.TileMode.CLAMP);
    final Shader moonGlow = new RadialGradient(0, 0, 1, new int[]{0x55FFFFFF, 0x22DDE6FF, 0x00DDE6FF}, new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP);
    final Shader moonDisc = new RadialGradient(-0.3f, -0.3f, 1.4f, 0xFFFFFFF2, 0xFFCFCDB8, Shader.TileMode.CLAMP);
    final Shader flake = new RadialGradient(0, 0, 1, new int[]{0xFFFFFFFF, 0xCCFFFFFF, 0x00FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
    final Shader haze = new LinearGradient(0, 0, 0, 1, new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP);
    Shader light, shade, sky;
    int kind = CLEAR, code = -1;
    boolean day = true;

    public WeatherView(Context c, AttributeSet a) {
        super(c, a);
        Random r = new Random(7);
        for (int i = 0; i < sx.length; i++) { sx[i] = r.nextFloat(); sy[i] = r.nextFloat(); ss[i] = r.nextFloat(); }
        p.setStrokeCap(Paint.Cap.ROUND);
        set(-1, true);
    }

    static Shader puff(int a, int b, int c) {
        return new RadialGradient(0, 0, 1, new int[]{a, b, c, c & 0xFFFFFF}, PUFF, Shader.TileMode.CLAMP);
    }

    /** WMO weather code (as open-meteo reports it); -1 is treated as clear. */
    public void set(int wmo, boolean isDay) {
        code = wmo;
        day = isDay;
        kind = wmo <= 0 ? CLEAR : wmo <= 2 ? PARTLY : wmo == 3 ? CLOUDY : wmo <= 48 ? FOG
                : (wmo <= 67 || (wmo >= 80 && wmo <= 82)) ? RAIN : (wmo <= 77 || wmo == 85 || wmo == 86) ? SNOW : STORM;
        boolean grey = kind == RAIN || kind == STORM;
        if (day) {
            light = grey ? puff(0xF0B4BEC9, 0xDDA6B1BE, 0x6697A4B2) : puff(0xFAFFFFFF, 0xEEFFFFFF, 0x80FFFFFF);
            shade = grey ? puff(0xF08B97A5, 0xDD7E8B9A, 0x66707E8E) : puff(0xF0D3DCE6, 0xDDC3CEDB, 0x70B5C2D1);
        } else {
            light = puff(0xE0B5BFD6, 0xBBA4AFC8, 0x6694A0BA);
            shade = puff(0xD0707C99, 0xAA616D8A, 0x5553607E);
        }
        if (kind == STORM) { light = puff(0xF07A8394, 0xDD6C7585, 0x665E6877); shade = puff(0xF0474F5E, 0xDD3D4552, 0x66333A47); }
        sky = null;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), 14 * d, 14 * d, Path.Direction.CW);
        hills(far, w, h, 0.74f, 0.05f, 2.3f, 0.7f);
        hills(near, w, h, 0.86f, 0.045f, 3.1f, 2.1f);
        sky = null;
    }

    static void hills(Path path, int w, int h, float base, float amp, float freq, float phase) {
        path.reset();
        path.moveTo(0, h);
        for (int i = 0; i <= 24; i++) {
            float x = w * i / 24f;
            path.lineTo(x, h * (base + amp * (float) (Math.sin(i / 24f * freq * 6.28 + phase) * 0.6 + Math.sin(i / 24f * freq * 13.1 + phase * 2) * 0.4)));
        }
        path.lineTo(w, h);
        path.close();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) invalidate(); // the frame loop stops while hidden
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - t0) / 1000f, m = Math.min(w, h);
        int[] col = (day ? DAY : NIGHT)[kind];
        c.save();
        c.clipPath(clip);
        if (sky == null) sky = new LinearGradient(0, 0, 0, h, col, STOPS, Shader.TileMode.CLAMP);
        p.setShader(sky);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (kind <= PARTLY) {
            if (day) sun(c, w * 0.72f, h * 0.27f, m * 0.09f, t, w, h);
            else moon(c, w * 0.72f, h * 0.26f, m * 0.085f, t, w, h);
        }
        // distant hills sit behind the clouds' lower edge and give the scene depth
        p.setColor(day ? 0x403A5670 : 0x66050A18);
        c.drawPath(far, p);

        int clouds = kind == PARTLY ? 2 : kind == CLOUDY ? 4 : kind == SNOW ? 3 : kind == RAIN || kind == STORM ? 4 : 0;
        for (int i = 0; i < clouds; i++) {
            float cw = m * (0.5f + 0.4f * ss[i + 10]), x = (sx[i] * (w + cw) + t * (7 + 6 * ss[i]) * d) % (w + cw) - cw;
            cloud(c, x, h * (0.04f + 0.3f * sy[i + 20]), cw);
        }
        if (kind == FOG) fog(c, w, h, t);

        p.setColor(day ? 0x66233347 : 0x99030612);
        c.drawPath(near, p);

        if (kind == RAIN || kind == STORM) rain(c, w, h, t, code >= 51 && code <= 57 ? 20 : 52);
        if (kind == SNOW) snow(c, w, h, t);
        if (kind == STORM) lightning(c, w, h, t);
        c.restore();
        postInvalidateDelayed(40); // ~25 fps is plenty for slow weather and keeps the TV's CPU quiet
    }

    void sun(Canvas c, float cx, float cy, float r, float t, int w, int h) {
        p.setShader(haze); // warm haze along the horizon
        c.save();
        c.translate(0, h * 0.5f);
        c.scale(w, h * 0.5f);
        p.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, 1, 1, p);
        c.restore();
        float pulse = 1 + 0.04f * (float) Math.sin(t * 1.3);
        p.setShader(sunGlow);
        c.save();
        c.translate(cx, cy);
        c.scale(r * 4.2f * pulse, r * 4.2f * pulse);
        c.drawCircle(0, 0, 1, p);
        c.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        for (int i = 0; i < 16; i++) { // long faint rays turning slowly
            double a = Math.toRadians(t * 5 + i * 22.5);
            p.setColor(((i % 2 == 0 ? 0x22 : 0x10) << 24) | 0xFFF2B0);
            c.drawLine(cx + (float) Math.cos(a) * r * 1.5f, cy + (float) Math.sin(a) * r * 1.5f,
                    cx + (float) Math.cos(a) * r * 3.6f, cy + (float) Math.sin(a) * r * 3.6f, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFF6CC);
        c.drawCircle(cx, cy, r * 0.95f, p);
    }

    void moon(Canvas c, float cx, float cy, float r, float t, int w, int h) {
        for (int i = 0; i < 26; i++) { // twinkling stars, a few with a soft glow
            float a = 0.3f + 0.7f * Math.abs((float) Math.sin(t * (0.8 + ss[i]) + ss[i + 30] * 6.28));
            float x = sx[i] * w, y = sy[i] * h * 0.62f, sr = (0.6f + 1.1f * ss[i + 30]) * d;
            p.setColor(((int) (a * 255) << 24) | 0xFFFFFF);
            c.drawCircle(x, y, sr, p);
            if (ss[i] > 0.75f) { p.setShader(flake); c.save(); c.translate(x, y); c.scale(sr * 4, sr * 4); p.setAlpha((int) (a * 120)); c.drawCircle(0, 0, 1, p); c.restore(); p.setShader(null); }
        }
        p.setAlpha(255);
        p.setShader(moonGlow);
        c.save();
        c.translate(cx, cy);
        c.scale(r * 4f, r * 4f);
        c.drawCircle(0, 0, 1, p);
        c.restore();
        p.setShader(moonDisc);
        c.save();
        c.translate(cx, cy);
        c.scale(r, r);
        c.drawCircle(0, 0, 1, p);
        c.restore();
        p.setShader(null);
        p.setColor(0x22707080); // craters
        c.drawCircle(cx - r * 0.3f, cy - r * 0.2f, r * 0.22f, p);
        c.drawCircle(cx + r * 0.35f, cy + r * 0.1f, r * 0.16f, p);
        c.drawCircle(cx - r * 0.05f, cy + r * 0.45f, r * 0.13f, p);
    }

    /** Soft cumulus: a grey-blue underside of puffs with brighter puffs piled on top. */
    void cloud(Canvas c, float x, float y, float cw) {
        float[][] under = {{0.18f, 0.55f, 0.2f}, {0.38f, 0.62f, 0.24f}, {0.6f, 0.6f, 0.23f}, {0.8f, 0.55f, 0.19f}};
        float[][] top = {{0.28f, 0.4f, 0.24f}, {0.5f, 0.28f, 0.29f}, {0.72f, 0.4f, 0.23f}, {0.45f, 0.46f, 0.26f}};
        p.setAlpha(255);
        p.setShader(shade);
        for (float[] u : under) blob(c, x + cw * u[0], y + cw * u[1] * 0.62f, cw * u[2] * 1.25f);
        p.setShader(light);
        for (float[] u : top) blob(c, x + cw * u[0], y + cw * u[1] * 0.62f, cw * u[2] * 1.25f);
        p.setShader(null);
    }

    void blob(Canvas c, float cx, float cy, float r) {
        c.save();
        c.translate(cx, cy);
        c.scale(r, r * 0.8f);
        c.drawCircle(0, 0, 1, p);
        c.restore();
    }

    void fog(Canvas c, int w, int h, float t) {
        p.setShader(flake); // soft drifting banks, no hard edges
        for (int i = 0; i < 5; i++) {
            float x = w * (0.5f + (float) Math.sin(t * (0.1 + 0.04 * i) + i * 1.7) * 0.3f), y = h * (0.2f + 0.14f * i);
            p.setAlpha(day ? 120 : 70);
            c.save();
            c.translate(x, y);
            c.scale(w * 0.85f, h * 0.2f);
            c.drawCircle(0, 0, 1, p);
            c.restore();
        }
        p.setShader(null);
        p.setAlpha(255);
    }

    void rain(Canvas c, int w, int h, float t, int n) {
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < n; i++) {
            float depth = 0.5f + 0.5f * ss[i], len = (8 + 14 * depth) * d, y = (sy[i] * h + t * (170 + 190 * depth) * d) % (h + len) - len;
            float x = ((sx[i] * w - 0.18f * y) % w + w) % w;
            p.setStrokeWidth((0.8f + 0.9f * depth) * d);
            p.setColor(((int) (70 + 110 * depth) << 24) | 0xDCE8F5);
            c.drawLine(x, y, x - 0.18f * len, y + len, p);
        }
        p.setStrokeWidth(1.1f * d); // ripples where the drops land
        for (int i = 0; i < 9; i++) {
            float ph = (t * 0.8f + ss[i + 40]) % 1f;
            p.setColor(((int) ((1 - ph) * 90) << 24) | 0xDCE8F5);
            rf.set(sx[i + 40] * w - ph * 16 * d, h * 0.95f - ph * 3.5f * d, sx[i + 40] * w + ph * 16 * d, h * 0.95f + ph * 3.5f * d);
            c.drawOval(rf, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    void snow(Canvas c, int w, int h, float t) {
        p.setShader(flake);
        for (int i = 0; i < 40; i++) {
            float depth = 0.4f + 0.6f * ss[i + 20], r = (1.4f + 3.2f * depth) * d;
            float y = (sy[i] * h + t * (14 + 30 * depth) * d) % (h + 10 * d) - 5 * d;
            float x = sx[i] * w + (float) Math.sin(t * (0.6 + 0.5 * depth) + ss[i] * 6.28) * 12 * d;
            p.setAlpha((int) (120 + 135 * depth));
            c.save();
            c.translate(x, y);
            c.scale(r, r);
            c.drawCircle(0, 0, 1, p);
            c.restore();
        }
        p.setShader(null);
        p.setAlpha(255);
    }

    void lightning(Canvas c, int w, int h, float t) {
        float ph = t % 6f; // a bolt with a double flash every six seconds
        int strike = (int) (t / 6f);
        float a = ph < 0.08f ? 0.55f : ph < 0.18f ? 0.05f : ph < 0.36f ? 0.4f * (1 - (ph - 0.18f) / 0.18f) : 0f;
        if (ph < 0.36f) {
            Random r = new Random(strike * 31L + 5);
            bolt.reset();
            float x = w * (0.25f + 0.5f * r.nextFloat()), y = 0;
            bolt.moveTo(x, y);
            while (y < h * 0.62f) { y += h * (0.05f + 0.05f * r.nextFloat()); x += (r.nextFloat() - 0.5f) * w * 0.14f; bolt.lineTo(x, y); }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(7 * d);
            p.setColor(((int) (a * 160) << 24) | 0xB4C8FF);
            c.drawPath(bolt, p);
            p.setStrokeWidth(2 * d);
            p.setColor(((int) (Math.min(1f, a * 2) * 255) << 24) | 0xFFFFFF);
            c.drawPath(bolt, p);
            p.setStyle(Paint.Style.FILL);
        }
        if (a > 0) { p.setColor(((int) (a * 150) << 24) | 0xDCE4FF); c.drawRect(0, 0, w, h, p); }
    }
}
