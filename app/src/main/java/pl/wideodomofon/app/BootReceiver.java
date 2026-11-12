package pl.wideodomofon.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Start po uruchomieniu tabletu, po aktualizacji oraz cykliczny alarm podtrzymujacy. */
public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_KEEPALIVE = "pl.wideodomofon.app.KEEPALIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        startService(context);
        scheduleKeepAlive(context, 5 * 60 * 1000L);
    }

    public static void startService(Context c) {
        try {
            c.startForegroundService(new Intent(c, IntercomService.class));
        } catch (Throwable t) {
            L.e("Nie udalo sie uruchomic uslugi", t);
        }
    }

    public static void scheduleKeepAlive(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_KEEPALIVE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable t) {
            L.e("Alarm podtrzymujacy", t);
        }
    }
}
