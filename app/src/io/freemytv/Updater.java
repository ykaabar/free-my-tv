package io.freemytv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The family edition updates itself: twice a day at most, from the home screen, it looks at the GitHub releases of
 * Config.UPDATES for a "family-<number>" newer than this build, downloads its APK and hands it to Android's package
 * installer, which asks on screen "install this update?" (once "install unknown apps" is allowed for Free My TV:
 * fire/menage-fire.bat does it). Signed with the same key, it installs over this one and keeps the settings.
 */
final class Updater {
    private Updater() {}

    private static final long EVERY = 12 * 3600_000L;
    private static final String PREFIX = "family-";
    private static volatile boolean busy;

    static boolean enabled() {
        return !Config.UPDATES.trim().isEmpty() && Config.BUILD > 1;
    }

    /** From the home screen's resume: cheap when it's not time yet. */
    static void maybeCheck(Context c) {
        if (!enabled() || busy) return;
        SharedPreferences sp = c.getSharedPreferences("updates", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - sp.getLong("checked", 0) < EVERY) return;
        sp.edit().putLong("checked", now).apply();
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                check(app);
            } catch (Exception e) {
                AppLog.w("update: " + e);
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    private static void check(Context c) throws Exception {
        JSONArray releases = new JSONArray(read(new URL("https://api.github.com/repos/" + Config.UPDATES.trim()
                + "/releases?per_page=10")));
        int best = Config.BUILD;
        String url = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            String tag = r.optString("tag_name");
            if (!tag.startsWith(PREFIX) || r.optBoolean("draft")) continue;
            int n;
            try {
                n = Integer.parseInt(tag.substring(PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            JSONArray assets = r.optJSONArray("assets");
            if (n <= best || assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                if (a.optString("name").endsWith(".apk")) {
                    best = n;
                    url = a.optString("browser_download_url");
                }
            }
        }
        if (url == null) return;
        AppLog.w("update: build " + best + " found (this one is " + Config.BUILD + ")");
        File apk = new File(c.getCacheDir(), "update.apk");
        download(new URL(url), apk);
        install(c, apk);
    }

    private static String read(URL url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) url.openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    private static void download(URL url, File to) throws Exception {
        HttpURLConnection h = (HttpURLConnection) url.openConnection();   // GitHub redirects to its file host (https too)
        h.setConnectTimeout(15_000);
        h.setReadTimeout(60_000);
        h.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } finally {
            h.disconnect();
        }
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, 9, new Intent(c, UpdateReceiver.class), flags);
            s.commit(done.getIntentSender());
        }
        AppLog.w("update: handed to the installer");
    }
}
