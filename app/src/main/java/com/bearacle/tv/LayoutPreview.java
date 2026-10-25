package com.bearacle.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** A small wireframe of one home layout, drawn from rectangles, for the layout picker. Keep in step with res/layout/layout_*.xml. */
public class LayoutPreview extends View {

    static final int[] TILES = {0xFFE53935, 0xFF7E57C2, 0xFFF5F5F5, 0xFF42A5F5, 0xFFEF5350, 0xFF5C6BC0, 0xFF26A69A};
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final String key;

    public LayoutPreview(Context c, String key) {
        super(c);
        this.key = key;
    }

    static int w(int alpha) { return (alpha << 24) | 0xFFFFFF; }

    /** Rounded box in 0..1 screen fractions. */
    void box(Canvas c, float l, float t, float rt, float b, int color, float rad) {
        float W = getWidth(), H = getHeight();
        r.set(l * W, t * H, rt * W, b * H);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(r, rad * H, rad * H, p);
    }

    void box(Canvas c, float l, float t, float rt, float b, int color) { box(c, l, t, rt, b, color, 0.025f); }

    void ring(Canvas c, float l, float t, float rt, float b) {
        float W = getWidth(), H = getHeight();
        r.set(l * W, t * H, rt * W, b * H);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, H * 0.018f));
        p.setColor(0xFFFFFFFF);
        c.drawRoundRect(r, 0.025f * H, 0.025f * H, p);
        p.setStyle(Paint.Style.FILL);
    }

    void tiles(Canvas c, int n, float x0, float y0, float y1, float tw, float gap, int focus) {
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (tw + gap);
            box(c, x, y0, x + tw, y1, (TILES[i % TILES.length] & 0xFFFFFF) | 0xDD000000);
            if (i == focus) ring(c, x, y0, x + tw, y1);
        }
    }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        p.setShader(new LinearGradient(0, 0, W, H, 0xFF16213E, 0xFF3A2B52, Shader.TileMode.CLAMP));
        r.set(0, 0, W, H);
        c.drawRoundRect(r, 0.05f * H, 0.05f * H, p);
        p.setShader(null);
        switch (key) {
            case "dock":
                box(c, .06f, .08f, .34f, .28f, w(0xD9), .03f);
                box(c, .06f, .33f, .2f, .365f, w(0xB0));
                box(c, .06f, .4f, .28f, .43f, w(0x55));
                box(c, .56f, .07f, .72f, .15f, w(0x55), .04f);
                for (int i = 0; i < 3; i++) box(c, .76f + i * .07f, .07f, .82f + i * .07f, .15f, w(0x55), .04f);
                box(c, .05f, .64f, .95f, .93f, w(0x26), .06f);
                tiles(c, 5, .08f, .73f, .86f, .1f, .018f, 1);
                box(c, .685f, .72f, .69f, .87f, w(0x55), 0);
                for (int i = 0; i < 4; i++) box(c, .715f + i * .054f, .74f, .76f + i * .054f, .85f, w(0x40));
                break;
            case "bento":
                box(c, .05f, .05f, .16f, .09f, w(0xD0));
                box(c, .72f, .04f, .86f, .1f, w(0x55), .03f);
                box(c, .88f, .04f, .95f, .1f, w(0x40), .03f);
                box(c, .05f, .15f, .34f, .64f, 0xFF7A3CFF, .035f);   // Game Mode
                box(c, .35f, .15f, .64f, .64f, 0xFF4F7FB5, .035f);   // Continue / weather
                tiles(c, 4, .05f, .7f, .91f, .13f, .016f, -1);
                for (int row = 0; row < 3; row++) for (int col = 0; col < 2; col++) {
                    float x = .67f + col * .145f, y = .15f + row * .255f;
                    box(c, x, y, x + .135f, y + .24f, w(0x40), .03f);
                }
                break;
            case "bottom":
                box(c, .05f, .07f, .15f, .1f, w(0xD0));
                box(c, .05f, .13f, .2f, .15f, w(0x55));
                box(c, .82f, .05f, .95f, .13f, w(0xD0), .03f);
                box(c, .28f, .27f, .72f, .38f, w(0xD9), .03f);
                tiles(c, 7, .05f, .46f, .62f, .11f, .0233f, 1);
                box(c, .05f, .74f, .95f, .94f, w(0x26), .05f);
                for (int i = 0; i < 7; i++) {
                    float x = .08f + i * .12f;
                    if (i == 4) box(c, x - .01f, .765f, x + .1f, .915f, 0xFF7A3CFF, .03f);
                    box(c, x + .025f, .79f, x + .065f, .86f, w(0xC0));
                    box(c, x + .015f, .885f, x + .075f, .9f, w(0x60), .01f);
                }
                break;
            default: // classic
                box(c, .05f, .06f, .14f, .1f, w(0xD0));
                box(c, .2f, .05f, .55f, .13f, w(0x40), .04f);
                box(c, .82f, .05f, .95f, .14f, w(0xD0), .03f);
                box(c, .28f, .27f, .72f, .38f, w(0xD9), .03f);
                box(c, .38f, .42f, .62f, .45f, w(0x55));
                tiles(c, 7, .05f, .54f, .69f, .11f, .0233f, 1);
                for (int i = 0; i < 5; i++) box(c, .05f + i * .182f, .76f, .05f + i * .182f + .165f, .86f, w(0x33), .03f);
                box(c, .05f, .92f, .2f, .945f, w(0x40));
        }
    }
}
