package pl.wideodomofon.app;

import android.app.Application;
import android.os.Process;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Cfg.init(this);
        L.init(this);
        L.i("=== Start procesu, wersja " + BuildConfig.VERSION_NAME + " ===");
        // Blad krytyczny: zapisz i zakoncz proces. Usluge podniesie system
        // (START_STICKY), alarm podtrzymujacy albo zewnetrzny watchdog root.
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                L.e("Nieobsluzony wyjatek w watku " + thread.getName(), ex);
            } catch (Throwable ignored) {
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }
}
