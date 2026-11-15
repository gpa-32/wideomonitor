package pl.wideodomofon.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Iterator;

/** Ustawienia aplikacji (zapisywane jako JSON, edytowane przez panel www). */
public final class Cfg {
    private static SharedPreferences prefs;
    private static JSONObject data;

    private Cfg() {}

    private static JSONObject defaults() {
        JSONObject d = new JSONObject();
        try {
            // SIP
            d.put("sip_port", 5060);            // port nasluchu (polaczenia bezposrednie po IP)
            d.put("sip_enabled", false);        // rejestracja na serwerze SIP
            d.put("sip_server", "");
            d.put("sip_user", "");
            d.put("sip_pass", "");
            d.put("sip_transport", "udp");      // udp | tcp
            d.put("sip_expires", 300);
            // Obraz
            d.put("video_source", "sip");       // sip | rtsp | jpeg
            d.put("early_media", true);         // obraz SIP przed odebraniem
            d.put("rtsp_url", "");
            d.put("rtsp_tcp", true);
            d.put("jpeg_url", "");
            // Furtka
            d.put("gate_mode", "both");         // dtmf | http | both
            d.put("dtmf_code", "#");
            d.put("dtmf_method", "rfc2833");    // rfc2833 | info | both
            d.put("http_url", "");
            d.put("http_user", "");
            d.put("http_pass", "");
            d.put("hangup_after_open_s", 3);    // 0 = nie rozlaczaj po otwarciu
            // Rozmowa
            d.put("ring_enabled", true);
            d.put("ring_volume", -1);           // 0-100, -1 = nie zmieniaj
            d.put("call_volume", -1);           // 0-100, -1 = nie zmieniaj
            d.put("mic_gain_db", 0);
            d.put("speaker_gain_db", 0);
            d.put("echo_cancel", true);
            d.put("ring_timeout_s", 60);
            d.put("max_call_s", 180);
            // Zachowanie
            d.put("restore_prev", true);
            d.put("screen_off_after", true);
            d.put("nightly_restart_hour", -1);  // 0-23, -1 = wylaczony
            // Panel www
            d.put("web_enabled", true);         // na telefonie domyslnie wylaczony
            d.put("web_port", 8080);
            d.put("web_pass", "");
        } catch (Exception ignored) {
        }
        return d;
    }

    public static synchronized void init(Context c) {
        if (data != null) return;
        prefs = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        data = defaults();
        try {
            JSONObject saved = new JSONObject(prefs.getString("json", "{}"));
            merge(saved);
            if (!saved.has("web_enabled")) {
                // Pierwsze uruchomienie: na telefonie konfiguracja odbywa sie z menu, panel www jest wylaczony.
                data.put("web_enabled", !isPhone(c));
                save();
            }
        } catch (Exception ignored) {
        }
        if (data.optString("web_pass", "").isEmpty()) {
            String chars = "abcdefghjkmnpqrstuvwxyz23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            try {
                data.put("web_pass", sb.toString());
            } catch (Exception ignored) {
            }
            save();
        }
    }

    /** Przyjmuje tylko znane klucze i pilnuje typow. */
    private static void merge(JSONObject in) {
        JSONObject def = defaults();
        Iterator<String> it = def.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!in.has(k)) continue;
            Object dv = def.opt(k);
            try {
                if (dv instanceof Boolean) {
                    Object v = in.opt(k);
                    boolean b = v instanceof Boolean ? (Boolean) v
                            : "true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v));
                    data.put(k, b);
                } else if (dv instanceof Integer) {
                    data.put(k, Integer.parseInt(String.valueOf(in.opt(k)).trim()));
                } else {
                    data.put(k, String.valueOf(in.opt(k)).trim());
                }
            } catch (Exception ignored) {
                // zla wartosc - zostaje poprzednia
            }
        }
    }

    private static void save() {
        prefs.edit().putString("json", data.toString()).apply();
    }

    public static synchronized void update(JSONObject in) {
        String oldPass = data.optString("web_pass", "");
        merge(in);
        if (data.optString("web_pass", "").isEmpty()) {
            try {
                data.put("web_pass", oldPass);
            } catch (Exception ignored) {
            }
        }
        save();
    }

    public static final String QR_PREFIX = "WD1:";
    /** Ustawienia zwiazane z konkretnym urzadzeniem - nie przenosimy ich kodem QR. */
    private static final String[] QR_SKIP = {"web_enabled", "web_port", "web_pass"};

    public static boolean isPhone(Context c) {
        return c.getResources().getConfiguration().smallestScreenWidthDp < 600;
    }

    /** Tresc kodu QR: tylko ustawienia rozne od domyslnych. */
    public static synchronized String exportQr() throws Exception {
        JSONObject def = defaults();
        JSONObject out = new JSONObject();
        Iterator<String> it = def.keys();
        while (it.hasNext()) {
            String k = it.next();
            boolean skip = false;
            for (String s : QR_SKIP) if (s.equals(k)) skip = true;
            if (skip) continue;
            if (!String.valueOf(def.opt(k)).equals(String.valueOf(data.opt(k)))) out.put(k, data.opt(k));
        }
        return QR_PREFIX + out.toString();
    }

    /** Wgrywa ustawienia z kodu QR; pozycje nieobecne w kodzie wracaja do wartosci domyslnych. Zwraca liczbe pozycji. */
    public static synchronized int importQr(String text) throws Exception {
        if (text == null || !text.startsWith(QR_PREFIX)) throw new Exception("to nie jest kod ustawień");
        JSONObject in = new JSONObject(text.substring(QR_PREFIX.length()));
        JSONObject full = defaults();
        for (String s : QR_SKIP) full.remove(s);
        int n = 0;
        Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (full.has(k)) {
                full.put(k, in.opt(k));
                n++;
            }
        }
        update(full);
        return n;
    }

    public static synchronized JSONObject all() {
        try {
            return new JSONObject(data.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static synchronized String s(String k) {
        return data.optString(k, "");
    }

    public static synchronized int i(String k) {
        return data.optInt(k, 0);
    }

    public static synchronized boolean b(String k) {
        return data.optBoolean(k, false);
    }
}
