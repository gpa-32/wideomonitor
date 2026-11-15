package pl.wideodomofon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.UUID;

/** Zapisuje w ustawieniach konto SIP pobrane z serwera Domofon SIP (patrz {@link ServerLink}). */
public final class Provision {
    private Provision() {}

    /** Staly identyfikator tabletu - serwer rozpoznaje po nim ponowne parowanie tego samego urzadzenia. */
    static String deviceUid(Context ctx) {
        String id = null;
        try {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id == null || id.length() < 8) {
            SharedPreferences p = ctx.getSharedPreferences("provision", Context.MODE_PRIVATE);
            id = p.getString("uid", null);
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "");
                p.edit().putString("uid", id).apply();
            }
        }
        return "wd-" + id;
    }

    /** Pobiera konto i zapisuje je w ustawieniach. Zwraca opis dla uzytkownika. Nie wolac z watku glownego. */
    public static String apply(Context ctx, String link, String name) throws Exception {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "Tablet " + Build.MODEL;
        ServerLink.Account a = ServerLink.fetch(link, deviceUid(ctx), n);
        JSONObject cfg = new JSONObject();
        cfg.put("sip_enabled", true);
        cfg.put("sip_server", a.server + ":" + a.port);
        cfg.put("sip_user", a.user);
        cfg.put("sip_pass", a.pass);
        cfg.put("sip_transport", a.transport);
        cfg.put("sip_expires", 120);
        Cfg.update(cfg);
        L.i("Serwer: pobrano konto " + a.user + "@" + a.server + ":" + a.port + " (" + a.transport + ")");
        StringBuilder sb = new StringBuilder("Pobrano konto ").append(a.user);
        if (!a.extension.isEmpty()) sb.append(" (numer ").append(a.extension).append(")");
        if (!a.group.isEmpty()) sb.append(", grupa ").append(a.group);
        return sb.append(". Rejestracja nastąpi za chwilę.").toString();
    }
}
