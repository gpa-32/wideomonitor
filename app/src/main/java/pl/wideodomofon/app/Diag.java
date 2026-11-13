package pl.wideodomofon.app;

import android.net.Uri;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Diagnostyka polaczenia z kamera bramofonu, wykonywana z tabletu (wynik w panelu www). */
public final class Diag {
    private Diag() {}

    public static String camera() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tablet: ").append(IntercomService.localIp()).append("\n");
        String rtsp = Cfg.s("rtsp_url");
        String host = null;
        if (rtsp.isEmpty()) {
            sb.append("Adres RTSP nie jest ustawiony.\n");
        } else {
            host = rtspProbe(rtsp, sb);
        }
        String jpeg = Cfg.s("jpeg_url");
        List<String> urls = new ArrayList<>();
        if (!jpeg.isEmpty()) urls.add(jpeg);
        if (host != null) {
            urls.add("http://" + host + ":8080/picture.jpg");
            urls.add("http://" + host + "/picture.jpg");
        }
        sb.append("\n--- Zdjecia JPEG ---\n");
        for (String u : urls) {
            sb.append(u.replaceAll("//[^/@]*@", "//***@")).append(" -> ");
            try {
                byte[] b = httpGet(u, 4000);
                boolean isJpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
                sb.append(isJpeg ? "OK, zdjecie " + b.length + " B" : "odpowiedz " + b.length + " B, ale to nie JPEG").append("\n");
            } catch (Throwable t) {
                sb.append("blad: ").append(t.getMessage() != null ? t.getMessage() : t.toString()).append("\n");
            }
        }
        sb.append("\n--- Ostatnie komunikaty odtwarzacza VLC ---\n").append(vlcLog());
        return sb.toString();
    }

    /** Reczne zapytania RTSP: pokazuje, czy bramofon odpowiada, czy zada hasla i jaki kodek nadaje. */
    private static String rtspProbe(String rtsp, StringBuilder sb) {
        String host = null;
        try {
            Uri u = Uri.parse(rtsp);
            host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 554;
            String clean = "rtsp://" + host + (u.getPort() > 0 ? ":" + port : "")
                    + (u.getEncodedPath() == null ? "" : u.getEncodedPath())
                    + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery());
            sb.append("--- RTSP ").append(clean).append(" ---\n");
            long t0 = System.currentTimeMillis();
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(4000);
            sb.append("Polaczenie TCP z ").append(host).append(":").append(port).append(" OK (")
                    .append(System.currentTimeMillis() - t0).append(" ms)\n");
            OutputStream o = s.getOutputStream();
            InputStream in = s.getInputStream();
            sb.append("OPTIONS -> ").append(exchange(o, in,
                    "OPTIONS " + clean + " RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Wideodomofon\r\n\r\n", false)).append("\n");
            String auth = "";
            if (u.getUserInfo() != null) {
                auth = "Authorization: Basic " + Base64.encodeToString(
                        u.getUserInfo().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\r\n";
            }
            sb.append("DESCRIBE -> ").append(exchange(o, in,
                    "DESCRIBE " + clean + " RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: Wideodomofon\r\nAccept: application/sdp\r\n"
                            + auth + "\r\n", true)).append("\n");
            s.close();
        } catch (Throwable t) {
            sb.append("BLAD polaczenia RTSP: ").append(t).append("\n");
        }
        return host;
    }

    private static String exchange(OutputStream o, InputStream in, String req, boolean full) throws Exception {
        o.write(req.getBytes(StandardCharsets.UTF_8));
        o.flush();
        byte[] buf = new byte[8192];
        int n = 0;
        long end = System.currentTimeMillis() + 3000;
        try {
            while (n < buf.length && System.currentTimeMillis() < end) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
                String sofar = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                int he = sofar.indexOf("\r\n\r\n");
                if (he >= 0) {
                    int cl = 0;
                    for (String line : sofar.substring(0, he).split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            cl = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    if (n >= he + 4 + cl) break;
                }
            }
        } catch (java.net.SocketTimeoutException ignored) {
        }
        if (n == 0) return "brak odpowiedzi";
        String resp = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
        if (!full) {
            String[] lines = resp.split("\r\n");
            StringBuilder sb = new StringBuilder(lines[0]);
            for (String l : lines) if (l.toLowerCase().startsWith("public:")) sb.append(" | ").append(l);
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : resp.split("\r\n")) {
            String ll = l.toLowerCase();
            if (l.startsWith("RTSP/") || ll.startsWith("www-authenticate") || ll.startsWith("content-")
                    || l.startsWith("m=") || l.startsWith("a=rtpmap") || l.startsWith("a=control")
                    || l.startsWith("a=fmtp") || l.startsWith("c=")) {
                sb.append("\n    ").append(l.length() > 160 ? l.substring(0, 160) + "..." : l);
            }
        }
        return sb.toString();
    }

    /** GET z opcjonalnym logowaniem Basic podanym w adresie (http://user:haslo@host/...). */
    public static byte[] httpGet(String url, int timeoutMs) throws Exception {
        String auth = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(https?://)([^/@:]+):([^/@]*)@(.*)$").matcher(url);
        if (m.matches()) {
            auth = "Basic " + Base64.encodeToString(
                    (m.group(2) + ":" + m.group(3)).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            url = m.group(1) + m.group(4);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new Exception("odpowiedz zbyt duza");
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String vlcLog() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> keep = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase();
                if (l.contains("vlc") && (l.contains("live555") || l.contains("rtsp") || l.contains("error")
                        || l.contains("fail") || l.contains("codec") || l.contains("decoder") || l.contains("no data")
                        || l.contains("vout") || l.contains("es_out"))) {
                    keep.add(line.length() > 220 ? line.substring(0, 220) : line);
                }
            }
            r.close();
            int from = Math.max(0, keep.size() - 60);
            for (int i = from; i < keep.size(); i++) sb.append(keep.get(i)).append("\n");
            if (keep.isEmpty()) sb.append("(brak wpisow - uruchom najpierw \"Test ekranu na tablecie\")\n");
        } catch (Throwable t) {
            sb.append("nie udalo sie odczytac: ").append(t).append("\n");
        }
        return sb.toString();
    }
}
