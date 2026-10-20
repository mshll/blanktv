package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.format.DateFormat;

/** Choices made in the launcher's settings on the TV (Config.SETTINGS_NAME). */
final class Choices {
    static final String WALLPAPER_MODE = "wallpaper_mode";
    static final String ROTATE = "rotate", FIXED = "fixed", BLACK = "black";
    static final String WALLPAPER_MINUTES = "wallpaper_minutes";
    static final String WALLPAPER_DIM = "wallpaper_dim";
    static final String CLOCK_24H = "clock_24h";
    static final String DATE_STYLE = "date_style";
    static final String DATE_LONG = "long", DATE_SHORT = "short", DATE_OFF = "off";
    static final String SCREENSAVER_MINUTES = "screensaver_minutes";
    static final String BACK_SCREENSAVER = "back_screensaver";
    static final String SHOW_NAMES = "show_names";
    static final String COLUMNS = "columns";
    private static final String LEGACY_FIXED = "wallpaper_fixed";

    private Choices() {
    }

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static String wallpaperMode(Context context) {
        SharedPreferences p = of(context);
        return p.getString(WALLPAPER_MODE, p.getBoolean(LEGACY_FIXED, false) ? FIXED : ROTATE);
    }

    static boolean clock24h(Context context) {
        return of(context).getBoolean(CLOCK_24H, DateFormat.is24HourFormat(context));
    }

    static String dateStyle(Context context) {
        return of(context).getString(DATE_STYLE, DATE_LONG);
    }

    /** 0 means never. */
    static int screensaverMinutes(Context context) {
        return of(context).getInt(SCREENSAVER_MINUTES, context.getResources().getInteger(R.integer.screensaver_minutes));
    }

    static boolean backStartsScreensaver(Context context) {
        return of(context).getBoolean(BACK_SCREENSAVER, true);
    }

    static boolean showNames(Context context) {
        return of(context).getBoolean(SHOW_NAMES, true);
    }

    /** Android TV starts the screensaver after the screen-off timeout; false without the WRITE_SETTINGS grant. */
    static boolean applyScreensaver(Context context) {
        if (!Settings.System.canWrite(context)) return false;
        int minutes = screensaverMinutes(context);
        return Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                minutes > 0 ? minutes * 60_000 : Integer.MAX_VALUE);
    }
}
