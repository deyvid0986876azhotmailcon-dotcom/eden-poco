package dev.poco.asistente.ui;

import android.content.Context;
import android.content.res.Configuration;

/** Colors of the app (logs/delegado/diseno_asistente.md), dark or light after the phone. */
public final class Palette {
    public final boolean dark;
    public final int brand, background, surface, text, muted, userBubble, assistantBubble, positive, negative, onBrand, divider;

    private Palette(boolean dark) {
        this.dark = dark;
        brand = dark ? 0xFF5B8BD6 : 0xFF335C97;
        background = dark ? 0xFF121418 : 0xFFF8F9FA;
        surface = dark ? 0xFF1E222B : 0xFFFFFFFF;
        text = dark ? 0xFFE1E4E8 : 0xFF212529;
        muted = dark ? 0xFF8B949E : 0xFF6C757D;
        userBubble = dark ? 0xFF263859 : 0xFFD6E4FF;
        assistantBubble = dark ? 0xFF1E222B : 0xFFE9ECEF;
        positive = dark ? 0xFF3FB950 : 0xFF1E7E34;
        negative = dark ? 0xFFF85149 : 0xFFC82333;
        onBrand = 0xFFFFFFFF;
        divider = dark ? 0xFF2D333B : 0xFFDEE2E6;
    }

    public static Palette of(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Palette(mode == Configuration.UI_MODE_NIGHT_YES);
    }
}
