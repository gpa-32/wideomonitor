package pl.wideodomofon.app;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Polecenia z uprawnieniami root (su). */
public final class Root {
    public static final class Result {
        public int code = -1;
        public String out = "";
    }

    private static volatile Boolean available;

    private Root() {}

    public static Result run(String cmd, long timeoutMs) {
        final Result res = new Result();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (sb) {
                            if (sb.length() < 200000) sb.append(new String(b, 0, n));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "root-out");
            reader.setDaemon(true);
            reader.start();
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                res.code = p.exitValue();
                reader.join(500);
            } else {
                p.destroy();
            }
            synchronized (sb) {
                res.out = sb.toString();
            }
        } catch (Throwable t) {
            if (p != null) p.destroy();
        }
        return res;
    }

    public static boolean available() {
        Boolean a = available;
        if (a != null) return a;
        Result r = run("id -u", 8000);
        boolean ok = r.code == 0 && r.out.trim().equals("0");
        available = ok;
        return ok;
    }

    public static void resetCache() {
        available = null;
    }

    public static void runAsync(final String cmd) {
        Thread t = new Thread(() -> {
            if (available()) run(cmd, 10000);
        }, "root-cmd");
        t.setDaemon(true);
        t.start();
    }
}
