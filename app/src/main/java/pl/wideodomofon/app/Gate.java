package pl.wideodomofon.app;

import android.util.Base64;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Otwieranie furtki zapytaniem HTTP (bez autoryzacji, Basic albo Digest). */
public final class Gate {
    private Gate() {}

    /** Zwraca opis wyniku; rzuca wyjatek przy bledzie. */
    public static String httpOpen() throws Exception {
        String url = Cfg.s("http_url");
        String user = Cfg.s("http_user");
        String pass = Cfg.s("http_pass");
        if (url.isEmpty()) throw new Exception("Nie ustawiono adresu URL furtki");

        // Dane logowania wpisane w adresie: http://user:haslo@host/...
        Matcher m = Pattern.compile("^(https?://)([^/@:]+):([^/@]*)@(.*)$").matcher(url);
        if (m.matches()) {
            if (user.isEmpty()) {
                user = m.group(2);
                pass = m.group(3);
            }
            url = m.group(1) + m.group(4);
        }

        Object[] r = request(url, null);
        int code = (Integer) r[0];
        if (code == 401 && !user.isEmpty()) {
            String challenge = (String) r[2];
            String auth;
            if (challenge != null && challenge.toLowerCase().startsWith("digest")) {
                auth = digest(challenge, url, user, pass);
            } else {
                auth = "Basic " + Base64.encodeToString(
                        (user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
            r = request(url, auth);
            code = (Integer) r[0];
        }
        String body = (String) r[1];
        if (body.length() > 120) body = body.substring(0, 120);
        body = body.replace('\n', ' ').trim();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + body);
        return "HTTP " + code + (body.isEmpty() ? "" : " " + body);
    }

    /** @return {kod, tresc, naglowek WWW-Authenticate} */
    private static Object[] request(String url, String authHeader) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
            int code = c.getResponseCode();
            String challenge = null;
            Map<String, List<String>> headers = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("WWW-Authenticate")) {
                    for (String v : e.getValue()) {
                        if (challenge == null || v.toLowerCase().startsWith("digest")) challenge = v;
                    }
                }
            }
            String body = "";
            try {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) {
                    byte[] b = new byte[2048];
                    int n = in.read(b);
                    if (n > 0) body = new String(b, 0, n, StandardCharsets.UTF_8);
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            return new Object[]{code, body, challenge};
        } finally {
            c.disconnect();
        }
    }

    private static String digest(String challenge, String url, String user, String pass) throws Exception {
        Map<String, String> p = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(challenge);
        while (m.find()) p.put(m.group(1).toLowerCase(), m.group(2) != null ? m.group(2) : m.group(3));
        String realm = p.containsKey("realm") ? p.get("realm") : "";
        String nonce = p.containsKey("nonce") ? p.get("nonce") : "";
        String qop = p.get("qop");
        String opaque = p.get("opaque");
        URL u = new URL(url);
        String uri = u.getPath().isEmpty() ? "/" : u.getPath();
        if (u.getQuery() != null) uri += "?" + u.getQuery();

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5("GET:" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"" + user + "\", realm=\"" + realm
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\"");
        String response;
        if (qop != null && qop.toLowerCase().contains("auth")) {
            byte[] rnd = new byte[8];
            new SecureRandom().nextBytes(rnd);
            String cnonce = hex(rnd);
            String nc = "00000001";
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        h.append(", response=\"").append(response).append("\"");
        if (opaque != null) h.append(", opaque=\"").append(opaque).append("\"");
        return h.toString();
    }

    private static String md5(String s) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
