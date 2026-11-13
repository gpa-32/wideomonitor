package pl.wideodomofon.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Podglad kamery bramofonu przez RTSP na silniku VLC (libVLC).
 * Przy bledzie lub braku obrazu ponawia polaczenie, na zmiane przez TCP i UDP,
 * i zglasza stan na ekran oraz do dziennika.
 */
public class RtspPlayer {
    public interface Listener {
        /** Komunikat do pokazania na ekranie; null = obraz jest, ukryj komunikat. */
        void onStatus(String msg);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LibVLC lib;
    private MediaPlayer player;
    private boolean stopped;
    private boolean gotFrame;
    private boolean workingFound;
    private boolean tcp;
    private int attempt;
    private Context ctx;
    private VLCVideoLayout view;
    private String url;
    private Listener listener;

    public void start(Context ctx, VLCVideoLayout view, String url, boolean tcpPreferred, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.view = view;
        this.url = url;
        this.listener = listener;
        this.tcp = tcpPreferred;
        this.attempt = 0;
        this.workingFound = false;
        this.stopped = false;
        open();
    }

    private final Runnable openRunnable = this::open;

    private final Runnable timeout = () -> {
        if (!gotFrame) fail("brak obrazu przez 12 s (sprawdź adres, hasło RTSP i sieć)");
    };

    private void status(String msg) {
        if (listener != null && !stopped) listener.onStatus(msg);
    }

    private void open() {
        if (stopped) return;
        attempt++;
        gotFrame = false;
        final String mode = tcp ? "TCP" : "UDP";
        L.i("RTSP: lacze z " + url.replaceAll("//[^/@]*@", "//***@") + " przez " + mode + " (proba " + attempt + ")");
        if (!workingFound) status("Łączenie z kamerą… (" + mode + ", próba " + attempt + ")");
        try {
            ArrayList<String> args = new ArrayList<>();
            args.add("-vv"); // szczegolowe komunikaty do diagnostyki w panelu www
            // Uwaga: nie wolno wylaczac sciezki dzwieku (--no-audio). VLC wysyla wtedy TEARDOWN
            // dla sciezki audio, a serwer RTSP Akuvoxa konczy po nim caly strumien (takze obraz).
            // Dzwiek RTSP jest wiec odbierany, ale wyciszony - rozmowa idzie przez SIP.
            args.add("--network-caching=300");
            if (tcp) args.add("--rtsp-tcp");
            lib = new LibVLC(ctx, args);
            player = new MediaPlayer(lib);
            player.attachViews(view, null, false, true);
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            player.setEventListener(event -> {
                if (stopped) return;
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                    case MediaPlayer.Event.ESAdded:
                        mute();
                        break;
                    case MediaPlayer.Event.Vout:
                        mute();
                        if (event.getVoutCount() > 0 && !gotFrame) {
                            gotFrame = true;
                            main.removeCallbacks(timeout);
                            L.i("RTSP: obraz odebrany (" + mode + ")");
                            status(null);
                        }
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        fail("odtwarzacz nie mógł otworzyć strumienia (adres, hasło RTSP lub sieć)");
                        break;
                    case MediaPlayer.Event.EndReached:
                        fail("strumień został zakończony przez bramofon");
                        break;
                    default:
                        break;
                }
            });
            Media media = new Media(lib, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=300");
            if (tcp) media.addOption(":rtsp-tcp");
            player.setMedia(media);
            media.release();
            player.play();
            mute();
            main.postDelayed(timeout, 12000);
        } catch (Throwable t) {
            fail("nie udało się uruchomić odtwarzacza: " + t);
        }
    }

    private void mute() {
        try {
            if (player != null) player.setVolume(0);
        } catch (Throwable ignored) {
        }
    }

    /** Zwalnianie odtwarzacza poza jego wlasnym wywolaniem zwrotnym. */
    private void fail(final String why) {
        if (stopped) return;
        main.removeCallbacks(timeout);
        final boolean hadFrame = gotFrame;
        main.post(() -> {
            if (stopped) return;
            release();
            main.removeCallbacks(openRunnable);
            if (hadFrame) {
                // Ten sposob transmisji dziala - wznawiamy od razu tym samym, bez komunikatu o bledzie.
                L.i("RTSP: przerwa w strumieniu (" + why + ") - wznawiam tym samym sposobem");
                workingFound = true;
                main.postDelayed(openRunnable, 200);
            } else {
                L.i("RTSP: BLAD - " + why);
                status("Brak obrazu z kamery:\n" + why);
                if (!workingFound) tcp = !tcp; // szukamy dzialajacego sposobu transmisji
                main.postDelayed(openRunnable, workingFound ? 1500 : 4000);
            }
        });
    }

    private void release() {
        MediaPlayer p = player;
        LibVLC l = lib;
        player = null;
        lib = null;
        try {
            if (p != null) {
                p.setEventListener(null);
                p.stop();
                p.detachViews();
                p.release();
            }
        } catch (Throwable t) {
            L.e("RTSP: zwalnianie odtwarzacza", t);
        }
        try {
            if (l != null) l.release();
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        release();
    }
}
