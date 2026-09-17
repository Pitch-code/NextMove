package com.pitchcode.nextmove.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public final class Design {
    public static final int INK = Color.rgb(24, 35, 31);
    public static final int PAPER = Color.rgb(247, 244, 236);
    public static final int CARD = Color.WHITE;
    public static final int SAFFRON = Color.rgb(245, 165, 36);
    public static final int MINT = Color.rgb(218, 235, 224);
    public static final int SOFT = Color.rgb(235, 231, 220);
    public static final int MUTED = Color.rgb(101, 111, 106);
    public static final int DANGER = Color.rgb(170, 57, 45);
    public static final int DANGER_SOFT = Color.rgb(251, 232, 227);

    private Design() {}

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context context, CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setLineSpacing(0, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static TextView label(Context context, CharSequence value) {
        TextView view = text(context, value, 11, MUTED, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static Space space(Context context, int heightDp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return space;
    }

    public static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(SOFT);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return view;
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(int fill, int stroke, float radiusDp, Context context) {
        GradientDrawable drawable = rounded(fill, radiusDp, context);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    public static void card(View view, int color, float radiusDp, Context context) {
        view.setBackground(rounded(color, radiusDp, context));
        view.setElevation(dp(context, 1.5f));
    }

    public static TextView button(Context context, CharSequence title, int background, int foreground) {
        TextView button = text(context, title, 15, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 54));
        button.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        GradientDrawable content = rounded(background, 17, context);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), content, null);
        button.setBackground(ripple);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static TextView chip(Context context, CharSequence title, boolean active) {
        TextView chip = text(context, title, 13, active ? INK : MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(context, 44));
        chip.setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        chip.setBackground(outlined(active ? Color.rgb(255, 244, 218) : CARD,
                active ? SAFFRON : SOFT, 16, context));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }
}
