package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * The launcher's own settings, tvOS style: categories on the left, the focused category's options on the right,
 * over the blurred wallpaper. OK on an option steps to its next value. Home rebuilds itself when anything changed.
 */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_ACTION = "action";
    static final String EXTRA_CHANGED = "changed";
    static final String ACTION_REORDER = "reorder";
    static final String ACTION_DOCK = "dock";
    static final String ACTION_LIBRARY = "library";

    private static final int[] ROTATION_MINUTES = {15, 30, 60, 240, 1440};
    private static final int[] DIM_PERCENT = {0, 20, 35, 50, 65};
    private static final int[] SCREENSAVER_MINUTES = {5, 10, 15, 30, 60, 0};
    private static final int[] COLUMNS = {4, 5, 6, 7};
    private static final String[] DATE_STYLES = {Choices.DATE_LONG, Choices.DATE_SHORT, Choices.DATE_OFF};

    private SharedPreferences choices;
    private SharedPreferences rotation;
    private Config config;
    private LinearLayout categories;
    private LinearLayout pane;
    private ScrollView paneScroll;
    private TextView paneTitle;
    private View selectedCategory;
    private Runnable buildSelected;
    private boolean changed;
    private float density;
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        density = getResources().getDisplayMetrics().density;
        choices = Choices.of(this);
        rotation = getSharedPreferences(WallpaperPlayer.ROTATION_PREFS, MODE_PRIVATE);
        config = Config.load(this);
        categories = findViewById(R.id.categories);
        pane = findViewById(R.id.pane);
        paneScroll = findViewById(R.id.paneScroll);
        paneTitle = findViewById(R.id.paneTitle);
        setBackdrop();

        if (!config.wallpapers.isEmpty()) addCategory(R.drawable.ic_image, R.string.settings_wallpaper, this::wallpaperPane);
        addCategory(R.drawable.ic_clock, R.string.settings_clock, this::clockPane);
        addCategory(R.drawable.ic_moon, R.string.settings_screensaver, this::screensaverPane);
        addCategory(R.drawable.ic_apps, R.string.settings_home, this::homePane);
        addCategory(R.drawable.ic_picture, R.string.settings_system, this::systemPane);
        categories.getChildAt(0).requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbnails.shutdownNow();
    }

    /** Back from an option returns to the categories first, as on tvOS. */
    @Override
    public void onBackPressed() {
        View focus = getCurrentFocus();
        if (focus != null && focus.getParent() != categories && selectedCategory != null) selectedCategory.requestFocus();
        else finish();
    }

    @Override
    public void finish() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED, new Intent().putExtra(EXTRA_CHANGED, changed));
        super.finish();
    }

    /** The blurred wallpaper Home shows, darkened; plain black with no wallpaper. */
    private void setBackdrop() {
        Bitmap frost = WallpaperPlayer.shownFrost();
        View root = findViewById(R.id.settingsRoot);
        if (frost == null) {
            root.setBackgroundColor(0xFF0B0B0D);
            return;
        }
        BitmapDrawable blur = new BitmapDrawable(getResources(), frost);
        blur.setFilterBitmap(true);
        root.setBackground(new LayerDrawable(new Drawable[]{blur, new ColorDrawable(0x99000000)}));
    }

    // ---- categories ------------------------------------------------------------------------------

    private void addCategory(int icon, int title, Runnable build) {
        View row = LayoutInflater.from(this).inflate(R.layout.settings_category, categories, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnFocusChangeListener((v, focused) -> {
            if (!focused || selectedCategory == v) return;
            if (selectedCategory != null) selectedCategory.setSelected(false);
            selectedCategory = v;
            v.setSelected(true);
            paneTitle.setText(title);
            buildSelected = build;
            rebuild(-1);
        });
        row.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int index = categories.indexOfChild(v);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    focusPane(0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return index == 0;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return index == categories.getChildCount() - 1;
                default:
                    return false;
            }
        });
        categories.addView(row);
    }

    /** Rebuilds the selected category's options; focus goes back to the option at {@code focusIndex}, if any. */
    private void rebuild(int focusIndex) {
        // Removing the focused option would hand focus to the first category, which then opens its own pane.
        if (focusIndex >= 0) selectedCategory.requestFocus();
        pane.removeAllViews();
        buildSelected.run();
        if (focusIndex >= 0) focusPane(focusIndex);
        else paneScroll.scrollTo(0, 0);
    }

    private void focusPane(int index) {
        if (pane.getChildCount() == 0) return;
        View row = pane.getChildAt(Math.min(index, pane.getChildCount() - 1));
        if (row instanceof HorizontalScrollView) {
            LinearLayout strip = (LinearLayout) ((HorizontalScrollView) row).getChildAt(0);
            for (int i = 0; i < strip.getChildCount(); i++) {
                if (strip.getChildAt(i).isSelected()) {
                    strip.getChildAt(i).requestFocus();
                    return;
                }
            }
            strip.getChildAt(0).requestFocus();
            return;
        }
        if (!row.isFocusable() && index + 1 < pane.getChildCount()) {
            focusPane(index + 1);
            return;
        }
        row.requestFocus();
    }

    private int focusedPaneIndex() {
        View focus = getCurrentFocus();
        while (focus != null && focus.getParent() != pane) {
            focus = focus.getParent() instanceof View ? (View) focus.getParent() : null;
        }
        return focus == null ? 0 : pane.indexOfChild(focus);
    }

    // ---- rows ------------------------------------------------------------------------------------

    private View row(int title, CharSequence value, int hint, Runnable onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.settings_row, pane, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        if (hint != 0) {
            TextView hintView = row.findViewById(R.id.hint);
            hintView.setText(hint);
            hintView.setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(v -> onClick.run());
        row.setOnKeyListener(this::paneKey);
        pane.addView(row);
        return row;
    }

    /** An option with a fixed list of values; OK steps to the next one and wraps. */
    private void choice(int title, String[] labels, int current, int hint, IntConsumer pick) {
        int index = Math.max(0, current);
        row(title, labels[index], hint, () -> {
            pick.accept((index + 1) % labels.length);
            changed = true;
            rebuild(focusedPaneIndex());
        });
    }

    private void action(int title, CharSequence value, Runnable run) {
        row(title, value == null ? "›" : value, 0, run);
    }

    private void note(CharSequence text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0x8CFFFFFF);
        view.setTextSize(14f);
        view.setPadding((int) (22 * density), (int) (10 * density), (int) (22 * density), 0);
        pane.addView(view);
    }

    /** Left goes back to the categories; Up and Down stop at the ends instead of jumping across to them. */
    private boolean paneKey(View v, int code, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        View row = v;
        while (row.getParent() != pane) row = (View) row.getParent();
        int index = pane.indexOfChild(row);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row != v && ((ViewGroup) v.getParent()).indexOfChild(v) > 0) return false;
                selectedCategory.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return index == 0;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return lastFocusableRow() == index;
            default:
                return false;
        }
    }

    private int lastFocusableRow() {
        for (int i = pane.getChildCount() - 1; i >= 0; i--) if (pane.getChildAt(i).isFocusable()) return i;
        return 0;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    private String minutes(int value) {
        if (value == 0) return getString(R.string.settings_never);
        if (value % 1440 == 0) return getResources().getQuantityString(R.plurals.settings_days, value / 1440, value / 1440);
        if (value % 60 == 0) return getResources().getQuantityString(R.plurals.settings_hours, value / 60, value / 60);
        return getString(R.string.settings_minutes, value);
    }

    private String[] minuteLabels(int[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = minutes(values[i]);
        return labels;
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    /** Back to Home, which then runs the action (reorder, edit dock, app library). */
    private void finishWith(String homeAction) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, homeAction).putExtra(EXTRA_CHANGED, changed));
        super.finish();
    }

    // ---- panes -----------------------------------------------------------------------------------

    private void wallpaperPane() {
        String mode = Choices.wallpaperMode(this);
        boolean several = config.wallpapers.size() > 1;
        if (!several && Choices.ROTATE.equals(mode)) mode = Choices.FIXED;
        thumbnailStrip(Choices.BLACK.equals(mode) ? "" : rotation.getString("image", ""));

        String[] modes = several ? new String[]{Choices.ROTATE, Choices.FIXED, Choices.BLACK}
                : new String[]{Choices.FIXED, Choices.BLACK};
        String[] modeLabels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeLabels[i] = getString(Choices.ROTATE.equals(modes[i]) ? R.string.settings_wallpaper_rotate
                    : Choices.FIXED.equals(modes[i]) ? R.string.settings_wallpaper_fixed : R.string.settings_wallpaper_black);
        }
        choice(R.string.settings_wallpaper_mode, modeLabels, indexOf(modes, mode), 0,
                i -> choices.edit().putString(Choices.WALLPAPER_MODE, modes[i]).apply());

        if (Choices.ROTATE.equals(mode)) {
            int minutes = config.wallpaperRotationMinutes;
            int current = indexOf(ROTATION_MINUTES, choices.getInt(Choices.WALLPAPER_MINUTES, minutes));
            choice(R.string.settings_wallpaper_every, minuteLabels(ROTATION_MINUTES), current < 0 ? 1 : current, 0,
                    i -> choices.edit().putInt(Choices.WALLPAPER_MINUTES, ROTATION_MINUTES[i]).apply());
        }
        if (!Choices.BLACK.equals(mode)) {
            String[] dims = new String[DIM_PERCENT.length];
            for (int i = 0; i < dims.length; i++) {
                dims[i] = DIM_PERCENT[i] == 0 ? getString(R.string.off) : getString(R.string.settings_percent, DIM_PERCENT[i]);
            }
            int current = indexOf(DIM_PERCENT, choices.getInt(Choices.WALLPAPER_DIM, config.wallpaperDim));
            choice(R.string.settings_wallpaper_dim, dims, current < 0 ? 2 : current, R.string.settings_wallpaper_dim_hint,
                    i -> choices.edit().putInt(Choices.WALLPAPER_DIM, DIM_PERCENT[i]).apply());
        }
    }

    /** Every wallpaper as a thumbnail; OK keeps that one on Home (fixed mode). */
    private void thumbnailStrip(String current) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, (int) (14 * density));
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength((int) (32 * density));
        LinearLayout strip = new LinearLayout(this);
        scroll.addView(strip);
        int w = (int) (136 * density), h = (int) (76 * density), gap = (int) (12 * density);
        List<File> files = config.wallpapers;
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setFocusable(true);
            thumb.setClickable(true);
            thumb.setForeground(getDrawable(R.drawable.settings_thumb_bg));
            thumb.setBackgroundColor(0x1FFFFFFF);
            thumb.setClipToOutline(true);
            thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14 * density);
                }
            });
            thumb.setSelected(file.getName().equals(current));
            thumb.setContentDescription(file.getName());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMarginEnd(gap);
            strip.addView(thumb, lp);
            thumb.setOnKeyListener(this::paneKey);
            thumb.setOnClickListener(v -> {
                choices.edit().putString(Choices.WALLPAPER_MODE, Choices.FIXED).apply();
                rotation.edit().putString("image", file.getName()).putLong("changed", System.currentTimeMillis()).apply();
                changed = true;
                rebuild(0);
            });
            thumbnails.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap != null) thumb.post(() -> thumb.setImageBitmap(bitmap));
            });
        }
        pane.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void clockPane() {
        boolean h24 = Choices.clock24h(this);
        choice(R.string.settings_time_format, new String[]{getString(R.string.settings_12h), getString(R.string.settings_24h)},
                h24 ? 1 : 0, 0, i -> choices.edit().putBoolean(Choices.CLOCK_24H, i == 1).apply());
        Date now = new Date();
        String[] dates = {DateFormat.format(getString(R.string.date_format), now).toString().replaceAll("\\s+", " "),
                DateFormat.format(getString(R.string.date_format_short), now).toString(), getString(R.string.settings_hidden)};
        choice(R.string.settings_date, dates, indexOf(DATE_STYLES, Choices.dateStyle(this)), 0,
                i -> choices.edit().putString(Choices.DATE_STYLE, DATE_STYLES[i]).apply());
    }

    private void screensaverPane() {
        int current = indexOf(SCREENSAVER_MINUTES, Choices.screensaverMinutes(this));
        choice(R.string.settings_screensaver_after, minuteLabels(SCREENSAVER_MINUTES), current < 0 ? 1 : current, 0, i -> {
            choices.edit().putInt(Choices.SCREENSAVER_MINUTES, SCREENSAVER_MINUTES[i]).commit();
            if (!Choices.applyScreensaver(this)) {
                Toast.makeText(this, R.string.settings_screensaver_no_permission, Toast.LENGTH_LONG).show();
            }
        });
        choice(R.string.settings_back_screensaver, new String[]{onOff(false), onOff(true)},
                Choices.backStartsScreensaver(this) ? 1 : 0, R.string.settings_back_screensaver_hint,
                i -> choices.edit().putBoolean(Choices.BACK_SCREENSAVER, i == 1).apply());
        action(R.string.settings_screensaver_app, screensaverName(), () -> open(new Intent(Settings.ACTION_DREAM_SETTINGS)));
        action(R.string.settings_screensaver_now, null, () -> {
            open(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
            finish();
        });
    }

    /** The chosen screensaver's app name, or null when the TV doesn't say. */
    private CharSequence screensaverName() {
        String components = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        ComponentName dream = components == null ? null : ComponentName.unflattenFromString(components.split(",")[0]);
        if (dream == null) return null;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(dream.getPackageName(), 0).loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void homePane() {
        String[] columns = new String[COLUMNS.length];
        for (int i = 0; i < columns.length; i++) columns[i] = String.valueOf(COLUMNS[i]);
        choice(R.string.columns_label, columns, indexOf(COLUMNS, config.columns), 0,
                i -> choices.edit().putInt(Choices.COLUMNS, COLUMNS[i]).apply());
        choice(R.string.settings_show_names, new String[]{onOff(false), onOff(true)},
                Choices.showNames(this) ? 1 : 0, R.string.settings_show_names_hint,
                i -> choices.edit().putBoolean(Choices.SHOW_NAMES, i == 1).apply());
        action(R.string.settings_hide_apps, null, () -> finishWith(ACTION_LIBRARY));
        if (config.dockRow) action(R.string.dock_edit, null, () -> finishWith(ACTION_DOCK));
        action(R.string.reorder, null, () -> finishWith(ACTION_REORDER));
    }

    private void systemPane() {
        action(R.string.home_settings, null, () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        action(R.string.tv_settings, null, () -> open(new Intent(Settings.ACTION_SETTINGS)));
        try {
            note(getString(R.string.settings_version,
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
