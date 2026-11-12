package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
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

    /** false: every app shows its high-resolution icon instead of its (often small) banner. */
    private static final boolean USE_BANNERS = true;

    private static final class Entry {
        String pkg;
        Intent launch;
        View item;
        View name;
        View card;
        int tint;
        GradientDrawable ring;
        String label;
        TextView pinChip;
        TextView runChip;
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
    private int topY;
    private boolean dialogUp;
    private boolean hintDone;
    private TextView memView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showing = true;
        overridePendingTransition(0, 0);
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
        topY = Math.round((screenH - cardH - headH) / 2f);
        fill(topY);
        stage.animate().alpha(1f).setDuration(180).withLayer().start();
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
        overridePendingTransition(0, 0);
    }

    private void fill(int top) {
        PackageManager pm = getPackageManager();
        List<String> recent = new ArrayList<>(RecentApps.list(this));
        List<String> pinned = pins();
        List<String> order = new ArrayList<>(pinned);
        for (String pkg : recent) {
            if (!order.contains(pkg)) order.add(pkg);
        }
        for (String pkg : order) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                if (pinned.contains(pkg)) setPinned(pkg, false);
                continue;
            }
            try {
                addEntry(pm, pkg, launch, top);
            } catch (PackageManager.NameNotFoundException gone) {
                RecentApps.remove(this, pkg);
                setPinned(pkg, false);
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
        index = 0;
        for (String pkg : recent) {
            int at = indexOfPkg(pkg);
            if (at < 0) continue;
            if (pkg.equals(RecentApps.front) && entries.size() > 1) continue;
            index = at;
            break;
        }
        pos = index;
        place();
        bg = darken(entries.get(index).tint);
        stage.setBackgroundColor(bg);
        refreshMarks(runningPackages());
        buildHud();
        refreshSelection();
    }

    /** The app's icon at the highest density it ships, so it stays sharp on a big card. */
    private Drawable hiResIcon(PackageManager pm, String pkg, Drawable fallback) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                Drawable d = pm.getResourcesForApplication(ai)
                        .getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return fallback;
    }

    private void addEntry(PackageManager pm, String pkg, Intent launch, int top)
            throws PackageManager.NameNotFoundException {
        CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        Drawable icon = pm.getApplicationIcon(pkg);
        Drawable art = pm.getActivityBanner(launch);
        if (art == null) art = pm.getApplicationBanner(pkg);
        final boolean banner = USE_BANNERS && art != null;
        if (!banner) art = icon;

        FrameLayout item = new FrameLayout(this) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
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
        TextView pinChip = chip("PINNED", 0xFFFFC857, 0xFF3A2A00);
        TextView runChip = chip("RUNNING", 0xFF4CD08C, 0xFF00331C);
        head.addView(pinChip);
        head.addView(runChip);
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
        FrameLayout.LayoutParams logoParams;
        if (banner) {
            // A banner is a small bitmap: show it at most 1.5 times its real size so it stays sharp.
            logo.setImageDrawable(art);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int srcW = art instanceof BitmapDrawable ? ((BitmapDrawable) art).getBitmap().getWidth() : 0;
            int logoW = Math.round(cardW * 0.50f);
            if (srcW > 0) logoW = Math.min(logoW, Math.round(srcW * 1.5f));
            logoParams = new FrameLayout.LayoutParams(logoW, Math.round(logoW * 9f / 16f), Gravity.CENTER);
            logo.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(12));
                }
            });
            logo.setClipToOutline(true);
        } else {
            logo.setImageDrawable(hiResIcon(pm, pkg, art));
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int logoW = Math.round(cardH * 0.42f);
            logoParams = new FrameLayout.LayoutParams(logoW, logoW, Gravity.CENTER);
        }
        card.addView(logo, logoParams);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(22));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(px(3), 0x1AFFFFFF);
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
        e.label = String.valueOf(label);
        e.launch = launch;
        e.item = item;
        e.name = name;
        e.card = card;
        e.tint = tint;
        e.ring = ring;
        e.pinChip = pinChip;
        e.runChip = runChip;
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
        }
    }

    private void slideTo(float target) {
        if (!entries.isEmpty()) {
            tintBackground(entries.get(Math.max(0, Math.min(entries.size() - 1, Math.round(target)))).tint);
        }
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(pos, target);
        slide.setDuration(380);
        slide.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        layers(true);
        slide.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            place();
            if (a.getAnimatedFraction() >= 1f) layers(false);
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
                if (event.getRepeatCount() == 0) holdKey(keyCode, 600);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() == 0) closeApp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getRepeatCount() == 0) holdKey(keyCode, 700);
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

    /** The position dots, the memory figure and a hint line that shows for the first three openings only. */
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
        memView = new TextView(this);
        memView.setTextColor(0x99FFFFFF);
        memView.setTextSize(16);
        FrameLayout.LayoutParams memParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        memParams.topMargin = px(36);
        memParams.rightMargin = px(48);
        stage.addView(memView, memParams);
        updateMemory();
        if (hintDone) return;
        hintDone = true;
        android.content.SharedPreferences ui = getSharedPreferences("recents_ui", MODE_PRIVATE);
        int shown = ui.getInt("hint", 0);
        if (shown < 3) {
            ui.edit().putInt("hint", shown + 1).apply();
            note("Left / Right browse  -  OK open  -  hold OK info  -  Up close app  -  Down twice close all  -  hold Down settings", 6000);
        }
    }

    private void note(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.removeCallbacks(hideHint);
        hint.postDelayed(hideHint, ms);
    }

    private final List<View> dotViews = new ArrayList<>();
    private final List<GradientDrawable> dotShapes = new ArrayList<>();

    /** While cards slide they are GPU layers, so moving them costs nothing but compositing. */
    private void layers(boolean on) {
        for (Entry e : entries) {
            e.item.setLayerType(on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        }
    }

    /** The chosen card gets a bright border and its name; the dots are reused, not rebuilt. */
    private void refreshSelection() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            e.ring.setStroke(px(3), i == index ? 0xCCFFFFFF : 0x1AFFFFFF);
            e.name.animate().alpha(i == index ? 1f : 0f).setDuration(150).start();
            e.pinChip.animate().alpha(i == index ? 1f : 0f).setDuration(150).start();
            e.runChip.animate().alpha(i == index ? 1f : 0f).setDuration(150).start();
        }
        if (dots == null) return;
        int n = Math.min(entries.size(), 12);
        while (dotViews.size() > n) {
            dots.removeView(dotViews.remove(dotViews.size() - 1));
            dotShapes.remove(dotShapes.size() - 1);
        }
        while (dotViews.size() < n) {
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            dot.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(8), px(8));
            lp.leftMargin = px(5);
            lp.rightMargin = px(5);
            dots.addView(dot, lp);
            dotViews.add(dot);
            dotShapes.add(shape);
        }
        for (int i = 0; i < n; i++) {
            float scale = i == index ? 1.6f : 1f;
            dotShapes.get(i).setColor(i == index ? 0xFFFFFFFF : 0x59FFFFFF);
            dotViews.get(i).animate().scaleX(scale).scaleY(scale).setDuration(150).start();
        }
    }

    private ActivityManager.MemoryInfo memoryInfo() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am != null) am.getMemoryInfo(mi);
        return mi;
    }

    private void updateMemory() {
        if (memView == null) return;
        ActivityManager.MemoryInfo mi = memoryInfo();
        memView.setText((mi.availMem >> 20) + " MB free of " + (mi.totalMem >> 20) + " MB");
    }

    /** Down once asks, Down again within 2.5 seconds closes every app and reports what it freed. */
    private void clearAll() {
        if (entries.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now > clearArmed) {
            clearArmed = now + 2500;
            note("Press Down again to close all apps", 2500);
            return;
        }
        clearArmed = 0;
        final long before = memoryInfo().availMem;
        final int count = entries.size();
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
        refreshSelection();
        stage.postDelayed(() -> {
            updateMemory();
            long gained = Math.max(0L, (memoryInfo().availMem - before) >> 20);
            note("Closed " + count + (count == 1 ? " app" : " apps") + "  -  about " + gained + " MB freed", 2600);
        }, 900);
        stage.postDelayed(this::leave, 3000);
    }

    // ----- pins -----
    private List<String> pins() {
        String raw = getSharedPreferences("recents_ui", MODE_PRIVATE).getString("pins", "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private void setPinned(String pkg, boolean on) {
        List<String> l = pins();
        l.remove(pkg);
        if (on) l.add(pkg);
        getSharedPreferences("recents_ui", MODE_PRIVATE).edit().putString("pins", android.text.TextUtils.join(",", l)).apply();
    }

    private int indexOfPkg(String pkg) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    private TextView chip(String text, int bgColor, int fgColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fgColor);
        t.setTextSize(12);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(px(8), px(2), px(8), px(2));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(px(10));
        shape.setColor(bgColor);
        t.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(10);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        return t;
    }

    private void refreshMarks(java.util.Set<String> live) {
        List<String> pinned = pins();
        for (Entry e : entries) {
            e.pinChip.setVisibility(pinned.contains(e.pkg) ? View.VISIBLE : View.GONE);
            e.runChip.setVisibility(live != null && live.contains(e.pkg) ? View.VISIBLE : View.GONE);
        }
    }

    /** Packages with a process in memory, or null when this firmware hides other apps' processes. */
    private java.util.Set<String> runningPackages() {
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> procs = am == null ? null : am.getRunningAppProcesses();
            if (procs == null) return null;
            java.util.Set<String> live = new java.util.HashSet<>();
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info.pkgList != null) {
                    for (String pkg : info.pkgList) live.add(pkg);
                }
            }
            String front = RecentApps.front;
            // The app in front is certainly running: if it is missing, the list cannot be trusted.
            if (front == null || front.isEmpty() || !live.contains(front)) return null;
            return live;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Builds the cards again (after a pin changes), keeping the same app selected. */
    private void rebuild(String keep) {
        stage.removeAllViews();
        entries.clear();
        dotViews.clear();
        dotShapes.clear();
        dots = null;
        hint = null;
        memView = null;
        fill(topY);
        if (entries.isEmpty()) return;
        int at = indexOfPkg(keep);
        if (at >= 0) {
            index = at;
            pos = at;
        }
        place();
        refreshSelection();
        bg = darken(entries.get(index).tint);
        stage.setBackgroundColor(bg);
    }

    // ----- hold OK for app info, hold Down for settings (own timer: some firmware drops key repeats) -----
    private int heldKey;
    private boolean heldFired;
    private final Runnable heldRun = () -> {
        heldFired = true;
        if (heldKey == KeyEvent.KEYCODE_DPAD_DOWN) showSettings();
        else showInfo();
    };

    private void holdKey(int code, long ms) {
        heldKey = code;
        heldFired = false;
        stage.removeCallbacks(heldRun);
        stage.postDelayed(heldRun, ms);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (heldKey != 0 && keyCode == heldKey) {
            stage.removeCallbacks(heldRun);
            boolean fired = heldFired;
            heldKey = 0;
            heldFired = false;
            if (!fired && !event.isCanceled()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) clearAll();
                else open();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showInfo() {
        if (entries.isEmpty() || dialogUp) return;
        final Entry e = entries.get(index);
        final boolean pinned = pins().contains(e.pkg);
        final String[] items = {pinned ? "Remove pin" : "Pin to front", "Close app (free memory)",
                "Force stop / app info", "Uninstall"};
        dialogUp = true;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            setPinned(e.pkg, !pinned);
                            rebuild(e.pkg);
                            break;
                        case 1:
                            closeApp();
                            break;
                        case 2:
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                leave();
                            } catch (RuntimeException unavailable) {
                                note("App info is not available on this TV", 2500);
                            }
                            break;
                        default:
                            try {
                                ApplicationInfo ai = getPackageManager().getApplicationInfo(e.pkg, 0);
                                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                                    note("System apps cannot be uninstalled", 2500);
                                    break;
                                }
                                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                leave();
                            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                                note("Uninstall is not available on this TV", 2500);
                            }
                    }
                })
                .setOnDismissListener(d -> dialogUp = false)
                .show();
    }

    private void showSettings() {
        if (dialogUp) return;
        final android.content.SharedPreferences ui = getSharedPreferences("recents_ui", MODE_PRIVATE);
        final int[] values = {300, 400, 500, 700, 1000, 1500};
        int def = getResources().getInteger(R.integer.app_switcher_hold_ms);
        int current = ui.getInt("hold_ms", 0);
        if (current <= 0) current = def;
        final String[] items = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            items[i] = String.format(java.util.Locale.US, "%.1f seconds", values[i] / 1000f)
                    + (values[i] == def ? "  (default)" : "");
            if (Math.abs(values[i] - current) < Math.abs(values[checked] - current)) checked = i;
        }
        final String keep = entries.isEmpty() ? "" : entries.get(index).pkg;
        dialogUp = true;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hold Back to open the switcher")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    ui.edit().putInt("hold_ms", values[which]).apply();
                    d.dismiss();
                    note("Back hold time: " + items[which].trim(), 2500);
                })
                .setNeutralButton("Clear pins", (d, which) -> {
                    ui.edit().remove("pins").apply();
                    rebuild(keep);
                })
                .setOnDismissListener(d -> dialogUp = false)
                .show();
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
