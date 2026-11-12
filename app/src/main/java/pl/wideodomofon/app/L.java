package pl.wideodomofon.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Dziennik zdarzen: logcat + plik + bufor w pamieci (widoczny w panelu www). */
public final class L {
    private static final String TAG = "Wideodomofon";
    private static final int MAX_LINES = 400;
    private static final long MAX_FILE = 300 * 1024;
    private static final ArrayDeque<String> buf = new ArrayDeque<>();
    private static File file;

    private L() {}

    public static synchronized void init(Context c) {
        file = new File(c.getFilesDir(), "log.txt");
        try {
            if (file.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(file));
                String line;
                while ((line = r.readLine()) != null) {
                    buf.addLast(line);
                    while (buf.size() > MAX_LINES) buf.removeFirst();
                }
                r.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void i(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        buf.addLast(line);
        while (buf.size() > MAX_LINES) buf.removeFirst();
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE) {
                File old = new File(file.getParentFile(), "log.1.txt");
                old.delete();
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            w.write(line);
            w.write('\n');
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        if (t != null) Log.e(TAG, msg, t);
        i("BLAD: " + msg + (t != null ? " (" + t + ")" : ""));
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : buf) sb.append(s).append('\n');
        return sb.toString();
    }
}
