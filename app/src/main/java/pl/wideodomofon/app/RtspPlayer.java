package pl.wideodomofon.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * Podglad kamery bramofonu przez RTSP. Przy bledzie lub braku obrazu ponawia
 * polaczenie, na zmiane przez TCP i UDP, i zglasza przyczyne na ekran oraz do dziennika.
 */
public class RtspPlayer {
    public interface Listener {
        void onVideoSize(int w, int h);

        /** Komunikat do pokazania na ekranie; null = obraz jest, ukryj komunikat. */
        void onStatus(String msg);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean stopped;
    private boolean gotFrame;
    private boolean tcp;
    private int attempt;
    private Context ctx;
    private TextureView view;
    private String url;
    private Listener listener;

    public void start(Context ctx, TextureView view, String url, boolean tcpPreferred, Listener listener) {
        this.ctx = ctx;
        this.view = view;
        this.url = url;
        this.listener = listener;
        this.tcp = tcpPreferred;
        this.attempt = 0;
        this.stopped = false;
        open();
    }

    private final Runnable openRunnable = this::open;

    private final Runnable timeout = () -> {
        if (!gotFrame) fail("brak obrazu przez 10 s (sprawdź sieć między tabletem a bramofonem)");
    };

    private void status(String msg) {
        if (listener != null && !stopped) listener.onStatus(msg);
    }

    private void open() {
        if (stopped) return;
        attempt++;
        gotFrame = false;
        String mode = tcp ? "TCP" : "UDP";
        L.i("RTSP: lacze z " + url.replaceAll("//[^/@]*@", "//***@") + " przez " + mode + " (proba " + attempt + ")");
        status("Łączenie z kamerą… (" + mode + ", próba " + attempt + ")");
        try {
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(400, 2000, 100, 200)
                    .build();
            player = new ExoPlayer.Builder(ctx).setLoadControl(lc).build();
            player.setVideoTextureView(view);
            player.setVolume(0f); // dzwiek idzie przez SIP
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Throwable c = error.getCause();
                    String why = error.getErrorCodeName();
                    if (c != null) why += ": " + c.getClass().getSimpleName()
                            + (c.getMessage() != null ? " " + c.getMessage() : "");
                    fail(why);
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (!gotFrame) {
                        gotFrame = true;
                        main.removeCallbacks(timeout);
                        L.i("RTSP: obraz odebrany");
                        status(null);
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (listener != null && size.width > 0 && size.height > 0) {
                        L.i("RTSP: rozdzielczosc " + size.width + "x" + size.height);
                        listener.onVideoSize(size.width, size.height);
                    }
                }
            });
            RtspMediaSource src = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(tcp)
                    .setTimeoutMs(6000)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
            player.setMediaSource(src);
            player.setPlayWhenReady(true);
            player.prepare();
            main.postDelayed(timeout, 10000);
        } catch (Throwable t) {
            fail("nie udało się uruchomić odtwarzacza: " + t);
        }
    }

    private void fail(String why) {
        if (stopped) return;
        L.i("RTSP: BLAD - " + why);
        status("Brak obrazu z kamery:\n" + why);
        main.removeCallbacks(timeout);
        release();
        tcp = !tcp; // nastepna proba drugim sposobem transmisji
        main.postDelayed(openRunnable, 2500);
    }

    private void release() {
        try {
            if (player != null) player.release();
        } catch (Throwable ignored) {
        }
        player = null;
    }

    public void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        release();
    }
}
