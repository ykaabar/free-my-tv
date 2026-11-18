package io.freemytv;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** ModeJob's work as a started service, for Fire OS (see ModeJob.schedule). Main process, one task at a time. */
public class ModeService extends Service {
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int pending;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? "" : String.valueOf(intent.getStringExtra("action"));
        final String mode = intent == null ? "" : String.valueOf(intent.getStringExtra("mode"));
        final int id = startId;
        pending++;
        AppLog.w("mode service: " + action + " " + mode);
        worker.execute(() -> {
            ModeJob.run(getApplicationContext(), action, mode);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (--pending == 0) stopSelf(id);
            });
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        worker.shutdown();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
