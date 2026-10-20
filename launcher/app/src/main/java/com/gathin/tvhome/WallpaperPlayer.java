package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Displays and rotates a theme's local wallpaper images while Home is visible, and keeps a small blurred copy of
 * the visible part (the frost) for glass surfaces to draw.
 */
final class WallpaperPlayer {
    static final String ROTATION_PREFS = "wallpaper_rotation";
    private static final int FROST_WIDTH = 96;
    private static final int FROST_PASSES = 3;
    private static final int FROST_RADIUS = 2;

    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    private Bitmap frost;
    /** The frost of the wallpaper on Home, for glass surfaces outside it (popups, settings); null when there is none. */
    private static Bitmap shown;
    private Consumer<Bitmap> onFrost = f -> { };

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        String mode = Choices.wallpaperMode(context);
        interval = Choices.FIXED.equals(mode) ? 0 : config.wallpaperRotationMinutes * 60_000L;
        preferences = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shown = null;
        if (Choices.BLACK.equals(mode)) files.clear();
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                frost = frost(bitmap);
                shown = frost;
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, random);
        }
    }

    /** The blurred visible wallpaper, screen-shaped and FROST_WIDTH wide; null without a wallpaper. */
    Bitmap frost() {
        return frost;
    }

    static Bitmap shownFrost() {
        return shown;
    }

    void setOnFrost(Consumer<Bitmap> listener) {
        onFrost = listener;
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            Bitmap nextFrost = bitmap == null ? null : frost(bitmap);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                image.setImageBitmap(bitmap);
                frost = nextFrost;
                shown = frost;
                onFrost.accept(frost);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** The part CENTER_CROP shows, shrunk to FROST_WIDTH and box blurred; drawn scaled up it reads as frosted glass. */
    private Bitmap frost(Bitmap source) {
        float scale = Math.max((float) width / source.getWidth(), (float) height / source.getHeight());
        int visibleW = Math.round(width / scale), visibleH = Math.round(height / scale);
        int frostH = Math.max(1, FROST_WIDTH * height / width);
        Matrix shrink = new Matrix();
        shrink.setScale((float) FROST_WIDTH / visibleW, (float) frostH / visibleH);
        Bitmap small = Bitmap.createBitmap(source, (source.getWidth() - visibleW) / 2,
                (source.getHeight() - visibleH) / 2, visibleW, visibleH, shrink, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < FROST_PASSES; pass++) {
            boxBlur(pixels, scratch, w, h, 1, w);
            boxBlur(scratch, pixels, h, w, w, 1);
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * One box blur pass along lines of {@code length} pixels, {@code step} apart within a line and {@code stride}
     * apart between lines; edges repeat the border pixel.
     */
    private static void boxBlur(int[] in, int[] out, int length, int lines, int step, int stride) {
        int span = FROST_RADIUS * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * stride;
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0;
                for (int k = -FROST_RADIUS; k <= FROST_RADIUS; k++) {
                    int c = in[base + Math.min(length - 1, Math.max(0, i + k)) * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                out[base + i * step] = 0xFF000000 | (r / span) << 16 | (g / span) << 8 | (b / span);
            }
        }
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
