package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Sleep timer picker, drawn like the Inputs dropdown (popup_inputs and item_input). Open it from a menu
 * with startActivity(new Intent(context, SleepTimerActivity.class)), from its own Home tile, or over adb:
 * am start -n com.gathin.tvhome/.SleepTimerActivity
 */
public class SleepTimerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(this);
        frame.setOnClickListener(v -> finish());
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, frame, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        boolean service = HomeKeyService.isRunning();
        long left = SleepTimer.remainingMs(this);
        View first = null;

        for (int minutes : SleepTimer.MINUTES) {
            String label = SleepTimer.label(this, minutes);
            View row = addRow(rows, label, null);
            row.setOnClickListener(v -> {
                if (!service) {
                    Toast.makeText(this, R.string.sleep_timer_need_service, Toast.LENGTH_LONG).show();
                    return;
                }
                SleepTimer.start(this, minutes);
                Toast.makeText(this, getString(R.string.sleep_timer_set, label), Toast.LENGTH_SHORT).show();
                finish();
            });
            if (first == null) first = row;
        }
        if (left > 0) {
            int minLeft = (int) Math.max(1, (left + 59_999L) / 60_000L);
            View row = addRow(rows, getString(R.string.sleep_timer_cancel),
                    getString(R.string.sleep_timer_left, SleepTimer.label(this, minLeft)));
            row.setOnClickListener(v -> {
                SleepTimer.cancel(this);
                Toast.makeText(this, R.string.sleep_timer_cancelled, Toast.LENGTH_SHORT).show();
                finish();
            });
        }
        if (!service) {
            TextView note = new TextView(this);
            note.setText(R.string.sleep_timer_need_service);
            note.setTextColor(0xB3FFFFFF);
            note.setTextSize(12f);
            note.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (6 * d));
            rows.addView(note);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (320 * d),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) (96 * d);
        lp.setMarginEnd((int) (56 * d));
        frame.addView(panel, lp);
        setContentView(frame);
        if (first != null) first.requestFocus();
    }

    private View addRow(LinearLayout rows, String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.status)).setText(status == null ? "" : status);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = rows.getChildCount() == 0 ? 0 : (int) (2 * getResources().getDisplayMetrics().density);
        rows.addView(row, lp);
        return row;
    }
}
