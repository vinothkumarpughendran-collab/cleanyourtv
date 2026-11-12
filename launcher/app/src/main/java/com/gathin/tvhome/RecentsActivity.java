package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The app switcher, laid out like tvOS: the chosen app large, older ones stacked behind it on the left, the next
 *  one peeking in from the right. Left and Right move, OK opens, Up closes the app, Back leaves. */
public class RecentsActivity extends Activity {
    /** True while the switcher is on screen, so a hold of Back there does not open a second one. */
    static boolean showing;

    /** How far the stacked cards sit left of the chosen one, as a share of the screen width, for 0 to 3 steps back. */
    private static final float[] STACK_X = {0f, 0.10f, 0.14f, 0.16f};

    private static final class Entry {
        String pkg;
        Intent launch;
        View item;
        View name;
        View card;
        int tint;
        GradientDrawable ring;
    }

    private final List<Entry> entries = new ArrayList<>();
    private FrameLayout stage;
    private float dp;
    private float pos;
    private int index;
    private int screenW, cardW, cardH, headH, gap, base;
    private ValueAnimator slide;
    private long created;
    private boolean leaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showing = true;
        created = SystemClock.uptimeMillis();
        DisplayMetrics m = getResources().getDisplayMetrics();
        dp = m.density;
        screenW = m.widthPixels;
        int screenH = m.heightPixels;
        cardW = Math.round(screenW * 0.70f);
        cardH = Math.round(cardW * 9f / 16f);
        if (cardH > screenH * 0.70f) {
            cardH = Math.round(screenH * 0.70f);
            cardW = Math.round(cardH * 16f / 9f);
        }
        headH = px(56);
        gap = px(28);
        base = Math.round(screenW * 0.17f);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF0A0C14);
        stage.setClipChildren(false);
        stage.setAlpha(0f);
        setContentView(stage);
        fill(Math.round((screenH - cardH - headH) / 2f));
        stage.animate().alpha(1f).setDuration(180).start();
    }

    @Override
    protected void onPause() {
        showing = false;
        super.onPause();
    }

    private int px(int v) {
        return Math.round(v * dp);
    }

    /** Our own way out (OK on a card, closing the last card): the guard below lets it through. */
    private void leave() {
        leaving = true;
        finish();
    }

    /** A stray Back right after the switcher opens must not close it. */
    @Override
    public void finish() {
        if (!leaving && SystemClock.uptimeMillis() - created < 800) return;
        super.finish();
    }

    private void fill(int top) {
        PackageManager pm = getPackageManager();
        for (String pkg : RecentApps.list(this)) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            try {
                addEntry(pm, pkg, launch, top);
            } catch (PackageManager.NameNotFoundException gone) {
                RecentApps.remove(this, pkg);
            }
        }
        if (entries.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.recents_none);
            none.setTextColor(Color.WHITE);
            none.setTextSize(26);
            stage.addView(none, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            stage.postDelayed(this::leave, 2500);
            return;
        }
        index = entries.size() > 1 && entries.get(0).pkg.equals(RecentApps.front) ? 1 : 0;
        pos = index;
        place();
        bg = darken(entries.get(index).tint);
        stage.setBackgroundColor(bg);
        buildHud();
        refreshSelection();
    }

    private void addEntry(PackageManager pm, String pkg, Intent launch, int top)
            throws PackageManager.NameNotFoundException {
        CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        Drawable icon = pm.getApplicationIcon(pkg);
        Drawable art = pm.getActivityBanner(launch);
        if (art == null) art = pm.getApplicationBanner(pkg);
        final boolean banner = art != null;
        if (art == null) art = icon;

        FrameLayout item = new FrameLayout(this);
        item.setClipChildren(false);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iconView = new ImageView(this);
        iconView.setImageDrawable(icon);
        head.addView(iconView, new LinearLayout.LayoutParams(px(40), px(40)));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(23);
        name.setSingleLine(true);
        name.setPadding(px(14), 0, 0, 0);
        head.addView(name);
        item.addView(head, new FrameLayout.LayoutParams(cardW, headH));

        // The app's own colour, taken from its artwork; apps with grey or white logos keep a hashed hue.
        final int tint = dominant(art, (pkg.hashCode() & 0x7fffffff) % 360);
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        GradientDrawable plate = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.HSVToColor(new float[]{hsv[0], hsv[1], 0.62f}),
                        Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1] + 0.10f), 0.20f})});
        plate.setCornerRadius(px(22));
        plate.setStroke(px(1), 0x33FFFFFF);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(plate);
        card.setClipToOutline(true);
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(art);
        if (banner) {
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(logo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int logoW = Math.round(cardH * 0.36f);
            card.addView(logo, new FrameLayout.LayoutParams(logoW, logoW, Gravity.CENTER));
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(22));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(px(1), 0x33FFFFFF);
        card.setForeground(ring);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(cardW, cardH);
        cardParams.topMargin = headH;
        item.addView(card, cardParams);

        FrameLayout.LayoutParams itemParams = new FrameLayout.LayoutParams(cardW, headH + cardH);
        itemParams.topMargin = top;
        item.setPivotX(0f);
        item.setPivotY((headH + cardH) / 2f);
        stage.addView(item, itemParams);

        Entry e = new Entry();
        e.pkg = pkg;
        e.launch = launch;
        e.item = item;
        e.name = name;
        e.card = card;
        e.tint = tint;
        e.ring = ring;
        entries.add(e);
    }

    private int bg = 0xFF0A0C14;
    private ValueAnimator bgAnim;

    /** A dark, opaque version of the app's colour for the screen behind the cards. */
    private static int darken(int tint) {
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        return Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1] * 0.9f), 0.20f});
    }

    private void tintBackground(int tint) {
        final int to = darken(tint);
        if (to == bg) return;
        if (bgAnim != null) bgAnim.cancel();
        bgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), bg, to);
        bgAnim.setDuration(300);
        bgAnim.addUpdateListener(a -> {
            bg = (Integer) a.getAnimatedValue();
            stage.setBackgroundColor(bg);
        });
        bgAnim.start();
    }

    /** The most common saturated hue in a 24 by 24 copy of the artwork, or the fallback hue when it has none. */
    private static int dominant(Drawable d, int fallbackHue) {
        float[] hsv = new float[3];
        float[] weight = new float[36];
        float[] sat = new float[36];
        int[] count = new int[36];
        try {
            int n = 24;
            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, n, n);
            d.draw(new Canvas(b));
            d.setBounds(old);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue;
                    int k = Math.min(35, (int) (hsv[0] / 10f));
                    weight[k] += hsv[1] * hsv[2];
                    sat[k] += hsv[1];
                    count[k]++;
                }
            }
            b.recycle();
        } catch (RuntimeException ignored) {
        }
        int best = -1;
        float top = 0f;
        for (int k = 0; k < 36; k++) {
            if (weight[k] > top) {
                top = weight[k];
                best = k;
            }
        }
        if (best < 0 || top < 3f) return Color.HSVToColor(new float[]{fallbackHue, 0.50f, 0.60f});
        float s = Math.max(0.45f, Math.min(0.85f, sat[best] / count[best]));
        return Color.HSVToColor(new float[]{best * 10f + 5f, s, 0.60f});
    }

    /** Puts every card where it belongs for the current (fractional) position. */
    private void place() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float d = i - pos;
            float x, s, a;
            if (d >= 0f) {
                x = base + d * (cardW + gap);
                s = 1f - 0.07f * Math.min(d, 1f);
                a = 1f - 0.45f * Math.min(d, 1f);
            } else {
                float t = Math.min(-d, 3f);
                int lo = (int) t;
                int hi = Math.min(lo + 1, 3);
                float off = STACK_X[lo] + (STACK_X[hi] - STACK_X[lo]) * (t - lo);
                x = base - off * screenW;
                s = 1f - 0.04f * t;
                a = -d > 3.5f ? 0f : Math.max(0f, 0.75f - 0.2f * t);
            }
            e.item.setTranslationX(x);
            e.item.setScaleX(s);
            e.item.setScaleY(s);
            e.item.setAlpha(a);
            e.item.setTranslationZ(10f - Math.abs(d));
            e.item.setVisibility(a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
            e.name.setAlpha(Math.max(0f, 1f - 2f * Math.abs(d)));
        }
    }

    private void slideTo(float target) {
        if (!entries.isEmpty()) {
            tintBackground(entries.get(Math.max(0, Math.min(entries.size() - 1, Math.round(target)))).tint);
        }
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(pos, target);
        slide.setDuration(300);
        slide.setInterpolator(new DecelerateInterpolator(1.6f));
        slide.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            place();
        });
        slide.start();
    }

    private void move(int delta) {
        int target = Math.max(0, Math.min(entries.size() - 1, index + delta));
        if (target == index) return;
        index = target;
        refreshSelection();
        slideTo(index);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) open();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() == 0) closeApp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getRepeatCount() == 0) clearAll();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private LinearLayout dots;
    private TextView hint;
    private long clearArmed;
    private final Runnable hideHint = () -> {
        if (hint != null) hint.setVisibility(View.INVISIBLE);
    };

    /** The position dots, and a hint line that shows for the first three openings only. */
    private void buildHud() {
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotsParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotsParams.bottomMargin = px(64);
        stage.addView(dots, dotsParams);
        hint = new TextView(this);
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(18);
        hint.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = px(24);
        stage.addView(hint, hintParams);
        android.content.SharedPreferences ui = getSharedPreferences("recents_ui", MODE_PRIVATE);
        int shown = ui.getInt("hint", 0);
        if (shown < 3) {
            ui.edit().putInt("hint", shown + 1).apply();
            note("Left / Right browse  -  OK open  -  Up close app  -  Down twice close all", 5000);
        }
    }

    private void note(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.removeCallbacks(hideHint);
        hint.postDelayed(hideHint, ms);
    }

    /** The chosen card gets a bright border and its dot turns into a white bar. */
    private void refreshSelection() {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).ring.setStroke(px(i == index ? 4 : 1), i == index ? 0xCCFFFFFF : 0x33FFFFFF);
        }
        if (dots == null) return;
        dots.removeAllViews();
        int n = Math.min(entries.size(), 12);
        for (int i = 0; i < n; i++) {
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(px(4));
            shape.setColor(i == index ? 0xFFFFFFFF : 0x59FFFFFF);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(i == index ? 22 : 8), px(8));
            lp.leftMargin = px(4);
            lp.rightMargin = px(4);
            dots.addView(dot, lp);
        }
    }

    /** Down once asks, Down again within 2.5 seconds closes every app in the list. */
    private void clearAll() {
        if (entries.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now > clearArmed) {
            clearArmed = now + 2500;
            note("Press Down again to close all apps", 2500);
            return;
        }
        clearArmed = 0;
        ActivityManager am = getSystemService(ActivityManager.class);
        for (Entry e : entries) {
            try {
                if (am != null) am.killBackgroundProcesses(e.pkg);
            } catch (RuntimeException ignored) {
            }
            RecentApps.remove(this, e.pkg);
            e.item.animate().translationYBy(-px(260)).alpha(0f).setDuration(220).start();
        }
        entries.clear();
        stage.postDelayed(this::leave, 260);
    }

    private void open() {
        if (entries.isEmpty()) return;
        Entry e = entries.get(index);
        Bundle options = ActivityOptions.makeScaleUpAnimation(e.card, 0, 0, e.card.getWidth(), e.card.getHeight()).toBundle();
        try {
            startActivity(new Intent(e.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
        } catch (RuntimeException ignored) {
            // the app is gone or refuses to start
        }
        leave();
    }

    private void closeApp() {
        if (entries.isEmpty()) return;
        final Entry e = entries.remove(index);
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am != null) am.killBackgroundProcesses(e.pkg);
        } catch (RuntimeException ignored) {
        }
        RecentApps.remove(this, e.pkg);
        e.item.animate().translationYBy(-px(260)).alpha(0f).setDuration(220)
                .withEndAction(() -> stage.removeView(e.item)).start();
        if (entries.isEmpty()) {
            stage.postDelayed(this::leave, 240);
            return;
        }
        if (index >= entries.size()) {
            index = entries.size() - 1;
        } else {
            pos = Math.max(0f, index - 0.6f);
        }
        refreshSelection();
        place();
        slideTo(index);
    }
}
