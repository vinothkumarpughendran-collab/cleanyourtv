package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Compact Control Center: sleep timer and power off. Styled after the Inputs dropdown. Open over adb:
 * am start -n com.gathin.tvhome/.ControlCenterActivity
 */
public class ControlCenterActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(this);
        frame.setOnClickListener(v -> finish());

        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, frame, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        View first = addRow(rows, getString(R.string.control_sleep_timer), null, () -> {
            startActivity(new Intent(this, SleepTimerActivity.class));
            finish();
        });

        boolean service = HomeKeyService.isRunning();
        addRow(rows, getString(R.string.control_power_off),
                service ? null : getString(R.string.control_need_service), () -> {
            if (!HomeKeyService.sleepNow()) {
                Toast.makeText(this, R.string.control_power_off_failed, Toast.LENGTH_LONG).show();
            }
            finish();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (280 * d),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) (96 * d);
        lp.setMarginEnd((int) (56 * d));
        frame.addView(panel, lp);
        setContentView(frame);
        if (first != null) first.requestFocus();
    }

    private View addRow(LinearLayout rows, String title, String status, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.status)).setText(status == null ? "" : status);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = rows.getChildCount() == 0 ? 0 : (int) (2 * getResources().getDisplayMetrics().density);
        rows.addView(row, lp);
        return row;
    }
}
