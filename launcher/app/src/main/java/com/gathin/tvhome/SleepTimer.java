package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Sleep timer: one alarm-clock entry (exact, needs no permission) that fires SleepTimerReceiver. The
 * deadline is also saved so the picker can show how long is left. Alarms do not survive a reboot, and a
 * stale deadline is treated as no timer.
 */
final class SleepTimer {
    static final int[] MINUTES = {15, 30, 45, 60, 90, 120};
    private static final String PREFS = "sleep_timer";
    private static final String KEY_DEADLINE = "deadline";

    private SleepTimer() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent fireIntent(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimerReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void start(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long when = System.currentTimeMillis() + minutes * 60_000L;
        PendingIntent show = PendingIntent.getActivity(c, 1, new Intent(c, SleepTimerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), fireIntent(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, fireIntent(c));
        }
        prefs(c).edit().putLong(KEY_DEADLINE, when).apply();
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(fireIntent(c));
        clear(c);
    }

    static void clear(Context c) {
        prefs(c).edit().remove(KEY_DEADLINE).apply();
    }

    static long remainingMs(Context c) {
        long left = prefs(c).getLong(KEY_DEADLINE, 0L) - System.currentTimeMillis();
        return Math.max(left, 0L);
    }

    /** 15 min, 1 h, 1 h 30 min ... */
    static String label(Context c, int minutes) {
        if (minutes < 60) return c.getString(R.string.sleep_timer_min, minutes);
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? c.getString(R.string.sleep_timer_hour, h) : c.getString(R.string.sleep_timer_hour_min, h, m);
    }
}
