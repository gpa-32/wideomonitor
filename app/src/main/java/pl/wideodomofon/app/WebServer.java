package pl.wideodomofon.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Panel konfiguracyjny www (logowanie: admin + haslo widoczne na ekranie tabletu). */
public class WebServer extends NanoHTTPD {
    private final IntercomService svc;

    public WebServer(IntercomService svc, int port) {
        super(port);
        this.svc = svc;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!authorized(session)) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ignored) {
                }
                Response r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8",
                        "Wymagane logowanie");
                r.addHeader("WWW-Authenticate", "Basic realm=\"Wideodomofon\", charset=\"UTF-8\"");
                return r;
            }
            String uri = session.getUri();
            Method method = session.getMethod();

            if (method == Method.GET) {
                if ("/".equals(uri) || "/index.html".equals(uri)) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", asset("index.html"));
                }
                if ("/api/config".equals(uri)) return json(Cfg.all());
                if ("/api/status".equals(uri)) return json(status());
                if ("/api/log".equals(uri)) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", L.dump());
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
            }

            if (method == Method.POST) {
                // Ochrona przed wywolaniem z obcej strony (CSRF): wymagany wlasny naglowek.
                if (!"1".equals(session.getHeaders().get("x-wideodomofon"))) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "403");
                }
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if ("/api/config".equals(uri)) {
                    Cfg.update(new JSONObject(body == null ? "{}" : body));
                    svc.applyConfig();
                    return json(new JSONObject().put("ok", true));
                }
                if ("/api/action".equals(uri)) {
                    String name = new JSONObject(body == null ? "{}" : body).optString("name");
                    switch (name) {
                        case "open":
                            svc.openGate("panel www");
                            break;
                        case "answer":
                            svc.answer();
                            break;
                        case "hangup":
                            svc.hangup();
                            break;
                        case "test_ui":
                            svc.testUi();
                            break;
                        case "restart_sip":
                            svc.restartSip();
                            break;
                        case "restart_app":
                            new Thread(() -> {
                                try {
                                    Thread.sleep(800);
                                } catch (InterruptedException ignored) {
                                }
                                IntercomService.die("Restart aplikacji z panelu www");
                            }).start();
                            break;
                        default:
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "nieznana akcja");
                    }
                    return json(new JSONObject().put("ok", true));
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404");
        } catch (Throwable t) {
            L.e("Panel www", t);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Blad: " + t);
        }
    }

    private boolean authorized(IHTTPSession session) {
        String h = session.getHeaders().get("authorization");
        if (h == null || !h.startsWith("Basic ")) return false;
        try {
            String dec = new String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
            byte[] expected = ("admin:" + Cfg.s("web_pass")).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, dec.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("version", BuildConfig.VERSION_NAME);
        o.put("uptime_s", svc.uptimeSeconds());
        o.put("ip", IntercomService.localIp());
        o.put("sip", svc.sipStatus());
        o.put("call_state", svc.state == IntercomService.IDLE ? "czuwanie"
                : svc.state == IntercomService.RINGING ? "dzwoni: " + svc.caller : "rozmowa: " + svc.caller);
        o.put("last_call", svc.lastCall);
        o.put("gate", svc.gateResult);
        o.put("root", svc.rootOk);
        o.put("watchdog", svc.watchdogInfo);
        o.put("overlay", svc.overlayAllowed());
        o.put("mic", svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        return o;
    }

    private Response json(JSONObject o) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private String asset(String name) throws Exception {
        InputStream in = svc.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
