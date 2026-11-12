package pl.wideodomofon.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Ekran informacyjny: adres panelu www, haslo, stan. Cala konfiguracja odbywa sie przez www. */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BootReceiver.startService(this);

        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView head = new TextView(this);
        head.setText("Wideodomofon");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        head.setTextColor(Color.WHITE);
        box.addView(head);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setTextColor(0xFFDDDDDD);
        status.setPadding(0, pad, 0, pad);
        status.setTextIsSelectable(true);
        box.addView(status);

        Button perms = new Button(this);
        perms.setText("Nadaj uprawnienia ręcznie (bez root)");
        perms.setOnClickListener(v -> requestManualPermissions());
        box.addView(perms);

        Button test = new Button(this);
        test.setText("Test ekranu dzwonienia");
        test.setOnClickListener(v -> {
            IntercomService s = IntercomService.instance;
            if (s != null) s.testUi();
        });
        box.addView(test);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF10171C);
        sv.addView(box);
        setContentView(sv);
    }

    private void requestManualPermissions() {
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                    "android.permission.POST_NOTIFICATIONS"}, 1);
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (Throwable t) {
            L.e("Uprawnienia", t);
        }
    }

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            IntercomService s = IntercomService.instance;
            StringBuilder sb = new StringBuilder();
            if (s == null) {
                sb.append("Usługa uruchamia się…");
            } else {
                boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                sb.append("Panel konfiguracji (z komputera lub telefonu w tej samej sieci):\n")
                        .append("http://").append(IntercomService.localIp()).append(":").append(Cfg.i("web_port")).append("\n")
                        .append("Login: admin     Hasło: ").append(Cfg.s("web_pass")).append("\n\n")
                        .append("Adres do dzwonienia po IP: ").append(IntercomService.localIp())
                        .append(":").append(Cfg.i("sip_port")).append("\n")
                        .append("SIP: ").append(s.sipStatus()).append("\n")
                        .append("Root: ").append(s.rootOk ? "tak" : "nie").append("\n")
                        .append("Watchdog: ").append(s.watchdogInfo).append("\n")
                        .append("Mikrofon: ").append(mic ? "OK" : "BRAK UPRAWNIENIA").append("\n")
                        .append("Wyświetlanie nad aplikacjami: ").append(s.overlayAllowed() ? "OK" : "BRAK UPRAWNIENIA").append("\n")
                        .append("Ostatnie połączenie: ").append(s.lastCall).append("\n")
                        .append("Wersja: ").append(BuildConfig.VERSION_NAME);
            }
            status.setText(sb.toString());
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        main.post(refresher);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresher);
        super.onPause();
    }
}
