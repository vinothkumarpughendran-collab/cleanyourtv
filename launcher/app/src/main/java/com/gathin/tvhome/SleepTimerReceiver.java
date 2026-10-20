package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Fires when the sleep timer runs out: puts the TV to sleep through the accessibility service. */
public class SleepTimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SleepTimer.clear(context);
        if (!HomeKeyService.sleepNow()) {
            Toast.makeText(context, R.string.sleep_timer_need_service, Toast.LENGTH_LONG).show();
        }
    }
}
