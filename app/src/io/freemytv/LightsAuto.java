package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The LED strip follows the TV (the strip is on mains power, so it stays lit when the TV goes off):
 *  - the TV goes to standby: the strip turns off;
 *  - the TV wakes up in the evening, or the evening comes while it's on: the strip turns on, in its last colour.
 *    Evening = from sunset (30 min before it when it rains) to sunrise, from the weather (HomeData).
 * Once per evening while the TV stays on, so turning the strip off by hand sticks until the next wake-up.
 *
 * Runs in one process only (two would send everything twice): the main one on a Fire TV, kept alive by
 * FireHomeService; the home screen's one elsewhere. The screen broadcasts only reach a receiver registered at runtime.
 */
final class LightsAuto {
    private LightsAuto() {}

    private static final long TICK = 2 * 60_000L, MIN = 60_000L;
    private static Context app;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean screenOn = true;

    static void start(Context c, String process) {
        if (!Lights.configured() || app != null) return;
        String pkg = c.getPackageName();
        boolean mine = HomeMode.FIRE ? pkg.equals(process) : (pkg + ":home").equals(process);
        if (!mine) return;
        app = c.getApplicationContext();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) off();
                else woke();
            }
        }, f);
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();
        MAIN.postDelayed(tick, MIN);
    }

    private static void off() {
        screenOn = false;
        MAIN.removeCallbacks(tick);
        if (!Lights.autoOff(app) || !Lights.on(app)) return;
        // The stick is going to sleep: a few seconds awake to reach the strip.
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (pm != null) pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeMyTV:lights").acquire(10_000);
        Lights.setOn(app, false, null);
        AppLog.w("lights: off with the TV");
    }

    private static void woke() {
        screenOn = true;
        Lights.setFiredAt(app, 0);   // a new wake-up: the evening may turn it on again
        MAIN.removeCallbacks(tick);
        HomeData.get(app).refreshIfStale();
        MAIN.post(tick);
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!screenOn) return;
            HomeData.get(app).refreshIfStale();
            long now = System.currentTimeMillis();
            boolean fired = now - Lights.firedAt(app) < 12 * 60 * MIN;
            if (Lights.autoOn(app) && !fired && evening(now)) {
                Lights.setFiredAt(app, now);
                if (!Lights.on(app)) {
                    Lights.setOn(app, true, null);
                    AppLog.w("lights: on for the evening");
                }
            }
            MAIN.postDelayed(this, TICK);
        }
    };

    /** From sunset (30 min before it when it rains) to sunrise. Without weather yet: 6 pm to 7 am. */
    static boolean evening(long now) {
        HomeData.Weather w = HomeData.get(app).weather;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(w.sunrise, 7 * 60 * MIN), set = Shaders.timeOfDay(w.sunset, 18 * 60 * MIN);
        boolean rain = w.raining || (w.rainStarts != 0 && w.rainStarts - now < 60 * MIN) || rainy(w.code);
        return t >= set - (rain ? 30 * MIN : 0) || t < rise;
    }

    /** Open-Meteo's codes for drizzle, rain, showers and thunderstorms. */
    private static boolean rainy(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }
}
