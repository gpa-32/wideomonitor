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

/** Podglad kamery bramofonu przez RTSP (z automatycznym wznawianiem). */
public class RtspPlayer {
    public interface SizeListener {
        void onVideoSize(int w, int h);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private boolean stopped;

    public void start(final Context ctx, final TextureView view, final String url,
                      final boolean tcp, final SizeListener sizeListener) {
        stopped = false;
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
                    L.i("RTSP: blad odtwarzania (" + error.getErrorCodeName() + "), ponawiam");
                    main.postDelayed(() -> {
                        if (stopped) return;
                        release();
                        start(ctx, view, url, tcp, sizeListener);
                    }, 2000);
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (sizeListener != null && size.width > 0 && size.height > 0) {
                        sizeListener.onVideoSize(size.width, size.height);
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
        } catch (Throwable t) {
            L.e("RTSP: nie udalo sie uruchomic podgladu", t);
        }
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
