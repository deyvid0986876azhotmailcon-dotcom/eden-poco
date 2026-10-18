package dev.poco.asistente.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The pieces the screens are built with, measures from logs/delegado/diseno_asistente.md. */
public final class Views {
    private final Context context;
    public final Palette palette;

    public Views(Context context) {
        this.context = context;
        this.palette = Palette.of(context);
    }

    public int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    public TextView text(CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** One Font Awesome icon; brands=true for the WhatsApp logo. */
    public TextView icon(String glyph, float sp, int color, boolean brands) {
        TextView view = text(glyph, sp, color, false);
        view.setTypeface(brands ? Icons.brands(context) : Icons.solid(context));
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** A card: surface color, 8dp corners, 16dp inside, 12dp below. */
    public LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(palette.surface, 8));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    /** Icon and title in a row, the head of a card or a section of Ajustes. */
    public LinearLayout cardTitle(String glyph, String title, boolean brands) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = icon(glyph, 16, palette.brand, brands);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = text(title, 16, palette.text, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(8);
        row.addView(label, params);
        row.setPadding(0, 0, 0, dp(8));
        return row;
    }

    /**
     * A chat message: the user's on the right in the brand-tinted bubble, the assistant's on the left;
     * 16dp corners with the one next to the sender at 4dp, at most 80% wide, the time below.
     */
    public View bubble(CharSequence message, String time, boolean fromUser, int parentWidthPx) {
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(6));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fromUser ? palette.userBubble : palette.assistantBubble);
        float big = dp(16), small = dp(4);
        // top-left, top-right, bottom-right, bottom-left (x and y radius each)
        shape.setCornerRadii(fromUser
                ? new float[] {big, big, big, big, small, small, big, big}
                : new float[] {big, big, big, big, big, big, small, small});
        bubble.setBackground(shape);

        TextView body = text(message, 15, palette.text, false);
        body.setTextIsSelectable(true);
        if (parentWidthPx > 0) body.setMaxWidth(parentWidthPx * 8 / 10 - dp(24));
        bubble.addView(body);
        if (time != null && !time.isEmpty()) {
            TextView stamp = text(time, 11, palette.muted, false);
            stamp.setGravity(Gravity.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(2);
            bubble.addView(stamp, params);
        }

        FrameLayout row = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                fromUser ? Gravity.END : Gravity.START);
        row.addView(bubble, params);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    /** One tab of the bottom bar: icon over label, brand color when active. */
    public LinearLayout tab(String glyph, String label, boolean active, View.OnClickListener onClick) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        int color = active ? palette.brand : palette.muted;
        tab.addView(icon(glyph, 18, color, false));
        TextView name = text(label, 11, color, active);
        name.setGravity(Gravity.CENTER);
        tab.addView(name);
        tab.setOnClickListener(onClick);
        tab.setContentDescription(label);
        return tab;
    }

    /** A round 48dp button with one icon, for Enviar. */
    public TextView roundButton(String glyph, View.OnClickListener onClick, String description) {
        TextView button = icon(glyph, 18, palette.onBrand, false);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(palette.brand);
        button.setBackground(circle);
        button.setOnClickListener(onClick);
        button.setContentDescription(description);
        return button;
    }

    /** A horizontal bar for spending by category: fraction 0..1 of the widest. */
    public View bar(float fraction, int color) {
        FrameLayout track = new FrameLayout(context);
        track.setBackground(rounded(palette.divider, 4));
        View fill = new View(context);
        fill.setBackground(rounded(color, 4));
        track.addView(fill, new FrameLayout.LayoutParams(0, dp(8)));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.round((r - l) * Math.max(0f, Math.min(1f, fraction)));
            ViewGroup.LayoutParams p = fill.getLayoutParams();
            if (p.width != width) { p.width = width; fill.setLayoutParams(p); }
        });
        return track;
    }
}
