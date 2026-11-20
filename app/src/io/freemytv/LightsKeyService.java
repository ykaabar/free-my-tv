package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Fire TV, with an LED strip set up: the remote's Menu key, from any app. Short: the strip on or off. Held (0.6 s): a
 * small panel at the bottom of the screen (on/off, brightness, the four moods), ← → OK, Back or Menu closes it, and it
 * closes by itself after 8 s untouched. Amazon keeps Home for itself (tested: a key filter never sees it); Menu only
 * serves the on-screen keyboard, so it's left alone while one is up.
 *
 * The system waits for this filter on every key: it lives alone in the :keys process, reads only Menu (and, while
 * its panel is up, the arrows, OK and Back) and does no heavy work in onKeyEvent; the strip is reached in Lights'
 * own background thread. Its windows are only checked for an input method; it reads no content.
 */
public class LightsKeyService extends AccessibilityService {
    private static final long HOLD = 600, CLOSE_AFTER = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean menuDown, held;
    private Panel panel;

    private final Runnable hold = () -> {
        held = true;
        showPanel();
    };
    private final Runnable autoClose = this::hidePanel;

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (panel != null) return panelKey(code, down);
        if (code != KeyEvent.KEYCODE_MENU || !Lights.configured()) return false;
        if (down) {
            if (e.getRepeatCount() > 0) return true;
            if (keyboardUp()) return false;   // the on-screen keyboard uses Menu
            menuDown = true;
            held = false;
            handler.postDelayed(hold, HOLD);
            return true;
        }
        if (!menuDown) return false;          // its press went through (a keyboard was up): so does its release
        menuDown = false;
        handler.removeCallbacks(hold);
        if (!held) Lights.toggle(this, null);
        return true;
    }

    private boolean keyboardUp() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** While the panel is up it takes the arrows, OK, Back and Menu; every other key goes on as usual. */
    private boolean panelKey(int code, boolean down) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) panel.move(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) panel.apply();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (!down) {
                    if (menuDown) {        // the release of the hold that opened it
                        menuDown = false;
                        return true;
                    }
                    hidePanel();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            default:
                return false;
        }
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, CLOSE_AFTER);
        return true;
    }

    private void showPanel() {
        if (panel != null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        panel = new Panel(this);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM;
        try {
            wm.addView(panel, p);
            panel.setAlpha(0);
            panel.setTranslationY(40);
            panel.animate().alpha(1).translationY(0).setDuration(200).start();
            handler.postDelayed(autoClose, CLOSE_AFTER);
        } catch (Exception e) {
            AppLog.w("lights popup: " + e);
            panel = null;
        }
    }

    private void hidePanel() {
        handler.removeCallbacks(autoClose);
        if (panel == null) return;
        final Panel gone = panel;
        panel = null;
        gone.animate().alpha(0).translationY(40).setDuration(160).withEndAction(() -> {
            try {
                ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(gone);
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (panel != null) {
            try {
                ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
        super.onDestroy();
    }

    /**
     * The strip's small panel: [on/off] [−] [brightness] [+] [Cinema] [Dim] [Evening] [Reading]. Drawn by hand, like
     * the home screen's panel (HomeView.drawLightsPanel), in its colours.
     */
    private static final class Panel extends View {
        private static final int ITEMS = 3 + Lights.MOODS;   // on/off, −, +, then the moods
        private final Context c;
        private int focus;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title, text, dark, value;
        private final RectF card = new RectF(), box = new RectF();
        private final float k;

        Panel(Context c) {
            super(c);
            this.c = c;
            k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            title = paint(medium, 26, 0xFFF5F3EE);
            text = paint(medium, 22, 0xFFF5F3EE);
            text.setTextAlign(Paint.Align.CENTER);
            dark = paint(medium, 22, 0xFF0B0D12);
            dark.setTextAlign(Paint.Align.CENTER);
            value = paint(medium, 22, 0xFFC9CBD1);
            value.setTextAlign(Paint.Align.CENTER);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4 * k);
            ring.setColor(0xFFF5F3EE);
            int mood = Lights.mood(c);
            focus = mood >= 0 ? 3 + mood : 0;
        }

        private Paint paint(Typeface t, float size, int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(t);
            p.setTextSize(size * k);
            p.setColor(color);
            return p;
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(220 * k));
        }

        void move(int dir) {
            focus = Math.max(0, Math.min(ITEMS - 1, focus + dir));
            invalidate();
        }

        void apply() {
            if (focus == 0) Lights.toggle(c, null);
            else if (focus <= 2) Lights.setBrightness(c, Math.max(10, Math.min(100, (Lights.brightness(c) + 5) / 10 * 10 + (focus == 2 ? 10 : -10))), null);
            else Lights.setMood(c, focus - 3, null);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            boolean on = Lights.on(c);
            int mood = Lights.mood(c);
            float w = getWidth(), cw = 1240 * k, left = (w - cw) / 2, top = 20 * k;
            card.set(left, top, left + cw, top + 170 * k);
            fill.setColor(0xF2161A22);
            cv.drawRoundRect(card, 32 * k, 32 * k, fill);
            cv.drawText(Ui.tr("Fita de LED", "LED strip", "Tira LED"), left + 36 * k, top + 46 * k, title);
            float y = top + 74 * k, h = 64 * k, x = left + 36 * k;
            // on/off
            box.set(x, y, x + 150 * k, y + h);
            pill(cv, on, on ? Ui.tr("Acesa", "Light on", "Encendida") : Ui.tr("Apagada", "Light off", "Apagada"), 0);
            x += 170 * k;
            // brightness
            box.set(x, y, x + 64 * k, y + h);
            pill(cv, false, "−", 1);
            cv.drawText(Lights.brightness(c) + " %", x + 64 * k + 50 * k, y + h / 2 + 8 * k, value);
            x += 164 * k;
            box.set(x, y, x + 64 * k, y + h);
            pill(cv, false, "+", 2);
            x += 92 * k;
            for (int i = 0; i < Lights.MOODS; i++) {
                String name = Lights.moodName(i);
                float pw = text.measureText(name) + 56 * k;
                box.set(x, y, x + pw, y + h);
                pill(cv, on && i == mood, name, 3 + i);
                x += pw + 14 * k;
            }
        }

        private void pill(Canvas cv, boolean lit, String label, int index) {
            fill.setColor(lit ? 0xFFF5F3EE : 0x1AF5F3EE);
            cv.drawRoundRect(box, 16 * k, 16 * k, fill);
            cv.drawText(label, box.centerX(), box.centerY() + 8 * k, lit ? dark : text);
            if (index == focus) {
                box.inset(-7 * k, -7 * k);
                cv.drawRoundRect(box, 22 * k, 22 * k, ring);
            }
        }
    }
}
