package com.bearacle.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Glass skins: the wallpaper, blurred, drawn inside a rounded panel. It paints the part of the full-screen wallpaper that sits under its view, so the panel looks like frosted glass. */
class Frost extends Drawable {
    /** Tiny screen-shaped copy of the wallpaper (see MainActivity.frostWall); stretching it back up is the blur. */
    static Bitmap blur;

    private final View view;
    private final float radius;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final int[] xy = new int[2];

    Frost(View view, float radius) {
        this.view = view;
        this.radius = radius;
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.25f); // frosted glass lifts the colour behind it
        paint.setColorFilter(new ColorMatrixColorFilter(m));
    }

    @Override public void draw(Canvas c) {
        View root = view.getRootView();
        if (blur == null || root.getWidth() == 0) return;
        clip.rewind();
        clip.addRoundRect(new RectF(getBounds()), radius, radius, Path.Direction.CW);
        view.getLocationInWindow(xy);
        c.save();
        c.clipPath(clip);
        c.translate(-xy[0], -xy[1]); // window coordinates, where the wallpaper fills the screen
        c.scale(root.getWidth() / (float) blur.getWidth(), root.getHeight() / (float) blur.getHeight());
        c.drawBitmap(blur, 0, 0, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { }

    @Override public void setColorFilter(ColorFilter f) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
