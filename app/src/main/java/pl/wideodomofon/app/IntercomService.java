package pl.wideodomofon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usluga dzialajaca stale w tle: SIP, serwer www, watchdog.
 * Przy polaczeniu przychodzacym wyswietla CallActivity nad biezaca aplikacja,
 * a po zakonczeniu przywraca poprzednia aplikacje.
 */
public class IntercomService extends Service implements SipManager.Events {
    public static final int IDLE = 0, RINGING = 1, IN_CALL = 2;

    public interface Ui {
        void refresh();

        void close();

        void toast(String msg);
    }

    public static volatile IntercomService instance;

    public volatile Ui ui;
    public volatile int state = IDLE;
    public volatile String caller = "";
    public volatile String lastCall = "-";
    public volatile String gateResult = "-";
    public volatile boolean rootOk;
    public volatile String watchdogInfo = "-";

    private static final String CH_SERVICE = "service";
    private static final String CH_CALL = "call";
    private static final int NOTIF_SERVICE = 1, NOTIF_CALL = 2;
    private static final String WD_SCRIPT = "wideodomofon_wd.sh";
    private static final String WD_PID = "/data/local/tmp/wideodomofon_wd.pid";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long startedAt = SystemClock.elapsedRealtime();
    private volatile long lastBeat = SystemClock.uptimeMillis();
    private volatile boolean alive = true;

    private SipManager sip;
    private WebServer web;
    private int webPort;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private File beatFile;
    private int tick;

    private long ringStart, callStart;
    private boolean pendingDtmf;
    private boolean micMuted;
    private String prevPkg;
    private boolean wasScreenOff;
    private boolean uiSessionActive;

