package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * A test, off unless turned on by hand (adb): does the remote's Home key reach an accessibility key filter on this TV,
 * and Menu? Logs every key it sees (down, up, repeats, long press) and lets each one through untouched. For a long
 * press on Home to open the LED strip's panel later, Home must show up here. Alone in the :keys process: the system
 * waits for the filter on every key.
 */
public class KeyProbeService extends AccessibilityService {
    private long downAt;

    @Override protected boolean onKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0) downAt = SystemClock.uptimeMillis();
        AppLog.w("key probe: " + KeyEvent.keyCodeToString(e.getKeyCode()) + " (" + e.getKeyCode() + ") "
                + (down ? "down" : "up") + (e.getRepeatCount() > 0 ? " repeat " + e.getRepeatCount() : "")
                + (e.isLongPress() ? " long" : "") + (down ? "" : " after " + (SystemClock.uptimeMillis() - downAt) + " ms"));
        return false;   // never keeps a key
    }

    @Override protected void onServiceConnected() {
        AppLog.w("key probe: on");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
