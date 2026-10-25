package com.kozzzak.simplefirewall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared design tokens: one palette, radius and type scale for every screen. */
final class Ui {
    private Ui() {}
    static final int BG = 0xff0e100f, SURFACE = 0xff161917, CARD = 0xff1c201d, CARD_HI = 0xff242924,
        STROKE = 0x1affffff, WHITE = 0xfff4f7ef, MUTED = 0xff9aa596, FAINT = 0xff5d665a,
        ACCENT = 0xffd4ff68, ACCENT_SOFT = 0x26d4ff68, ON_ACCENT = 0xff101211,
        DOWN = 0xffd4ff68, UP = 0xff7cc4ff, DANGER = 0xffff8f7a, DANGER_SOFT = 0x26ff8f7a;
    static final int R_CARD = 20, R_CONTROL = 14, R_PILL = 999;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d;
    }
    static GradientDrawable outlined(Context c, int color, int stroke, int radius) {
        GradientDrawable d = round(c, color, radius); d.setStroke(dp(c, 1), stroke); return d;
    }
    /** Touch feedback over any shape; the mask keeps ripples inside rounded corners. */
    static Drawable ripple(Context c, Drawable content, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33ffffff), content, round(c, 0xffffffff, radius));
    }
    static TextView text(Context c, String value, float size, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false); return v;
    }
    static TextView bold(Context c, String value, float size, int color) {
        TextView v = text(c, value, size, color); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); return v;
    }
    static TextView label(Context c, String value) {
        TextView v = text(c, value.toUpperCase(java.util.Locale.ROOT), 11, MUTED); v.setLetterSpacing(.12f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setGravity(Gravity.CENTER_VERTICAL); return v; }

    static TextView button(Context c, String value, boolean primary) {
        TextView b = bold(c, value, 15, primary ? ON_ACCENT : WHITE); b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, primary ? round(c, ACCENT, R_CONTROL) : outlined(c, CARD, STROKE, R_CONTROL), R_CONTROL));
        b.setClickable(true); b.setFocusable(true); return b;
    }
    static void chip(Context c, TextView chip, boolean selected) {
        chip.setTextColor(selected ? ON_ACCENT : WHITE);
        chip.setBackground(ripple(c, selected ? round(c, ACCENT, R_PILL) : outlined(c, CARD, STROKE, R_PILL), R_PILL));
        chip.setSelected(selected);
    }
    static Drawable icon(Context c, int res, int sizeDp, int tint) {
        Drawable d = c.getDrawable(res).mutate(); d.setTint(tint); d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp)); return d;
    }
    static LinearLayout.LayoutParams margins(int w, int h, Context c, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = dp(c, top); return p;
    }
    static void fadeIn(View v) { v.setAlpha(0f); v.setTranslationY(dp(v.getContext(), 8)); v.animate().alpha(1f).translationY(0).setDuration(180).start(); }
}
