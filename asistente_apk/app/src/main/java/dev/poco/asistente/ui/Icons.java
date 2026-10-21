package dev.poco.asistente.ui;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Font Awesome Free 6.5.2 (icons CC BY 4.0, fonts SIL OFL 1.1, https://fontawesome.com/license/free),
 * drawn as text in the colors of the app. Every code below was checked against the fonts in assets/fonts.
 */
public final class Icons {
    // fa-solid-900.ttf
    public static final String COMMENTS = "", CHART_PIE = "", GEAR = "";
    public static final String PAPER_PLANE = "", STOP = "", ELLIPSIS = "", CLOCK = "";
    public static final String WALLET = "", ARROW_UP = "", ARROW_DOWN = "", PIGGY_BANK = "";
    public static final String BULLSEYE = "", WEIGHT_SCALE = "", DUMBBELL = "", UTENSILS = "";
    public static final String KEY = "", MICROCHIP = "", TRASH = "", SHIELD = "", INFO = "";
    public static final String PHONE = "", PHONE_SLASH = "", MICROPHONE = "", MICROPHONE_SLASH = "", VOLUME = "";
    // fa-brands-400.ttf
    public static final String WHATSAPP = "";

    public static final String CREDIT = "Íconos: Font Awesome Free 6.5.2 (CC BY 4.0), fuentes SIL OFL 1.1.";

    private static Typeface solid, brands;

    public static synchronized Typeface solid(Context context) {
        if (solid == null) solid = Typeface.createFromAsset(context.getAssets(), "fonts/fa-solid-900.ttf");
        return solid;
    }

    public static synchronized Typeface brands(Context context) {
        if (brands == null) brands = Typeface.createFromAsset(context.getAssets(), "fonts/fa-brands-400.ttf");
        return brands;
    }

    private Icons() { }
}
