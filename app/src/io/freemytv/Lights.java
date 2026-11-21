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
 * (config.properties: tuya_id, tuya_key, tuya_ip, tuya_version; tools/tuya_keys.py finds them). OK on the light button
 * turns it on or off; held, it opens the strip's panel: brightness, a colour, a mood.
 *
 * Tuya lights speak one of two sets of data points: the newer one (20 on/off, 21 mode, 22 brightness 10-1000,
 * 23 white temperature 0-1000, 24 colour "hhhhssssvvvv") or the older one (1, 2, 3 = 25-255, 4 = 0-255,
 * 5 = "rrggbbhhhhssvv"). The first answer says which, and whether there's a white channel at all.
 */
final class Lights {
    private Lights() {}

    interface Done { void done(String error); }   // only called on an error

    /** The colours: warm white, white (temperature 0-1000), then hues (0-360) at full saturation. */
    static final int[] SWATCH_RGB = {0xFFFFC98A, 0xFFF3F1EA, 0xFF2F4BD8, 0xFF8B3FD9, 0xFFE5484D, 0xFF2FB36B};
    private static final int[] SWATCH_TEMP = {0, 550, -1, -1, -1, -1};
    private static final int[] SWATCH_HUE = {0, 0, 230, 275, 0, 140};
    /** The moods: cinema (a deep blue glow), dim (warm white, low), evening (warm amber), reading (bright white). */
    static final int CINEMA = 0, DIM = 1, EVENING = 2, READING = 3, MOODS = 4;
    private static final int[] MOOD_SWATCH = {2, 0, -1, 1};
    private static final int[] MOOD_BRIGHT = {14, 22, 45, 90};

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int sent;                 // the last change asked for: quick presses only send the last one
    private static volatile boolean older, noWhite, known;

    static boolean configured() {
        return !Config.TUYA_ID.trim().isEmpty() && Config.TUYA_KEY.trim().length() == 16 && !Config.TUYA_IP.trim().isEmpty();
    }

