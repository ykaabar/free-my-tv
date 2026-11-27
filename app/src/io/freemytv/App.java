package io.freemytv;

import android.app.Application;
import android.os.Build;

/** Gives Prefs a context in every process; starts the LED strip's automations in the one that runs them. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        LightsAuto.start(this, processName());
    }

    /** This process's name ("io.freemytv:home"…): Application.getProcessName() from Android 9, /proc before. */
    static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b), end = 0;
            while (end < Math.max(0, n) && b[end] != 0) end++;
            return new String(b, 0, end, "UTF-8");
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
