package io.freemytv;

import android.util.Log;

/** The app's log (adb logcat -s FreeMyTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        // Warning level: Fire OS keeps only warnings and errors from apps in the log.
        Log.w("FreeMyTV", message);
    }

    static void w(String message, Throwable t) {
        Log.w("FreeMyTV", message, t);
    }
}