    // ------------------------------------------------------------------ cykl zycia

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        beatFile = new File(getFilesDir(), "heartbeat");
        createChannels();
        startForeground(NOTIF_SERVICE, serviceNotification());
        L.i("Usluga: start");

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wideodomofon:service");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wideodomofon:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Throwable t) {
            L.e("Blokady zasilania", t);
        }

        sip = new SipManager(this, this);
        sip.start();
        startWeb();
        writeBeat();
        main.postDelayed(heartbeat, 5000);
        startWatchThread();
        setupRootAsync();
        BootReceiver.scheduleKeepAlive(this, 5 * 60 * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        L.i("Usluga: zatrzymana - zaplanowano ponowny start");
        alive = false;
        main.removeCallbacksAndMessages(null);
        try {
            if (sip != null) sip.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (web != null) web.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (wakeLock != null) wakeLock.release();
            if (wifiLock != null) wifiLock.release();
        } catch (Throwable ignored) {
        }
        instance = null;
        BootReceiver.scheduleKeepAlive(this, 3000);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ watchdog

    /** Tetno watku glownego: co 5 s. Sprawdza tez SIP, serwer www i zawieszone polaczenia. */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            lastBeat = now;
            tick++;
            try {
                if (tick % 2 == 0) writeBeat();
                if (web == null || !web.isAlive()) {
                    L.i("Watchdog: serwer www nie dziala - uruchamiam ponownie");
                    startWeb();
                }
                if (state == IDLE) sip.healthCheck();

                int maxCall = Cfg.i("max_call_s");
                if (state == IN_CALL && maxCall > 0 && now - callStart > maxCall * 1000L) {
                    L.i("Watchdog: przekroczony maksymalny czas rozmowy - rozlaczam");
                    hangup();
                }
                if (state == RINGING && now - ringStart > (Cfg.i("ring_timeout_s") + 20) * 1000L) {
                    L.i("Watchdog: dzwonienie trwa zbyt dlugo - koncze");
                    hangup();
                }
                if (state != IDLE && !sip.hasCall() && now - ringStart > 3000) {
                    L.i("Watchdog: stan polaczenia bez aktywnej rozmowy - naprawiam");
                    onEnded("naprawa stanu");
                }

                int hour = Cfg.i("nightly_restart_hour");
                if (hour >= 0 && hour <= 23 && state == IDLE
                        && SystemClock.elapsedRealtime() - startedAt > 30 * 60 * 1000L) {
                    Calendar c = Calendar.getInstance();
                    if (c.get(Calendar.HOUR_OF_DAY) == hour && c.get(Calendar.MINUTE) < 3) {
                        die("Planowy nocny restart aplikacji");
                    }
                }
                if (tick % 24 == 0) BootReceiver.scheduleKeepAlive(IntercomService.this, 5 * 60 * 1000L);
            } catch (Throwable t) {
                L.e("Watchdog: blad w tetnie", t);
            }
            main.postDelayed(this, 5000);
        }
    };

    /** Osobny watek: jesli watek glowny nie odpowiada przez 40 s, zabija proces (system go podniesie). */
    private void startWatchThread() {
        Thread t = new Thread(() -> {
            while (alive) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                long age = SystemClock.uptimeMillis() - lastBeat;
                if (alive && age > 40000) {
                    die("Watchdog: watek glowny zawieszony od " + (age / 1000) + " s - restart procesu");
                }
            }
        }, "watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void writeBeat() {
        try {
            FileOutputStream o = new FileOutputStream(beatFile);
            o.write(String.valueOf(System.currentTimeMillis()).getBytes());
            o.close();
        } catch (Throwable ignored) {
        }
    }

    public static void die(String why) {
        try {
            L.i(why);
        } catch (Throwable ignored) {
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    /** Uprawnienia nadawane przez root oraz zewnetrzny watchdog (skrypt dzialajacy poza aplikacja). */
    private void setupRootAsync() {
        Thread t = new Thread(() -> {
            Root.resetCache();
            if (!Root.available()) {
                rootOk = false;
                watchdogInfo = "brak root - dziala tylko watchdog wewnetrzny";
                L.i("Root: niedostepny (uprawnienia trzeba nadac recznie w aplikacji)");
                return;
            }
            rootOk = true;
            String pkg = getPackageName();
            Root.run("pm grant " + pkg + " android.permission.RECORD_AUDIO;"
                    + " pm grant " + pkg + " android.permission.POST_NOTIFICATIONS;"
                    + " appops set " + pkg + " SYSTEM_ALERT_WINDOW allow;"
                    + " appops set " + pkg + " RUN_IN_BACKGROUND allow;"
                    + " appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow;"
                    + " dumpsys deviceidle whitelist +" + pkg, 15000);
            L.i("Root: nadano uprawnienia (mikrofon, wyswietlanie nad aplikacjami, praca w tle)");
            try {
                File script = new File(getFilesDir(), WD_SCRIPT);
                String sh = "#!/system/bin/sh\n"
                        + "PKG=" + pkg + "\n"
                        + "HB=" + beatFile.getAbsolutePath() + "\n"
                        + "sleep 60\n"
                        + "while true; do\n"
                        + "  pm path $PKG >/dev/null 2>&1 || exit 0\n"
                        + "  NOW=$(date +%s)\n"
                        + "  T=$(stat -c %Y $HB 2>/dev/null || echo 0)\n"
                        + "  AGE=$((NOW-T))\n"
                        + "  if [ $AGE -gt 90 ]; then\n"
                        + "    log -t WideodomofonWD \"brak tetna od $AGE s - restart aplikacji\"\n"
                        + "    am force-stop $PKG\n"
                        + "    sleep 1\n"
                        + "    am start-foreground-service -n $PKG/.IntercomService >/dev/null 2>&1\n"
                        + "    sleep 60\n"
                        + "  fi\n"
                        + "  sleep 20\n"
                        + "done\n";
                FileOutputStream o = new FileOutputStream(script);
                o.write(sh.getBytes());
                o.close();
                String path = script.getAbsolutePath();
                // Stary egzemplarz konczymy tylko, gdy PID na pewno nalezy do naszego skryptu.
                Root.Result r = Root.run("OP=$(cat " + WD_PID + " 2>/dev/null);"
                        + " if [ -n \"$OP\" ] && grep -q " + WD_SCRIPT + " /proc/$OP/cmdline 2>/dev/null; then kill $OP; fi;"
                        + " nohup sh " + path + " >/dev/null 2>&1 &"
                        + " echo $! > " + WD_PID + "; echo ok", 10000);
                watchdogInfo = r.out.contains("ok") ? "zewnetrzny (root) + wewnetrzny" : "wewnetrzny (blad startu zewnetrznego)";
                L.i("Watchdog: " + watchdogInfo);
            } catch (Throwable e) {
                watchdogInfo = "wewnetrzny";
                L.e("Watchdog zewnetrzny", e);
            }
        }, "root-setup");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ serwer www

    private void startWeb() {
        try {
            if (web != null) web.stop();
        } catch (Throwable ignored) {
        }
        web = null;
        int port = Cfg.i("web_port");
        if (port < 1024 || port > 65535) port = 8080;
        try {
            WebServer w = new WebServer(this, port);
            w.start(15000, true);
            web = w;
            webPort = port;
            L.i("Panel www: http://" + localIp() + ":" + port);
        } catch (Throwable t) {
            L.e("Panel www: nie udalo sie uruchomic na porcie " + port, t);
        }
    }

    /** Wolane po zapisaniu ustawien w panelu www. */
    public void applyConfig() {
        main.postDelayed(() -> {
            L.i("Zapisano nowe ustawienia");
            if (state == IDLE) sip.restart();
            else L.i("Trwa rozmowa - ustawienia SIP zostana zastosowane po restarcie SIP");
            if (Cfg.i("web_port") != webPort) startWeb();
        }, 500);
    }

    public void restartSip() {
        main.post(() -> {
            if (state == IDLE) sip.restart();
        });
    }

    // ------------------------------------------------------------------ zdarzenia SIP (watek glowny)

    @Override
    public void onIncoming(String who) {
        state = RINGING;
        caller = who;
        ringStart = SystemClock.uptimeMillis();
        pendingDtmf = false;
        micMuted = false;
        lastCall = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + who;
        L.i("Polaczenie przychodzace: " + who);
        setVolume(AudioManager.STREAM_RING, Cfg.i("ring_volume"));
        showCallUi(false);
    }

    @Override
    public void onConnected() {
        state = IN_CALL;
        callStart = SystemClock.uptimeMillis();
        L.i("Rozmowa rozpoczeta");
        setVolume(AudioManager.STREAM_VOICE_CALL, Cfg.i("call_volume"));
        if (pendingDtmf) {
            pendingDtmf = false;
            main.postDelayed(() -> sip.sendDtmf(Cfg.s("dtmf_code")), 700);
        }
        Ui u = ui;
        if (u != null) u.refresh();
    }

    @Override
    public void onEnded(String reason) {
        if (state == IDLE) return;
        state = IDLE;
        pendingDtmf = false;
        main.removeCallbacks(hangupAfterOpen);
        L.i("Polaczenie zakonczone: " + reason);
        closeUiSession();
    }

    // ------------------------------------------------------------------ akcje (ekran / www)

    public boolean isMicMuted() {
        return micMuted;
    }

    public void answer() {
        main.post(() -> {
            if (state != RINGING) return;
            micMuted = false;
            if (!sip.answer(false)) L.i("Nie udalo sie odebrac");
        });
    }

    public void toggleMute() {
        main.post(() -> {
            micMuted = !micMuted;
            sip.setMicMuted(micMuted);
            Ui u = ui;
            if (u != null) u.refresh();
        });
    }

    /** Odrzucenie (gdy dzwoni) albo rozlaczenie (w trakcie rozmowy). */
    public void hangup() {
        main.post(() -> {
            if (state == IDLE) return;
            if (sip.hasCall()) sip.terminate();
            else onEnded("rozlaczono");
        });
    }

    private final Runnable hangupAfterOpen = this::hangup;

    public void openGate(final String source) {
        main.post(() -> {
            String mode = Cfg.s("gate_mode");
            boolean useHttp = "http".equals(mode) || "both".equals(mode);
            boolean useDtmf = "dtmf".equals(mode) || "both".equals(mode);
            L.i("Otwieranie furtki (" + source + "), tryb " + mode);
            long extra = 0;

            if (useHttp) {
                if (Cfg.s("http_url").isEmpty()) {
                    if (!useDtmf) notifyUi("Nie ustawiono adresu URL furtki");
                } else {
                    Thread t = new Thread(() -> {
                        String msg;
                        try {
                            msg = "Furtka otwarta (" + Gate.httpOpen() + ")";
                        } catch (Throwable e) {
                            msg = "Błąd otwierania furtki: " + e.getMessage();
                        }
                        gateResult = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
                        L.i(msg);
                        final String m = msg;
                        main.post(() -> notifyUi(m));
                    }, "gate-http");
                    t.setDaemon(true);
                    t.start();
                }
            }
            if (useDtmf) {
                String code = Cfg.s("dtmf_code");
                if (state == IN_CALL) {
                    sip.sendDtmf(code);
                    if (!useHttp) notifyUi("Wysłano kod otwarcia");
                } else if (state == RINGING) {
                    // Kod DTMF mozna wyslac dopiero w zestawionym polaczeniu:
                    // odbieramy z wyciszonym mikrofonem, wysylamy kod i rozlaczamy.
                    pendingDtmf = true;
                    micMuted = true;
                    extra = 1500;
                    if (!sip.answer(true)) pendingDtmf = false;
                } else if (!useHttp) {
                    notifyUi("Kod DTMF można wysłać tylko podczas połączenia");
                }
            }

            int delay = Cfg.i("hangup_after_open_s");
            if (state != IDLE && (delay > 0 || pendingDtmf)) {
                main.removeCallbacks(hangupAfterOpen);
                main.postDelayed(hangupAfterOpen, Math.max(delay, 2) * 1000L + extra);
            }
        });
    }

    private void notifyUi(String msg) {
        Ui u = ui;
        if (u != null) u.toast(msg);
    }

    // ------------------------------------------------------------------ ekran rozmowy i powrot do poprzedniej aplikacji

    public void testUi() {
        main.post(() -> {
            if (state == IDLE) showCallUi(true);
        });
    }

    /** Wolane przez CallActivity po zamknieciu ekranu testowego. */
    public void onTestClosed() {
        main.post(() -> {
            if (state == IDLE) closeUiSession();
        });
    }

    private void showCallUi(final boolean test) {
        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final boolean interactive = pm.isInteractive();
        final boolean firstShow = !uiSessionActive;
        uiSessionActive = true;
        if (firstShow) {
            wasScreenOff = !interactive;
            prevPkg = null;
        }
        Thread t = new Thread(() -> {
            String found = null;
            if (firstShow && interactive && !CallActivity.shown && Root.available()) {
                Root.Result r = Root.run("dumpsys activity activities | grep ResumedActivity", 1500);
                found = parseResumed(r.out);
            }
            final String fp = found;
            main.post(() -> {
                if (!test && state == IDLE) {
                    uiSessionActive = false;
                    return;
                }
                if (fp != null && !fp.equals(getPackageName())) prevPkg = fp;
                if (!interactive) Root.runAsync("input keyevent 224"); // wybudzenie ekranu
                launchCallActivity(test);
            });
        }, "show-ui");
        t.setDaemon(true);
        t.start();
    }

    static String parseResumed(String out) {
        if (out == null) return null;
        Pattern p = Pattern.compile("ResumedActivity[:=]\\s*ActivityRecord\\{\\S+\\s+\\S+\\s+([A-Za-z0-9_.]+)/");
        String first = null;
        for (String line : out.split("\n")) {
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            if (line.contains("topResumedActivity")) return m.group(1);
            if (first == null) first = m.group(1);
        }
        return first;
    }

    private void launchCallActivity(final boolean test) {
        final Intent i = new Intent(this, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("test", test);
        try {
            startActivity(i);
        } catch (Throwable t) {
            L.e("Start ekranu rozmowy", t);
        }
        if (!test) {
            try {
                PendingIntent pi = PendingIntent.getActivity(this, 2, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(this, CH_CALL)
                        .setSmallIcon(android.R.drawable.sym_call_incoming)
                        .setContentTitle("Ktoś dzwoni do furtki")
                        .setContentText(caller)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setOngoing(true)
                        .setContentIntent(pi)
                        .setFullScreenIntent(pi, true)
                        .build();
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_CALL, n);
            } catch (Throwable t) {
                L.e("Powiadomienie o polaczeniu", t);
            }
        }
        // Rezerwa: jesli system zablokowal start ekranu z tla, wymus go przez root.
        main.postDelayed(() -> {
            if (!CallActivity.shown && (test || state != IDLE) && rootOk) {
                L.i("Ekran rozmowy nie pojawil sie - wymuszam przez root");
                Root.runAsync("am start -n " + getPackageName() + "/.CallActivity --ez test " + test
                        + " -f 0x10040000");
            }
        }, 2000);
    }

    /** Zamyka ekran rozmowy i przywraca to, co bylo na ekranie wczesniej. */
    private void closeUiSession() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_CALL);
        } catch (Throwable ignored) {
        }
        Ui u = ui;
        if (u != null) u.close();
        if (!uiSessionActive) return;
        uiSessionActive = false;
        final String pkg = prevPkg;
        final boolean screenOff = wasScreenOff;
        prevPkg = null;
        main.postDelayed(() -> {
            if (state != IDLE) return; // w miedzyczasie zadzwonil ktos nastepny
            if (Cfg.b("restore_prev") && pkg != null && !pkg.equals(getPackageName())) {
                try {
                    Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (li != null) {
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(li);
                        L.i("Przywrocono aplikacje: " + pkg);
                    }
                } catch (Throwable t) {
                    L.e("Przywracanie poprzedniej aplikacji", t);
                }
            }
            if (screenOff && Cfg.b("screen_off_after")) {
                main.postDelayed(() -> {
                    if (state == IDLE && !CallActivity.shown) Root.runAsync("input keyevent 223"); // wygaszenie
                }, 1500);
            }
        }, 700);
    }

    // ------------------------------------------------------------------ pomocnicze

    private void setVolume(int stream, int percent) {
        if (percent < 0) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(stream);
            am.setStreamVolume(stream, Math.round(max * Math.min(100, percent) / 100f), 0);
        } catch (Throwable t) {
            L.e("Ustawienie glosnosci", t);
        }
    }

    public String sipStatus() {
        SipManager s = sip;
        if (s == null) return "-";
        return s.isRunning() ? s.regState : "zatrzymany / " + s.regState;
    }

    public long uptimeSeconds() {
        return (SystemClock.elapsedRealtime() - startedAt) / 1000;
    }

    public boolean overlayAllowed() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return false;
        }
    }

    public SipManager sip() {
        return sip;
    }

    public static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Praca w tle", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(s);
        NotificationChannel c = new NotificationChannel(CH_CALL, "Połączenia z furtki", NotificationManager.IMPORTANCE_HIGH);
        c.setSound(null, null);
        nm.createNotificationChannel(c);
    }

    private Notification serviceNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Wideodomofon działa w tle")
                .setContentText("Oczekiwanie na połączenie z furtki")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
