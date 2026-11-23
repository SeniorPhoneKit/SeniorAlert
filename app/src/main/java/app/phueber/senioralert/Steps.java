package app.phueber.senioralert;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The full instructions for a setup task, only when the helper asks: each
 * setup warning keeps its one short line, and "How? Show the steps" under it
 * opens a big card with numbered steps in the words Android shows, notes for
 * other phones, and the fix itself at the bottom. The same in every Senior
 * app, here in this app's look (white card, pill buttons). Back (on the card,
 * or the system's) closes the card and returns to the alarm list.
 */
final class Steps {
    /** A button at the bottom of the steps: its label and what it opens. */
    static final class Action {
        final int label;
        final Runnable run;

        Action(int label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private static final int TEXT = 20;
    private static final int BUTTON = 22;
    private static final int HEIGHT = 64;

    private Steps() {}

    /**
     * "How? Show the steps", outlined, full width: goes right below the
     * warning. stepsRes and notesRes are string arrays (notesRes 0: none).
     */
    static Button button(Activity a, int titleRes, int stepsRes, int notesRes, Action main) {
        Button b = pill(a, a.getString(R.string.steps_button), a.getColor(R.color.card),
                a.getColor(R.color.text_black), a.getColor(R.color.outline), R.color.press_dark);
        b.setOnClickListener(v -> show(a, titleRes, stepsRes, notesRes, main));
        return b;
    }

    static Dialog show(Activity a, int titleRes, int stepsRes, int notesRes, Action main) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 24);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.card));
        bg.setCornerRadius(dp(a, 24));
        bg.setStroke(dp(a, 2), a.getColor(R.color.card_border));
        box.setBackground(bg);

        TextView title = text(a, a.getString(titleRes), 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        box.addView(title);

        // The steps scroll; title and buttons stay in view.
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        String[] steps = a.getResources().getStringArray(stepsRes);
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(a);
            TextView n = text(a, (i + 1) + ".", TEXT);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setMinWidth(dp(a, 36));
            n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            TextView t = text(a, steps[i], TEXT);
            t.setContentDescription((i + 1) + ". " + steps[i]);
            row.addView(n);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            list.addView(row, full(a, i == 0 ? 0 : 14));
        }
        if (notesRes != 0) {
            int gap = 20;
            for (String note : a.getResources().getStringArray(notesRes)) {
                TextView t = text(a, note, TEXT);
                t.setTextColor(a.getColor(R.color.text_muted));
                list.addView(t, full(a, gap));
                gap = 10;
            }
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(a, 16);
        box.addView(scroll, slp);

        Button go = pill(a, a.getString(main.label), a.getColor(R.color.on_green), Color.WHITE, 0,
                R.color.press_light);
        go.setOnClickListener(v -> { d.dismiss(); main.run.run(); });
        box.addView(go, full(a, 20));
        box.addView(back(a, v -> d.dismiss()), full(a, 12));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(a.getResources().getDisplayMetrics().widthPixels - dp(a, 32),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    /** "← Back", like the alarm list's pinned Back, but it only closes the card. */
    private static View back(Context c, View.OnClickListener tap) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(c, HEIGHT));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setBackground(ripple(c, R.color.press_dark, c.getColor(R.color.card),
                c.getColor(R.color.outline), HEIGHT / 2));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(c.getString(R.string.back));
        b.setOnClickListener(tap);
        TextView label = text(c, c.getString(R.string.back), BUTTON);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_arrow_back);
        arrow.setImageTintList(ColorStateList.valueOf(c.getColor(R.color.text_black)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(label.getTextSize() * 1.2f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(size, size);
        alp.setMarginEnd(dp(c, 10));
        b.addView(arrow, alp);
        b.addView(label);
        return b;
    }

    private static Button pill(Context c, String label, int bg, int fg, int stroke, int pressRes) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON);
        b.setMinHeight(dp(c, HEIGHT));
        b.setMinimumHeight(dp(c, HEIGHT));
        b.setStateListAnimator(null);
        b.setBackground(ripple(c, pressRes, bg, stroke, HEIGHT / 2));
        return b;
    }

    private static RippleDrawable ripple(Context c, int pressRes, int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) shape.setStroke(dp(c, 2), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(c.getColor(pressRes)), shape, mask);
    }

    private static TextView text(Context c, String s, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(c.getColor(R.color.text_black));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static LinearLayout.LayoutParams full(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    private static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }
}
