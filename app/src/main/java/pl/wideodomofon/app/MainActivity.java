package pl.wideodomofon.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.EnumMap;
import java.util.Map;

/** Ekran glowny: stan urzadzenia i menu (ustawienia, kod QR, uprawnienia, dziennik). */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView permButton;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BootReceiver.startService(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(24));

        TextView head = new TextView(this);
        head.setText("Wideodomofon");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        head.setTextColor(Color.WHITE);
        box.addView(head);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTextColor(0xFFDDDDDD);
        status.setTextIsSelectable(true);
        status.setLineSpacing(dp(3), 1f);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFF1A242C);
        card.setCornerRadius(dp(12));
        status.setBackground(card);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(14), 0, dp(10));
        box.addView(status, slp);

        box.addView(menu("Ustawienia", 0xFF1565C0, v -> startActivity(new Intent(this, SettingsActivity.class))));
        box.addView(menu("Wgraj ustawienia kodem QR", 0xFF2E7D32, v -> scanQr()));
        box.addView(menu("Pokaż kod QR z ustawieniami", 0xFF37474F, v -> showQr()));
        box.addView(menu("Test ekranu dzwonienia", 0xFF37474F, v -> {
            IntercomService s = IntercomService.instance;
            if (s != null) s.testUi();
        }));
        permButton = menu("Uprawnienia", 0xFF37474F, v -> requestNextPermission());
        box.addView(permButton);
        box.addView(menu("Dziennik i diagnostyka", 0xFF37474F, v -> startActivity(new Intent(this, LogActivity.class))));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF10171C);
        sv.addView(box);
        setContentView(sv);
    }

    private TextView menu(String text, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(15), dp(18), dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------------ kod QR

    private void scanQr() {
        try {
            IntentIntegrator i = new IntentIntegrator(this);
            i.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            i.setPrompt("Zeskanuj kod QR z ustawieniami albo kod z panelu serwera");
            i.setBeepEnabled(false);
            i.setOrientationLocked(false);
            i.initiateScan();
        } catch (Throwable t) {
            L.e("Skaner QR", t);
            toast("Nie udało się uruchomić skanera: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (r != null) {
            if (r.getContents() != null) handleQr(r.getContents().trim());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Kod QR: eksport ustawien z innego urzadzenia (WD1:...) albo link z panelu serwera Domofon SIP. */
    void handleQr(final String text) {
        if (text.startsWith(Cfg.QR_PREFIX)) {
            new AlertDialog.Builder(this)
                    .setTitle("Wgrać ustawienia?")
                    .setMessage("Ustawienia z kodu QR zastąpią obecne (SIP, kamera, furtka, dźwięk).")
                    .setNegativeButton("Anuluj", null)
                    .setPositiveButton("Wgraj", (d, w) -> {
                        try {
                            int n = Cfg.importQr(text);
                            IntercomService s = IntercomService.instance;
                            if (s != null) s.applyConfig();
                            L.i("QR: wgrano ustawienia (" + n + " pozycji)");
                            toast("Wgrano ustawienia (" + n + " pozycji)");
                        } catch (Throwable t) {
                            L.e("QR: import ustawien", t);
                            toast("Kod QR jest uszkodzony: " + t.getMessage());
                        }
                    }).show();
        } else if (text.startsWith("http://") || text.startsWith("https://")) {
            String host = Uri.parse(text).getHost();
            new AlertDialog.Builder(this)
                    .setTitle("Pobrać konto SIP?")
                    .setMessage("Serwer: " + host + "\nUrządzenie zgłosi się do niego i pobierze konto SIP.")
                    .setNegativeButton("Anuluj", null)
                    .setPositiveButton("Pobierz", (d, w) -> provision(text))
                    .show();
        } else {
            toast("Ten kod QR nie zawiera ustawień wideodomofonu");
        }
    }

    private void provision(final String text) {
        {
            toast("Pobieram konto z serwera…");
            final String name = (Cfg.isPhone(this) ? "Telefon " : "Tablet ") + Build.MODEL;
            Thread t = new Thread(() -> {
                String msg;
                try {
                    msg = Provision.apply(getApplicationContext(), text, name);
                    IntercomService s = IntercomService.instance;
                    if (s != null) s.applyConfig();
                } catch (Throwable e) {
                    msg = "Nie udało się pobrać konta: " + e.getMessage();
                    L.i("QR: " + msg);
                }
                final String m = msg;
                runOnUiThread(() -> toast(m));
            }, "qr-provision");
            t.setDaemon(true);
            t.start();
        }
    }

    private void showQr() {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            int size = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(80));
            Bitmap bmp = new BarcodeEncoder().encodeBitmap(Cfg.exportQr(), BarcodeFormat.QR_CODE, size, size, hints);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setPadding(dp(16), dp(16), dp(16), dp(8));
            new AlertDialog.Builder(this)
                    .setTitle("Kod QR z ustawieniami")
                    .setMessage("Na drugim urządzeniu wybierz „Wgraj ustawienia kodem QR”. Kod zawiera hasła – nie udostępniaj go.")
                    .setView(iv)
                    .setPositiveButton("Zamknij", null)
                    .show();
        } catch (Throwable t) {
            L.e("Kod QR", t);
            toast("Nie udało się utworzyć kodu QR: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ uprawnienia

    private boolean micOk() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifOk() {
        try {
            return ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean overlayOk() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean batteryOk() {
        try {
            return ((PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Kazde nacisniecie prosi o kolejne brakujace uprawnienie. */
    private void requestNextPermission() {
        try {
            Uri pkg = Uri.parse("package:" + getPackageName());
            if (!micOk()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            } else if (!notifOk()) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } else if (!overlayOk()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg));
            } else if (!batteryOk()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg));
            } else {
                toast("Wszystkie uprawnienia są nadane");
            }
        } catch (Throwable t) {
            L.e("Uprawnienia", t);
            toast("Otwórz ustawienia systemu i nadaj uprawnienie ręcznie");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ stan

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            IntercomService s = IntercomService.instance;
            StringBuilder sb = new StringBuilder();
            if (s == null) {
                sb.append("Usługa uruchamia się…");
            } else {
                String ip = IntercomService.localIp();
                sb.append("SIP: ").append(s.sipStatus()).append("\n")
                        .append("Adres do dzwonienia po IP: ").append(ip).append(":").append(Cfg.i("sip_port")).append("\n");
                if (Cfg.b("web_enabled")) {
                    sb.append("Panel www: http://").append(ip).append(":").append(Cfg.i("web_port"))
                            .append("  (admin / ").append(Cfg.s("web_pass")).append(")\n");
                }
                sb.append("Ostatnie połączenie: ").append(s.lastCall).append("\n")
                        .append("Root: ").append(s.rootOk ? "tak" : "nie")
                        .append("   Watchdog: ").append(s.watchdogInfo).append("\n")
                        .append("Wersja: ").append(BuildConfig.VERSION_NAME);
            }
            status.setText(sb.toString());

            int missing = (micOk() ? 0 : 1) + (notifOk() ? 0 : 1) + (overlayOk() ? 0 : 1) + (batteryOk() ? 0 : 1);
            StringBuilder p = new StringBuilder("Uprawnienia: ");
            if (missing == 0) {
                p.append("wszystkie nadane");
            } else {
                p.append("brakuje");
                if (!micOk()) p.append(" · mikrofon");
                if (!notifOk()) p.append(" · powiadomienia");
                if (!overlayOk()) p.append(" · wyświetlanie nad aplikacjami");
                if (!batteryOk()) p.append(" · praca w tle bez oszczędzania baterii");
                p.append("  (dotknij, aby nadać)");
            }
            permButton.setText(p.toString());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(missing == 0 ? 0xFF37474F : 0xFFB45309);
            bg.setCornerRadius(dp(12));
            permButton.setBackground(bg);
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
