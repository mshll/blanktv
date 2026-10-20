package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Philips Live TV opens the last input at boot from its own receiver, which the shell cannot disable, so Home
 * is brought back on top once it has. Once per boot and only shortly after it, so a late or repeated broadcast
 * never pulls the viewer out of an app. Also re-applies the screensaver delay that Philips resets at boot.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final long BOOT_WINDOW_MS = 90_000;
    private static final long[] DELAYS_MS = {500, 4000};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS || !firstForThisBoot(context)) return;
        Choices.applyScreensaver(context);
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delay : DELAYS_MS) handler.postDelayed(() -> showHome(app), delay);
        handler.postDelayed(result::finish, DELAYS_MS[DELAYS_MS.length - 1]);
    }

    private static boolean firstForThisBoot(Context context) {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = context.getSharedPreferences("boot", Context.MODE_PRIVATE);
        if (prefs.getInt("handled_boot", -2) == boot) return false;
        prefs.edit().putInt("handled_boot", boot).apply();
        return true;
    }

    private static void showHome(Context context) {
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
