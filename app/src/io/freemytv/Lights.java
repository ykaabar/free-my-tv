package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Tuya LED strip behind the TV (LSC Smart Connect, Smart Life…), over the local network with its local key
 * (config.properties: tuya_id, tuya_key, tuya_ip, tuya_version; tools/tuya_keys.py finds them). The light button on the
 * home screen goes through four moods: off, dim, cinema, white.
 *
 * Tuya lights speak one of two sets of data points: the newer one (20 on/off, 21 mode, 22 brightness 10-1000,
 * 23 white temperature 0-1000, 24 colour "hhhhssssvvvv") or the older one (1, 2, 3 = 25-255, 4 = 0-255,
 * 5 = "rrggbbhhhhssvv"). The first answer says which, and whether there's a white channel at all.
 */
final class Lights {
    private Lights() {}

    static final int OFF = 0, DIM = 1, CINEMA = 2, WHITE = 3, COUNT = 4;

    interface Done { void done(String error); }   // null = it worked

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int wanted = -1;          // the last mood asked for: quick presses only send the last one
    private static volatile boolean older, noWhite, known;

    static boolean configured() {
        return !Config.TUYA_ID.trim().isEmpty() && Config.TUYA_KEY.trim().length() == 16 && !Config.TUYA_IP.trim().isEmpty();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("lights", Context.MODE_PRIVATE);
    }

    static int mood(Context c) {
        return sp(c).getInt("mood", OFF);
    }

    static String name(int mood) {
        switch (mood) {
            case DIM: return Ui.tr("Suave", "Dim", "Tenue");
            case CINEMA: return Ui.tr("Cinema", "Cinema", "Cine");
            case WHITE: return Ui.tr("Branca", "White", "Blanca");
            default: return Ui.tr("Apagada", "Switched off", "Apagada");
        }
    }

    /** The next mood (OK on the light button): sent in the background, `done` on the main thread. */
    static int next(Context c, Done done) {
        int mood = (mood(c) + 1) % COUNT;
        set(c, mood, done);
        return mood;
    }

    static void set(Context c, int mood, Done done) {
        sp(c).edit().putInt("mood", mood).apply();
        wanted = mood;
        IO.execute(() -> {
            if (wanted != mood) return;   // a newer press is queued
            String error = null;
            try {
                send(c, mood);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("lights: " + error);
            }
            final String result = error;
            if (done != null) MAIN.post(() -> done.done(result));
        });
    }

    // MARK: - The strip

    private static void send(Context c, int mood) throws Exception {
        Exception last = null;
        for (String version : versions(c)) {
            Tuya t = new Tuya(Config.TUYA_IP, Config.TUYA_ID, Config.TUYA_KEY.trim(), version);
            try {
                t.connect(4000);
                if (!known) learn(t.status());
                JSONObject dps = dps(mood);
                t.set(dps);
                sp(c).edit().putString("version", version).apply();
                return;
            } catch (Exception e) {
                last = e;
            } finally {
                t.close();
            }
        }
        throw last != null ? last : new java.io.IOException("no answer");
    }

    /** tuya_version if set; else the one that worked last time first, then 3.5, 3.4, 3.3. */
    private static String[] versions(Context c) {
        String v = Config.TUYA_VERSION.trim();
        if (!v.isEmpty()) return new String[] {v};
        String last = sp(c).getString("version", "3.5");
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add(last);
        all.add("3.5");
        all.add("3.4");
        all.add("3.3");
        return all.toArray(new String[0]);
    }

    private static void learn(JSONObject status) {
        if (status == null) return;
        older = !status.has("20") && status.has("1");
        noWhite = older ? !status.has("4") : !status.has("23");
        known = true;
        AppLog.w("lights: " + (older ? "older" : "newer") + " data points" + (noWhite ? ", colour only" : ""));
    }

    private static JSONObject dps(int mood) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        String power = older ? "1" : "20";
        if (mood == OFF) return o.put(power, false);
        o.put(power, true);
        switch (mood) {
            case DIM:     // warm and low
                return noWhite ? colour(o, 30, 700, 220) : white(o, 220, 0);
            case CINEMA:  // a deep blue glow, low, behind the picture
                return colour(o, 235, 1000, 140);
            default:      // WHITE: neutral and bright
                return noWhite ? colour(o, 0, 0, 850) : white(o, 850, 450);
        }
    }

    /** brightness and temperature on the newer scale (0-1000). */
    private static JSONObject white(JSONObject o, int bright, int temp) throws org.json.JSONException {
        if (older) {
            return o.put("2", "white").put("3", Math.max(25, bright * 255 / 1000)).put("4", temp * 255 / 1000);
        }
        return o.put("21", "white").put("22", Math.max(10, bright)).put("23", temp);
    }

    /** hue 0-360, saturation and value on the newer scale (0-1000). */
    private static JSONObject colour(JSONObject o, int h, int s, int v) throws org.json.JSONException {
        if (older) {
            int rgb = android.graphics.Color.HSVToColor(new float[] {h, s / 1000f, v / 1000f});
            String hex = String.format(java.util.Locale.ROOT, "%06x%04x%02x%02x", rgb & 0xFFFFFF, h, s * 255 / 1000, v * 255 / 1000);
            return o.put("2", "colour").put("5", hex);
        }
        return o.put("21", "colour").put("24", String.format(java.util.Locale.ROOT, "%04x%04x%04x", h, s, v));
    }
}
