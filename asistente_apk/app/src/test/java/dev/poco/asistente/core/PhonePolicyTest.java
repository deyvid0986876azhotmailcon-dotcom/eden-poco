package dev.poco.asistente.core;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhonePolicyTest {
    @Test public void explicitPackagesAndFuturePatternsAreBlocked() {
        for (String pkg : new String[]{"com.google.android.apps.walletnfcrel", "com.google.android.apps.authenticator2",
                "com.juvo.tigomoney", "com.mangopayment.mangoapp", "net.mastarjeta.app", "py.com.eko",
                "py.com.elcomercio.retailbanking", "py.com.familiar.app", "py.com.itau.nmb.android", "py.com.itti.nueva",
                "com.eycon_py_app_usuario_jaha", "com.genexus.TSIMovil", "com.miui.virtualsim", "com.anydesk.remote",
                "com.teamviewer.host", "com.google.chromeremotedesktop", "com.zoho.assist", "dev.poco.asistente",
                "new.bank", "new.banco", "new.banking", "new.wallet", "new.billetera", "new.authenticator", "new.2fa",
                "new.otp", "new.password", "new.1password", "new.bitwarden", "new.lastpass", "new.keepass"}) assertTrue(pkg, PhonePolicy.blocked(pkg));
        assertTrue(PhonePolicy.blocked(null)); assertTrue(PhonePolicy.blocked(""));
        for (String pkg : new String[]{"com.spotify.music", "com.google.android.deskclock", "com.whatsapp", "app.morphe.android.apps.youtube.music"}) assertFalse(pkg, PhonePolicy.blocked(pkg));
    }
    @Test public void secretsNeverLeaveTitleOrBodyEvenWithNewlinesOrSeparatedCodes() {
        for (String text : new String[]{"Tu código es 1234", "Code: 12345678", "OTP\n987654", "PIN: 54321",
                "Clave de verificación 123456", "123456\nNo compartas este código", "Don't share this code: 12 34 56",
                "Nunca le digas a nadie: AB9XQ7", "Verification: 123-456"}) {
            String safe = PhonePolicy.redact(text);
            assertFalse(safe, safe.contains("123")); assertFalse(safe, safe.contains("987")); assertFalse(safe, safe.contains("543")); assertFalse(safe, safe.contains("AB9"));
        }
        assertEquals("Nos vemos a las 18:30", PhonePolicy.redact("Nos vemos a las 18:30"));
        assertFalse(PhonePolicy.redact("Tu número es 782345").contains("782345"));
    }
    @Test public void appResolutionNormalizesLabelsButNeverChoosesAnAmbiguousOrBlockedOne() {
        PhonePolicy.App music = new PhonePolicy.App("Música", "com.example.music");
        assertSame(music, PhonePolicy.resolve("  MUSICA ", Collections.singletonList(music)));
        assertSame(music, PhonePolicy.resolve("musica", Arrays.asList(music, music)));
        assertThrows(IllegalArgumentException.class, () -> PhonePolicy.resolve("musica", Arrays.asList(music, new PhonePolicy.App("Música", "another.music"))));
        assertThrows(IllegalArgumentException.class, () -> PhonePolicy.resolve("reloj", Collections.singletonList(music)));
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class, () -> PhonePolicy.resolve("mango", Collections.singletonList(new PhonePolicy.App("Mango", "com.mangopayment.mangoapp"))));
        assertTrue(blocked.getMessage().contains("no la puedo"));
    }
}
