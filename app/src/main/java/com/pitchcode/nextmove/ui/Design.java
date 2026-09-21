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
    // Palette tokens. These are mutable so the app can switch between light,
    // dark and high-contrast themes at runtime (the activity is recreated).
    public static int INK = Color.rgb(24, 35, 31);
    public static int PAPER = Color.rgb(247, 244, 236);
    public static int CARD = Color.WHITE;
    public static int SAFFRON = Color.rgb(245, 165, 36);
    public static int MINT = Color.rgb(218, 235, 224);
    public static int MINT_STRONG = Color.rgb(198, 226, 208);
    public static int SOFT = Color.rgb(235, 231, 220);
    public static int MUTED = Color.rgb(101, 111, 106);
    public static int DANGER = Color.rgb(170, 57, 45);
    public static int DANGER_SOFT = Color.rgb(251, 232, 227);
    public static int CREAM = Color.rgb(255, 241, 207);
    public static int CREAM_SOFT = Color.rgb(255, 247, 225);
    public static int AD_BG = Color.rgb(243, 240, 232);
    // Text drawn on top of an INK-coloured surface.
    public static int ON_INK = Color.WHITE;
    public static int ON_INK_MUTED = Color.rgb(204, 215, 210);

    // Global font scale for the accessibility "bigger text" setting.
    public static float FONT_SCALE = 1f;

    private Design() {}

    /** Applies the palette and text scale for the chosen theme. */
    public static void apply(boolean dark, boolean highContrast, float fontScale) {
        FONT_SCALE = fontScale;
        SAFFRON = Color.rgb(245, 165, 36);
        if (dark) {
            PAPER = Color.rgb(18, 20, 22);
            CARD = Color.rgb(30, 35, 38);
            INK = highContrast ? Color.WHITE : Color.rgb(235, 238, 235);
            MUTED = highContrast ? Color.rgb(200, 208, 204) : Color.rgb(150, 160, 156);
            SOFT = highContrast ? Color.rgb(150, 160, 156) : Color.rgb(58, 64, 66);
            MINT = Color.rgb(28, 46, 38);
            MINT_STRONG = Color.rgb(40, 66, 54);
            DANGER = Color.rgb(240, 128, 116);
            DANGER_SOFT = Color.rgb(58, 34, 32);
            CREAM = Color.rgb(58, 50, 28);
            CREAM_SOFT = Color.rgb(48, 44, 30);
            AD_BG = Color.rgb(36, 40, 42);
            ON_INK = Color.rgb(18, 20, 22);
            ON_INK_MUTED = Color.rgb(90, 100, 96);
        } else {
            PAPER = highContrast ? Color.WHITE : Color.rgb(247, 244, 236);
            CARD = Color.WHITE;
            INK = highContrast ? Color.rgb(0, 0, 0) : Color.rgb(24, 35, 31);
            MUTED = highContrast ? Color.rgb(66, 74, 70) : Color.rgb(101, 111, 106);
            SOFT = highContrast ? Color.rgb(120, 126, 116) : Color.rgb(235, 231, 220);
            MINT = Color.rgb(218, 235, 224);
            MINT_STRONG = Color.rgb(198, 226, 208);
            DANGER = highContrast ? Color.rgb(150, 40, 30) : Color.rgb(170, 57, 45);
            DANGER_SOFT = Color.rgb(251, 232, 227);
            CREAM = Color.rgb(255, 241, 207);
            CREAM_SOFT = Color.rgb(255, 247, 225);
            AD_BG = Color.rgb(243, 240, 232);
            ON_INK = Color.WHITE;
            ON_INK_MUTED = Color.rgb(204, 215, 210);
        }
    }

    /** Scales a base sp value by the current accessibility font scale. */
    public static float scaled(float sp) {
        return sp * FONT_SCALE;
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context context, CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * FONT_SCALE);
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
        chip.setBackground(outlined(active ? blendActive() : CARD,
                active ? SAFFRON : SOFT, 16, context));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    // Active chip fill: a soft saffron tint that works on both themes.
    private static int blendActive() {
        return Color.argb(40,
                Color.red(SAFFRON), Color.green(SAFFRON), Color.blue(SAFFRON));
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
