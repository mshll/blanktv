package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Frosted glass behind a view: the part of the blurred wallpaper (WallpaperPlayer.shownFrost()) that lies under
 * the host on screen, darkened by the wallpaper's dim. Draws nothing without a wallpaper.
 */
final class FrostDrawable extends Drawable {
    private final View host;
    private final float radius;
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] location = new int[2];
    private Bitmap frost;

    FrostDrawable(View host, float radius, int veilArgb) {
        this.host = host;
        this.radius = radius;
        veil.setColor(veilArgb);
    }

    @Override
    public void draw(Canvas canvas) {
        Bitmap current = WallpaperPlayer.shownFrost();
        if (current == null) return;
        if (current != frost) {
            frost = current;
            frostPaint.setShader(new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        host.getLocationOnScreen(location);
        float sx = (float) host.getResources().getDisplayMetrics().widthPixels / frost.getWidth();
        float sy = (float) host.getResources().getDisplayMetrics().heightPixels / frost.getHeight();
        matrix.setScale(sx, sy);
        matrix.postTranslate(-location[0], -location[1]);
        frostPaint.getShader().setLocalMatrix(matrix);
        rect.set(getBounds());
        canvas.drawRoundRect(rect, radius, radius, frostPaint);
        canvas.drawRoundRect(rect, radius, radius, veil);
    }

    @Override
    public void setAlpha(int alpha) {
        frostPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        frostPaint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
