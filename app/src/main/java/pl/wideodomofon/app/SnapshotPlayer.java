package pl.wideodomofon.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/** Zapasowy podglad kamery: cyklicznie pobierane zdjecia JPEG z bramofonu (bez RTSP). */
public class SnapshotPlayer {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private Thread thread;

    public void start(final ImageView view, final String url, final RtspPlayer.Listener listener) {
        stopped = false;
        L.i("JPEG: podglad z " + url.replaceAll("//[^/@]*@", "//***@"));
        listener.onStatus("Łączenie z kamerą… (zdjęcia JPEG)");
        thread = new Thread(() -> {
            boolean ok = false;
            int errors = 0;
            while (!stopped) {
                long t0 = System.currentTimeMillis();
                try {
                    byte[] data = Diag.httpGet(url, 4000);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp == null) throw new Exception("odpowiedź nie jest zdjęciem JPEG");
                    final boolean first = !ok;
                    ok = true;
                    errors = 0;
                    main.post(() -> {
                        if (stopped) return;
                        view.setImageBitmap(bmp);
                        if (first) {
                            L.i("JPEG: obraz odebrany " + bmp.getWidth() + "x" + bmp.getHeight());
                            listener.onStatus(null);
                        }
                    });
                } catch (Throwable t) {
                    errors++;
                    final String why = t.getMessage() != null ? t.getMessage() : t.toString();
                    if (errors == 1 || errors % 10 == 0) L.i("JPEG: BLAD - " + why);
                    if (errors >= 2) {
                        ok = false;
                        main.post(() -> {
                            if (!stopped) listener.onStatus("Brak obrazu z kamery (JPEG):\n" + why);
                        });
                    }
                }
                long wait = (errors > 0 ? 1500 : 250) - (System.currentTimeMillis() - t0);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "jpeg-preview");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (thread != null) thread.interrupt();
    }
}
