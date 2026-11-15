package pl.wideodomofon.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pobieranie konta SIP z serwera Domofon SIP na podstawie linku z jego panelu:
 * - link urzadzenia:  https://serwer/p/TOKEN.xml (lub .json) - kod QR / "Kopiuj link" przy urzadzeniu,
 * - link parowania:   https://serwer/api/pair/TOKEN - kod QR / "Kopiuj link" w grupie (tablet dodaje sie sam).
 * Klasa bez zaleznosci od Androida (latwa do przetestowania).
 */
public final class ServerLink {
    private static final Pattern LINK = Pattern.compile(
            "^(https?)://([A-Za-z0-9.\\-]+(?::\\d{1,5})?)/(?:(p)/([A-Za-z0-9_\\-]{8,})(?:\\.xml|\\.json)?|(api/pair)/([A-Za-z0-9_\\-]{8,}))/?$");
    private static final Pattern PRIVATE_IP = Pattern.compile(
            "^(127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)(:\\d+)?$");
    private static final int MAX_BODY = 64 * 1024;

    private ServerLink() {}

    public static final class Account {
        public String server = "";
        public int port = 5060;
        public String transport = "udp";
        public String user = "";
        public String pass = "";
        public String displayName = "";
        public String extension = "";
        public String group = "";
        public boolean paired;
    }

    public static final class LinkException extends Exception {
        public LinkException(String m) {
            super(m);
        }
    }

    public static Account fetch(String link, String deviceUid, String deviceName) throws LinkException {
        Matcher m = LINK.matcher(link == null ? "" : link.trim());
        if (!m.matches()) {
            throw new LinkException("Nieprawidłowy link. Wklej link urządzenia (…/p/….xml) albo link parowania (…/api/pair/…) z panelu serwera.");
        }
        String scheme = m.group(1), host = m.group(2);
        if ("http".equals(scheme) && !PRIVATE_IP.matcher(host).matches()) {
            throw new LinkException("Link musi zaczynać się od https:// (dane logowania nie mogą iść przez internet bez szyfrowania).");
        }
        boolean pair = m.group(5) != null;
        String url = scheme + "://" + host + (pair ? "/api/pair/" + m.group(6) : "/p/" + m.group(4) + ".json");
        String body;
        try {
            JSONObject req = null;
            if (pair) {
                req = new JSONObject();
                req.put("device_uid", deviceUid);
                req.put("name", deviceName);
            }
            body = http(url, req);
        } catch (LinkException e) {
            throw e;
        } catch (Exception e) {
            throw new LinkException("Brak połączenia z serwerem: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
        }
        try {
            JSONObject o = new JSONObject(body);
            JSONObject sip = o.getJSONObject("sip");
            Account a = new Account();
            a.server = sip.getString("server").trim();
            a.port = sip.optInt("port", 5060);
            a.transport = sip.optString("transport", "udp").toLowerCase();
            a.user = sip.getString("username").trim();
            a.pass = sip.getString("password");
            a.displayName = sip.optString("display_name", "");
            a.extension = sip.optString("extension", "");
            JSONObject g = o.optJSONObject("group");
            if (g != null) a.group = (g.optString("number", "") + " " + g.optString("name", "")).trim();
            a.paired = pair;
            if (a.server.isEmpty() || a.user.isEmpty() || a.port < 1 || a.port > 65535
                    || !a.server.matches("[A-Za-z0-9.\\-]+")) {
                throw new LinkException("Serwer zwrócił niepełne dane konta.");
            }
            if (!"udp".equals(a.transport) && !"tcp".equals(a.transport)) {
                throw new LinkException("Serwer podał transport „" + a.transport
                        + "”, którego aplikacja nie obsługuje. Ustaw dla tego urządzenia UDP lub TCP w panelu serwera.");
            }
            return a;
        } catch (LinkException e) {
            throw e;
        } catch (Exception e) {
            throw new LinkException("Nieoczekiwana odpowiedź serwera.");
        }
    }

    private static String http(String url, JSONObject post) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Wideodomofon");
            if (post != null) {
                byte[] data = post.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.close();
            }
            int code = c.getResponseCode();
            if (code == 404) {
                throw new LinkException(post != null
                        ? "Parowanie nie jest aktywne. W panelu serwera otwórz grupę i kliknij „Rozpocznij parowanie”."
                        : "Link jest nieważny albo wygasł. Wygeneruj nowy link przy urządzeniu w panelu serwera.");
            }
            if (code != 200) throw new LinkException("Serwer odpowiedział kodem " + code + ".");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > MAX_BODY) throw new LinkException("Zbyt długa odpowiedź serwera.");
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
