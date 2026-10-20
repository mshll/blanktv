package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the band behind the first (dock) row and adds a gap below it: frosted glass over the wallpaper when there is
 * one, a plain lighter band otherwise.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix frostMatrix = new Matrix();
    private final int[] location = new int[2];
    private Bitmap frost;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
        sheen.setColor(0x0FFFFFFF);
    }

    /**
     * @param frost the blurred wallpaper, shaped like the screen (WallpaperPlayer.frost()), or null for the plain band
     * @param dimPercent the wallpaper's dim, repeated over the frost so the glass is no brighter than its surround
     */
    void setFrost(Bitmap frost, int dimPercent) {
        this.frost = frost;
        frostPaint.setShader(frost == null ? null : new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        veil.setColor((dimPercent * 255 / 100) << 24);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        float radius = 26 * density;
        if (frost == null) {
            c.drawRoundRect(rect, radius, radius, band);
        } else {
            View screen = parent.getRootView();
            parent.getLocationOnScreen(location);
            frostMatrix.setScale((float) screen.getWidth() / frost.getWidth(), (float) screen.getHeight() / frost.getHeight());
            frostMatrix.postTranslate(-location[0], -location[1]);
            frostPaint.getShader().setLocalMatrix(frostMatrix);
            c.drawRoundRect(rect, radius, radius, frostPaint);
            c.drawRoundRect(rect, radius, radius, veil);
            c.drawRoundRect(rect, radius, radius, sheen);
        }
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
