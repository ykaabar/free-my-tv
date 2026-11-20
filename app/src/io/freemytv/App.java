package io.freemytv;

import android.app.Application;

/** Gives Prefs a context in every process; starts the LED strip's automations in the one that runs them. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        LightsAuto.start(this, Application.getProcessName());
    }
}
