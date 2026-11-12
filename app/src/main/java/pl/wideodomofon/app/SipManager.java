package pl.wideodomofon.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AudioDevice;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.CallParams;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaDirection;
import org.linphone.core.Reason;
import org.linphone.core.RegistrationState;
import org.linphone.core.Transports;
import org.linphone.core.VideoActivationPolicy;

/**
 * Obsluga SIP (Linphone SDK). Wszystkie metody wolac wylacznie z watku glownego.
 * Przyjmuje polaczenia z serwera SIP oraz bezposrednie po adresie IP tabletu.
 */
public class SipManager {
    public interface Events {
        void onIncoming(String who);

        void onConnected();

        void onEnded(String reason);
    }

    private final Context ctx;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Core core;
    private Call call;
    private boolean connected;
    private boolean accountConfigured;
    private boolean regOk;
    private long lastOk;
    private long lastStartTry;
    private Object videoWindow;
    public volatile String regState = "-";

    public SipManager(Context ctx, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.events = events;
    }

    private final CoreListenerStub listener = new CoreListenerStub() {
        @Override
        public void onCallStateChanged(Core c, final Call cl, Call.State state, String message) {
            L.i("SIP: stan polaczenia " + state + (message != null ? " (" + message + ")" : ""));
            if (state == Call.State.IncomingReceived) {
                if (call != null && call != cl) {
                    L.i("SIP: drugie polaczenie odrzucone (zajete)");
                    try {
                        cl.decline(Reason.Busy);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                call = cl;
                connected = false;
                String who = describe(cl);
                if ("sip".equals(Cfg.s("video_source")) && Cfg.b("early_media")) {
                    // Obraz jeszcze przed odebraniem (183 Session Progress).
                    main.post(() -> {
                        try {
                            if (call == cl && c.getCallsNb() > 0) {
                                CallParams p = c.createCallParams(cl);
                                if (p != null) {
                                    p.setVideoEnabled(true);
                                    p.setVideoDirection(MediaDirection.RecvOnly);
                                    cl.acceptEarlyMediaWithParams(p);
                                }
                            }
                        } catch (Throwable t) {
                            L.e("SIP: early media", t);
                        }
                    });
                }
                events.onIncoming(who);
            } else if (state == Call.State.StreamsRunning || state == Call.State.Connected) {
                if (cl == call && !connected && state == Call.State.StreamsRunning) {
                    connected = true;
                    routeToSpeaker();
                    events.onConnected();
                }
            } else if (state == Call.State.End || state == Call.State.Error
                    || state == Call.State.Released) {
                if (cl == call) {
                    call = null;
                    connected = false;
                    try {
                        c.setMicEnabled(true);
                    } catch (Throwable ignored) {
                    }
                    events.onEnded(message != null ? message : String.valueOf(state));
                }
            }
        }

        @Override
        public void onAccountRegistrationStateChanged(Core c, Account account,
                                                      RegistrationState state, String message) {
            regState = String.valueOf(state) + (message != null && !message.isEmpty() ? " (" + message + ")" : "");
            regOk = state == RegistrationState.Ok;
            if (regOk) lastOk = SystemClock.uptimeMillis();
            L.i("SIP: rejestracja " + regState);
        }
    };

    private static String describe(Call cl) {
        try {
            Address a = cl.getRemoteAddress();
            String name = a.getDisplayName();
            String user = a.getUsername();
            String host = a.getDomain();
            if (name != null && !name.isEmpty()) return name;
            if (user != null && !user.isEmpty()) return user + "@" + host;
            return String.valueOf(host);
        } catch (Throwable t) {
            return "Bramofon";
        }
    }

    public void start() {
        lastStartTry = SystemClock.uptimeMillis();
        lastOk = lastStartTry;
        try {
            Factory f = Factory.instance();
            Core c = f.createCore(null, null, ctx);
            c.setUserAgent("Wideodomofon", BuildConfig.VERSION_NAME);
            c.setAutoIterateEnabled(true);
            c.setIpv6Enabled(false);
            c.setKeepAliveEnabled(true);

            int port = Cfg.i("sip_port");
            if (port <= 0 || port > 65535) port = 5060;
            Transports tr = f.createTransports();
            tr.setUdpPort(port);
            tr.setTcpPort(port);
            tr.setTlsPort(0);
            tr.setDtlsPort(0);
            c.setTransports(tr);

            // Obraz tylko odbieramy - kamera tabletu nie jest uzywana.
            c.setVideoDisplayEnabled(true);
            c.setVideoCaptureEnabled(false);
            VideoActivationPolicy pol = f.createVideoActivationPolicy();
            pol.setAutomaticallyAccept(true);
            pol.setAutomaticallyInitiate(false);
            c.setVideoActivationPolicy(pol);

            c.setEchoCancellationEnabled(Cfg.b("echo_cancel"));
            c.setMicGainDb(Cfg.i("mic_gain_db"));
            c.setPlaybackGainDb(Cfg.i("speaker_gain_db"));
            c.setIncTimeout(Math.max(10, Cfg.i("ring_timeout_s")));

            if (Cfg.b("ring_enabled")) {
                c.setNativeRingingEnabled(true);
                c.setRingDuringIncomingEarlyMedia(true);
            } else {
                c.setNativeRingingEnabled(false);
                c.setRing(null);
            }

            String dm = Cfg.s("dtmf_method");
            c.setUseRfc2833ForDtmf(!"info".equals(dm));
            c.setUseInfoForDtmf("info".equals(dm) || "both".equals(dm));

            accountConfigured = false;
            regOk = false;
            regState = "wylaczona (tylko polaczenia po IP)";
            String server = Cfg.s("sip_server");
            String user = Cfg.s("sip_user");
            if (Cfg.b("sip_enabled") && !server.isEmpty() && !user.isEmpty()) {
                String domain = server.contains(":") ? server.substring(0, server.indexOf(':')) : server;
                String transport = "tcp".equals(Cfg.s("sip_transport")) ? "tcp" : "udp";
                AuthInfo ai = f.createAuthInfo(user, null, Cfg.s("sip_pass"), null, null, domain);
                c.addAuthInfo(ai);
                AccountParams ap = c.createAccountParams();
                Address identity = f.createAddress("sip:" + user + "@" + domain);
                Address srv = f.createAddress("sip:" + server + ";transport=" + transport);
                if (identity == null || srv == null) {
                    L.i("SIP: bledny adres serwera lub nazwa uzytkownika");
                } else {
                    ap.setIdentityAddress(identity);
                    ap.setServerAddress(srv);
                    ap.setRegisterEnabled(true);
                    ap.setExpires(Math.max(60, Cfg.i("sip_expires")));
                    Account acc = c.createAccount(ap);
                    c.addAccount(acc);
                    c.setDefaultAccount(acc);
                    accountConfigured = true;
                    regState = "laczenie...";
                }
            }

            c.addListener(listener);
            c.start();
            core = c;
            if (videoWindow != null) c.setNativeVideoWindowId(videoWindow);
            L.i("SIP: uruchomiono, port " + port + (accountConfigured ? ", konto " + user + "@" + server : ", bez rejestracji"));
        } catch (Throwable t) {
            core = null;
            regState = "blad uruchomienia: " + t;
            L.e("SIP: nie udalo sie uruchomic", t);
        }
    }

    public void stop() {
        Core c = core;
        core = null;
        call = null;
        connected = false;
        if (c == null) return;
        try {
            c.removeListener(listener);
            c.terminateAllCalls();
            c.setNativeVideoWindowId(null);
            c.stop();
        } catch (Throwable t) {
            L.e("SIP: zatrzymanie", t);
        }
    }

    public void restart() {
        L.i("SIP: restart");
        stop();
        start();
    }

    /** Wolane cyklicznie przez usluge - naprawia zawieszona rejestracje lub nieudany start. */
    public void healthCheck() {
        long now = SystemClock.uptimeMillis();
        if (core == null) {
            if (now - lastStartTry > 30000) start();
            return;
        }
        if (accountConfigured && !regOk && call == null && now - lastOk > 180000) {
            L.i("SIP: brak rejestracji od 3 minut - restart stosu SIP");
            restart();
        }
    }

    public boolean hasCall() {
        return call != null;
    }

    public boolean isRunning() {
        return core != null;
    }

    public boolean answer(boolean muted) {
        if (core == null || call == null) return false;
        try {
            boolean sipVideo = "sip".equals(Cfg.s("video_source"));
            core.setMicEnabled(!muted);
            CallParams p = core.createCallParams(call);
            if (p == null) {
                call.accept();
                return true;
            }
            p.setVideoEnabled(sipVideo);
            if (sipVideo) p.setVideoDirection(MediaDirection.RecvOnly);
            call.acceptWithParams(p);
            return true;
        } catch (Throwable t) {
            L.e("SIP: odebranie", t);
            return false;
        }
    }

    public void terminate() {
        try {
            if (call != null) call.terminate();
            else if (core != null) core.terminateAllCalls();
        } catch (Throwable t) {
            L.e("SIP: rozlaczenie", t);
        }
    }

    public void setMicMuted(boolean muted) {
        try {
            if (core != null) core.setMicEnabled(!muted);
        } catch (Throwable ignored) {
        }
    }

    public void sendDtmf(String code) {
        try {
            if (call != null && code != null && !code.isEmpty()) {
                call.sendDtmfs(code);
                L.i("SIP: wyslano DTMF " + code);
            }
        } catch (Throwable t) {
            L.e("SIP: DTMF", t);
        }
    }

    public void setVideoWindow(Object window) {
        videoWindow = window;
        try {
            if (core != null) core.setNativeVideoWindowId(window);
        } catch (Throwable t) {
            L.e("SIP: okno wideo", t);
        }
    }

    private void routeToSpeaker() {
        try {
            if (core == null || call == null) return;
            for (AudioDevice d : core.getAudioDevices()) {
                if (d.getType() == AudioDevice.Type.Speaker) {
                    call.setOutputAudioDevice(d);
                    break;
                }
            }
        } catch (Throwable t) {
            L.e("SIP: glosnik", t);
        }
    }
}
