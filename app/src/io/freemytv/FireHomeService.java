package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Fire TV (Fire OS 8) won't let another app be the home screen: Amazon's launcher stays the system's home. So, in Free
 * My TV mode, this service sees Amazon's launcher come to the front (the Home button, or Back out of an app) and opens
 * ours on top of it, a quarter of a second later. It reads only which of the launcher's screens opened: no content.
 * On after the switch to Free My TV; Original takes it out. Not used on any other TV.
 */
public class FireHomeService extends AccessibilityService {
    static final String LAUNCHER = "com.amazon.tv.launcher";
    private long launchedAt;
    private final Set<String> seen = new HashSet<>();

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || !LAUNCHER.contentEquals(pkg)) return;
        String name = cls.toString();
        // Each of the launcher's screens once in the log, to see which ones show up on this Fire OS version.
        if (seen.add(name)) AppLog.w("fire launcher: " + name);
        // Its screens (activities), not its menus and dialogs.
        if (!name.contains("Activity")) return;
        long now = SystemClock.uptimeMillis();
        if (now - launchedAt < 800 || Watchdog.crashedTooOften(this)) return;
        launchedAt = now;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setClassName(HomeMode.PKG, HomeMode.PKG + ".HomeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ex) {
            AppLog.w("fire home: " + ex.getMessage());
        }
    }

    /**
     * Bound in the first minutes after the stick starts: Amazon's home came up before this service could see it, so
     * nothing else can be in front. Ours goes on top once (as couch-tv's FireWatch does), unless it keeps crashing.
     */
    @Override protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS || Watchdog.crashedTooOften(this)) return;
        AppLog.w("fire home: bound just after a restart, opening Free My TV");
        launchedAt = SystemClock.uptimeMillis();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setClassName(HomeMode.PKG, HomeMode.PKG + ".HomeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ex) {
            AppLog.w("fire home: " + ex.getMessage());
        }
    }

    private static final long BOOT_WINDOW_MS = 3 * 60_000L;

    @Override public void onInterrupt() {}
}
