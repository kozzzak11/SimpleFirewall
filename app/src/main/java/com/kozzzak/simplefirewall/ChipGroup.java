package com.kozzzak.simplefirewall;

import android.content.Context;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Single-choice row of pill chips. */
final class ChipGroup extends HorizontalScrollView {
    interface Listener { void onSelected(int position); }
    private final LinearLayout strip;
    private int selected;
    private Listener listener;

    ChipGroup(Context c, String... labels) {
        super(c); setHorizontalScrollBarEnabled(false); setOverScrollMode(OVER_SCROLL_NEVER);
        strip = new LinearLayout(c); strip.setGravity(Gravity.CENTER_VERTICAL); addView(strip);
        for (int i = 0; i < labels.length; i++) {
            TextView chip = Ui.bold(c, labels[i], 13, Ui.WHITE); chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0); chip.setClickable(true); chip.setFocusable(true);
            int index = i; chip.setOnClickListener(v -> setSelection(index));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(c, 36));
            if (i > 0) p.leftMargin = Ui.dp(c, 8);
            strip.addView(chip, p);
        }
        paint();
    }
    int getSelectedItemPosition() { return selected; }
    void setSelection(int position) {
        if (position < 0 || position >= strip.getChildCount()) return;
        boolean changed = position != selected; selected = position; paint();
        if (changed && listener != null) listener.onSelected(position);
    }
    void setListener(Listener l) { listener = l; }
    private void paint() {
        for (int i = 0; i < strip.getChildCount(); i++) Ui.chip(getContext(), (TextView) strip.getChildAt(i), i == selected);
    }
}