    /** Read again from disk on each call: LightsAuto may change it from the main process (Fire TV). */
    @SuppressWarnings("deprecation")
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("lights", Context.MODE_MULTI_PROCESS);
    }

    /** LightsAuto: on in the evening when the TV wakes up; off when it goes to standby. */
    static boolean autoOn(Context c) { return sp(c).getBoolean("autoOn", true); }
    /** How long after the TV goes to standby the strip turns off, in minutes: 0 at once, -1 never. */
    static final int[] OFF_DELAYS = {0, 1, 5, 15, 30, -1};
    private static String[] offDelayNames;

    static int offDelay(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains("offDelay")) return p.getInt("offDelay", 0);
        return p.getBoolean("autoOff", true) ? 0 : -1;   // the on/off switch it replaces
    }

    static void setOffDelay(Context c, int minutes) { sp(c).edit().putInt("offDelay", minutes).apply(); }

    /** "Right away", "After 5 min", "Never": built once, the panel draws them on every frame. */
    static String offDelayName(int minutes) {
        if (offDelayNames == null) {
            String[] n = new String[OFF_DELAYS.length];
            for (int i = 0; i < n.length; i++) {
                int m = OFF_DELAYS[i];
                n[i] = m == 0 ? Ui.tr("Na hora", "Right away", "Al instante") : m < 0 ? Ui.tr("Nunca", "Never", "Nunca")
                        : Ui.tr("Depois de ", "After ", "Después de ") + m + " min";
            }
            offDelayNames = n;
        }
        for (int i = 0; i < OFF_DELAYS.length; i++) if (OFF_DELAYS[i] == minutes) return offDelayNames[i];
        return offDelayNames[0];
    }

    /** The next (or previous) delay in OFF_DELAYS, wrapping around. */
    static int nextOffDelay(int minutes, int dir) {
        int at = 0;
        for (int i = 0; i < OFF_DELAYS.length; i++) if (OFF_DELAYS[i] == minutes) at = i;
        return OFF_DELAYS[(at + dir + OFF_DELAYS.length) % OFF_DELAYS.length];
    }
    static void setAutoOn(Context c, boolean on) { sp(c).edit().putBoolean("autoOn", on).apply(); }
    static long firedAt(Context c) { return sp(c).getLong("firedAt", 0); }
    static void setFiredAt(Context c, long at) { sp(c).edit().putLong("firedAt", at).apply(); }

    static boolean on(Context c) { return sp(c).getBoolean("on", false); }
    static int brightness(Context c) { return sp(c).getInt("bright", 70); }
    /** The colour picked (index in SWATCH_RGB), or -1 for the evening's own amber. */
    static int swatch(Context c) { return sp(c).getInt("swatch", 0); }
    /** The mood picked, or -1 once the colour or the brightness was changed by hand. */
    static int mood(Context c) { return sp(c).getInt("mood", -1); }

    static String moodName(int mood) {
        switch (mood) {
            case CINEMA: return Ui.tr("Cinema", "Cinema", "Cine");
            case DIM: return Ui.tr("Suave", "Dim", "Tenue");
            case EVENING: return Ui.tr("Noite", "Evening", "Velada");
            default: return Ui.tr("Leitura", "Reading", "Lectura");
        }
    }

    static String swatchName(int i) {
        switch (i) {
            case 0: return Ui.tr("Branco quente", "Warm white", "Blanco cálido");
            case 1: return Ui.tr("Branco", "White", "Blanco");
            case 2: return Ui.tr("Azul noite", "Night blue", "Azul noche");
            case 3: return Ui.tr("Violeta", "Violet", "Violeta");
            case 4: return Ui.tr("Vermelho", "Red", "Rojo");
            default: return Ui.tr("Verde", "Green", "Verde");
        }
    }

    // MARK: - Changes (saved at once, sent in the background)

    static void toggle(Context c, Done done) {
        setOn(c, !on(c), done);
    }

    static void setOn(Context c, boolean on, Done done) {
        sp(c).edit().putBoolean("on", on).apply();
        push(c, done);
    }

    /** 5 to 100 %. */
    static void setBrightness(Context c, int percent, Done done) {
        sp(c).edit().putBoolean("on", true).putInt("bright", Math.max(5, Math.min(100, percent))).putInt("mood", -1).apply();
        push(c, done);
    }

    static void setSwatch(Context c, int i, Done done) {
        sp(c).edit().putBoolean("on", true).putInt("swatch", i).putInt("mood", -1).apply();
        push(c, done);
    }

    static void setMood(Context c, int mood, Done done) {
        sp(c).edit().putBoolean("on", true).putInt("mood", mood).putInt("swatch", moodSwatch(c, mood))
                .putInt("bright", moodBright(c, mood)).apply();
        push(c, done);
    }

    /** A mood's colour and brightness: the person's own (OK held on it in the panel), else the built-in ones. */
    static int moodSwatch(Context c, int mood) { return sp(c).getInt("moodSwatch" + mood, MOOD_SWATCH[mood]); }
    static int moodBright(Context c, int mood) { return sp(c).getInt("moodBright" + mood, MOOD_BRIGHT[mood]); }

    /**
     * OK held on a mood: keeps the strip's colour and brightness in it. Held on the mood already in use and left as it
     * is, it goes back to the built-in one instead. True when it was kept, false when it went back.
     */
    static boolean keepMood(Context c, int mood) {
        SharedPreferences p = sp(c);
        if (mood(c) == mood && p.contains("moodSwatch" + mood)) {
            p.edit().remove("moodSwatch" + mood).remove("moodBright" + mood).apply();
            setMood(c, mood, null);
            return false;
        }
        p.edit().putInt("moodSwatch" + mood, swatch(c)).putInt("moodBright" + mood, brightness(c))
                .putBoolean("on", true).putInt("mood", mood).apply();
        push(c, null);
        return true;
    }

    private static void push(Context c, Done done) {
        final int id = ++sent;
        final boolean on = on(c);
        final int bright = brightness(c), swatch = swatch(c);
        IO.execute(() -> {
            if (id != sent) return;   // a newer change is queued
            try {
                send(c, on, bright, swatch);
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("lights: " + error);
                if (done != null) MAIN.post(() -> done.done(error));
            }
        });
    }

    // MARK: - The strip

    private static void send(Context c, boolean on, int bright, int swatch) throws Exception {
        Exception last = null;
        for (String version : versions(c)) {
            Tuya t = new Tuya(Config.TUYA_IP, Config.TUYA_ID, Config.TUYA_KEY.trim(), version);
            try {
                t.connect(4000);
                if (!known) learn(t.status());
                t.set(dps(on, bright, swatch));
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
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add(sp(c).getString("version", "3.5"));
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

    private static JSONObject dps(boolean on, int bright, int swatch) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        String power = older ? "1" : "20";
        if (!on) return o.put(power, false);
        o.put(power, true);
        int v = bright * 10;   // 0-1000
        if (swatch < 0) return colour(o, 28, 850, v);   // the evening's warm amber
        int temp = SWATCH_TEMP[swatch];
        if (temp >= 0) return noWhite ? colour(o, 30, temp == 0 ? 600 : 0, v) : white(o, v, temp);
        return colour(o, SWATCH_HUE[swatch], 1000, v);
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
        v = Math.max(10, v);
        if (older) {
            int rgb = android.graphics.Color.HSVToColor(new float[] {h, s / 1000f, v / 1000f});
            String hex = String.format(java.util.Locale.ROOT, "%06x%04x%02x%02x", rgb & 0xFFFFFF, h, s * 255 / 1000, v * 255 / 1000);
            return o.put("2", "colour").put("5", hex);
        }
        return o.put("21", "colour").put("24", String.format(java.util.Locale.ROOT, "%04x%04x%04x", h, s, v));
    }
}
