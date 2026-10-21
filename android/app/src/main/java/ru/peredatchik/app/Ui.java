package ru.peredatchik.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Мелкие помощники для разметки в коде. */
final class Ui {
    static int dp(Activity a, int v) { return (int) (v * a.getResources().getDisplayMetrics().density); }

    static TextView text(Activity a, String s, float size, boolean bold) {
        TextView t = new TextView(a); t.setText(s); t.setTextSize(size); t.setTextColor(0xFF212121);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        return t;
    }

    static Button button(Activity a, String s, View.OnClickListener l) {
        Button b = new Button(a); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }

    static LinearLayout col(Activity a) {
        LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8)); return l;
    }

    static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }

    static View card(Activity a, View inner) {
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(Color.WHITE);
        f.setElevation(dp(a, 2));
        f.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
        f.addView(inner);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(a, 6), 0, dp(a, 6));
        f.setLayoutParams(lp);
        return f;
    }

    static Spinner methodSpinner(Activity a, ru.peredatchik.core.Method sel) {
        Spinner s = new Spinner(a);
        String[] items = new String[ru.peredatchik.core.Method.values().length];
        for (int i = 0; i < items.length; i++) items[i] = ru.peredatchik.core.Method.values()[i].title;
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad); s.setSelection(sel.ordinal());
        return s;
    }
}
