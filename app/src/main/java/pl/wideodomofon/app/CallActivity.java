package pl.wideodomofon.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.util.VLCVideoLayout;

/** Pelnoekranowy ekran polaczenia: obraz z kamery + Odbierz / Otworz furtke / Odrzuc. */
public class CallActivity extends Activity implements IntercomService.Ui {
    public static volatile boolean shown;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView video;
    private VLCVideoLayout rtspView;
    private TextView title, info, videoStatus;
    private ImageView btnAnswer, btnOpen, btnHang, btnMute;
    private RtspPlayer rtsp;
    private SnapshotPlayer jpeg;
    private android.widget.ImageView jpegView;
    private boolean test;
    private boolean sipVideoAttached;
    private boolean closed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        test = getIntent().getBooleanExtra("test", false);
        buildUi();

        IntercomService svc = IntercomService.instance;
        if (svc == null || (!test && svc.state == IntercomService.IDLE)) {
            finishNow();
            return;
        }
        svc.ui = this;
        startVideo(svc);
        refresh();
        main.postDelayed(guard, 2000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        test = intent.getBooleanExtra("test", false);
        IntercomService svc = IntercomService.instance;
        if (svc != null) svc.ui = this;
        refresh();
    }

    /** Zabezpieczenie: ekran nie moze zostac na wierzchu, gdy polaczenia juz nie ma. */
    private final Runnable guard = new Runnable() {
        @Override
        public void run() {
            IntercomService svc = IntercomService.instance;
            if (svc == null || (!test && svc.state == IntercomService.IDLE)) {
                finishNow();
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    private void startVideo(IntercomService svc) {
        String url = Cfg.s("rtsp_url");
        final RtspPlayer.Listener statusListener = msg -> {
            if (closed) return;
            videoStatus.setText(msg == null ? "" : msg);
            videoStatus.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        };
        if ("jpeg".equals(Cfg.s("video_source")) && !Cfg.s("jpeg_url").isEmpty()) {
            L.i("Ekran: podglad JPEG");
            video.setVisibility(View.GONE);
            jpegView.setVisibility(View.VISIBLE);
            jpeg = new SnapshotPlayer();
            jpeg.start(jpegView, Cfg.s("jpeg_url"), statusListener);
            return;
        }
        if ("rtsp".equals(Cfg.s("video_source")) && !url.isEmpty()) {
            rtsp = new RtspPlayer();
            L.i("Ekran: podglad RTSP");
            video.setVisibility(View.GONE);
            rtspView.setVisibility(View.VISIBLE);
            rtsp.start(this, rtspView, url, Cfg.b("rtsp_tcp"), statusListener);
        } else if (svc.sip() != null) {
            L.i("Ekran: obraz z polaczenia SIP (zrodlo: " + Cfg.s("video_source")
                    + (url.isEmpty() ? ", adres RTSP pusty" : "") + ")");
            svc.sip().setVideoWindow(video);
            sipVideoAttached = true;
            if (test) {
                videoStatus.setText(!"sip".equals(Cfg.s("video_source"))
                        ? "Nie wpisano adresu kamery (RTSP / JPEG) w panelu www."
                        : "Źródło obrazu ustawione na „Wideo w połączeniu SIP” –\nobraz pojawi się tylko podczas prawdziwego połączenia.\n"
                        + "Aby zobaczyć podgląd tutaj, wybierz w panelu www „Strumień RTSP z bramofonu” i zapisz.");
                videoStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ IntercomService.Ui

    @Override
    public void refresh() {
        IntercomService svc = IntercomService.instance;
        if (svc == null) return;
        int st = svc.state;
        if (test && st == IntercomService.IDLE) {
            title.setText("Test ekranu wideodomofonu");
            info.setText("Podgląd kamery i próbne otwarcie furtki");
            btnAnswer.setVisibility(View.GONE);
            btnMute.setVisibility(View.GONE);
            setIcon(btnHang, R.drawable.ic_close, "Zamknij");
        } else if (st == IntercomService.RINGING) {
            title.setText("Ktoś dzwoni do furtki");
            info.setText(svc.caller);
            btnAnswer.setVisibility(View.VISIBLE);
            btnMute.setVisibility(View.GONE);
            setIcon(btnHang, R.drawable.ic_call_end, "Odrzuć");
        } else if (st == IntercomService.IN_CALL) {
            title.setText("Rozmowa");
            info.setText(svc.caller);
            btnAnswer.setVisibility(View.GONE);
            btnMute.setVisibility(View.VISIBLE);
            boolean muted = svc.isMicMuted();
            setIcon(btnMute, muted ? R.drawable.ic_mic_off : R.drawable.ic_mic, muted ? "Włącz mikrofon" : "Wycisz mikrofon");
            btnMute.setBackground(circle(muted ? 0xFFEF6C00 : 0xFF546E7A));
            setIcon(btnHang, R.drawable.ic_call_end, "Rozłącz");
        }
    }

    @Override
    public void close() {
        finishNow();
    }

    @Override
    public void toast(String msg) {
        if (closed) return;
        try {
            info.setText(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ cykl zycia

    private void finishNow() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        releaseVideo();
        try {
            finishAndRemoveTask();
        } catch (Throwable t) {
            finish();
        }
    }

    private void releaseVideo() {
        try {
            if (rtsp != null) rtsp.stop();
            rtsp = null;
            if (jpeg != null) jpeg.stop();
            jpeg = null;
            IntercomService svc = IntercomService.instance;
            if (sipVideoAttached && svc != null && svc.sip() != null) svc.sip().setVideoWindow(null);
            sipVideoAttached = false;
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = true;
        hideSystemBars();
        IntercomService svc = IntercomService.instance;
        if (svc != null) svc.cancelCallNotification();
    }

    @Override
    protected void onPause() {
        shown = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        shown = false;
        main.removeCallbacksAndMessages(null);
        releaseVideo();
        IntercomService svc = IntercomService.instance;
        if (svc != null && svc.ui == this) svc.ui = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Wstecz nie zamyka ekranu w trakcie polaczenia - sluza do tego przyciski.
        if (test) onHang();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------------ przyciski

    private void onAnswer() {
        IntercomService svc = IntercomService.instance;
        if (svc != null) svc.answer();
    }

    private void onOpen() {
        IntercomService svc = IntercomService.instance;
        if (svc != null) {
            info.setText("Otwieranie furtki…");
            svc.openGate("ekran tabletu");
        }
    }

    private void onHang() {
        IntercomService svc = IntercomService.instance;
        if (svc == null) {
            finishNow();
            return;
        }
        if (svc.state == IntercomService.IDLE) {
            finishNow();
            svc.onTestClosed();
        } else {
            svc.hangup();
        }
    }

    private void onMute() {
        IntercomService svc = IntercomService.instance;
        if (svc != null) svc.toggleMute();
    }

    // ------------------------------------------------------------------ budowa widoku

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Polprzezroczyste kolo z cienka jasna obwodka i efektem dotkniecia. */
    private android.graphics.drawable.Drawable circle(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor((color & 0x00FFFFFF) | 0x8C000000); // ok. 55% krycia - widac przez nie obraz
        bg.setStroke(dp(2), 0x99FFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), bg, mask);
    }

    private void setIcon(ImageView b, int icon, String description) {
        b.setImageResource(icon);
        b.setContentDescription(description);
    }

    /** Okragly przycisk ze znakiem zamiast tekstu. */
    private ImageView button(int icon, String description, int color, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        setIcon(b, icon, description);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(24);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(circle(color));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(104));
        lp.setMargins(dp(28), 0, dp(28), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        rtspView = new VLCVideoLayout(this);
        rtspView.setVisibility(View.GONE);
        root.addView(rtspView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        jpegView = new android.widget.ImageView(this);
        jpegView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        jpegView.setVisibility(View.GONE);
        root.addView(jpegView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        video = new TextureView(this);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        videoStatus = new TextView(this);
        videoStatus.setTextColor(0xFFFFD54F);
        videoStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        videoStatus.setGravity(Gravity.CENTER);
        videoStatus.setPadding(dp(24), dp(24), dp(24), dp(24));
        videoStatus.setVisibility(View.GONE);
        root.addView(videoStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0x55000000);
        top.setPadding(dp(20), dp(12), dp(20), dp(12));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        info = new TextView(this);
        info.setTextColor(0xFFDDDDDD);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        top.addView(title);
        top.addView(info);
        root.addView(top, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(12), dp(12), dp(12), dp(28)); // bez tla - przyciski leza bezposrednio na obrazie
        btnAnswer = button(R.drawable.ic_call, "Odbierz", 0xFF2E7D32, v -> onAnswer());
        btnOpen = button(R.drawable.ic_gate, "Otwórz furtkę", 0xFF1565C0, v -> onOpen());
        btnMute = button(R.drawable.ic_mic, "Wycisz mikrofon", 0xFF546E7A, v -> onMute());
        btnHang = button(R.drawable.ic_call_end, "Odrzuć", 0xFFC62828, v -> onHang());
        bar.addView(btnAnswer);
        bar.addView(btnOpen);
        bar.addView(btnMute);
        bar.addView(btnHang);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
    }
}
