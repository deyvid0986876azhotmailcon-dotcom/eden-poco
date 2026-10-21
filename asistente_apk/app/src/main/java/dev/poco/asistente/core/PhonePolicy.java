package dev.poco.asistente.core;

import java.util.*;
import java.util.regex.Pattern;

/** Package policy applies before extracting content or resolving an Intent. No model can override it. */
public final class PhonePolicy {
    private PhonePolicy() {}
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "com.google.android.apps.walletnfcrel", "com.google.android.apps.authenticator2",
            "com.juvo.tigomoney", "com.mangopayment.mangoapp", "net.mastarjeta.app", "py.com.eko",
            "py.com.elcomercio.retailbanking", "py.com.familiar.app", "py.com.itau.nmb.android",
            "com.eycon_py_app_usuario_jaha", "com.genexus.tsimovil", "com.miui.virtualsim",
            "com.google.chromeremotedesktop", "com.zoho.assist", "dev.poco.asistente"));
    private static final String[] PREFIXES = {"py.com.itti", "com.anydesk", "com.teamviewer"};
    private static final String[] TERMS = {"bank", "banco", "wallet", "billetera", "authenticator", "2fa", "otp",
            "password", "bitwarden", "lastpass", "keepass"};
    public static boolean blocked(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(p)) return true;
        for (String prefix : PREFIXES) if (p.equals(prefix) || p.startsWith(prefix + ".")) return true;
        for (String term : TERMS) if (p.contains(term)) return true;
        return false;
    }
    public static final class App {
        public final String label, pkg;
        public App(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }
    public static App resolve(String name, List<App> apps) {
        String query = Rules.normalize(name);
        List<App> exact = new ArrayList<>(), partial = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (App app : apps) {
            if (!seen.add(app.pkg)) continue;
            String label = Rules.normalize(app.label);
            if (label.equals(query)) exact.add(app);
            else if (label.contains(query)) partial.add(app);
        }
        List<App> found = exact.isEmpty() ? partial : exact;
        if (found.isEmpty()) throw new IllegalArgumentException("No encontré esa app. Decime el nombre que ves en el teléfono.");
        // Never select a permitted partial match while silently ignoring a blocked candidate.
        for (App app : found) if (blocked(app.pkg)) throw new IllegalArgumentException("Esa no la puedo abrir ni leer.");
        if (found.size() > 1) {
            Set<String> labels = new LinkedHashSet<>();
            for (App app : found) labels.add(app.label);
            throw new IllegalArgumentException("Encontré varias: " + String.join(", ", labels) + ". ¿Cuál? Repetí el pedido con el nombre completo. Si se llaman igual, abrila vos.");
        }
        return found.get(0);
    }
    // android.media.session.PlaybackState values, here so the choice is testable on the JVM
    public static final int PAUSED = 2, PLAYING = 3, BUFFERING = 6;
    /**
     * Which player a media key goes to, from the permitted sessions in Android's order (most recent first):
     * pause/next/previous go to the one that is playing; play goes to the most recent paused one.
     * Players left open but stopped (YouTube after a video) do not count.
     */
    public static int player(int[] states, String action) {
        int paused = -1;
        for (int i = 0; i < states.length; i++) {
            boolean playing = states[i] == PLAYING || states[i] == BUFFERING;
            if (playing) {
                if (action.equals("reproducir")) throw new IllegalArgumentException("Ya está sonando.");
                return i;
            }
            if (states[i] == PAUSED && paused < 0) paused = i;
        }
        if (action.equals("pausar")) throw new IllegalArgumentException("No hay nada sonando.");
        if (paused < 0) throw new IllegalArgumentException("No encontré un reproductor abierto. Abrí Spotify o YouTube Music y poné algo.");
        return paused;
    }
    private static final Pattern SECRET = Pattern.compile("\\b(codigo|code|otp|verificacion|verification|pin|clave|contrasena|password)\\b");
    private static final Pattern NO_SHARE = Pattern.compile("(no|nunca|jamas|not|never|don't|dont).{0,60}(compart|share|divulg|revel|digas|diga)");
    public static String redact(String text) {
        if (text == null) return "";
        String normalized = Rules.normalize(text);
        // Conservative: suppress the whole notice, including alphanumeric/separated codes and its title.
        if (NO_SHARE.matcher(normalized).find()) return "[Aviso con código privado oculto]";
        if (SECRET.matcher(normalized).find())
            return "[Aviso de seguridad oculto]";
        return text.replaceAll("(?<!\\d)\\d{4,8}(?!\\d)", "[número oculto]");
    }
}
