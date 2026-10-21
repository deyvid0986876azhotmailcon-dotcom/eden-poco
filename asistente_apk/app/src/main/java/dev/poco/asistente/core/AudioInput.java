package dev.poco.asistente.core;

import java.util.Locale;

/** Binary input lives only for the current request; never stored in conversation or logs. */
public final class AudioInput {
    public static final int MAX_BYTES = 5_000_000, MAX_SECONDS = 180;
    public static final String FALLBACK = "No pude escuchar el audio ahora; mandamelo escrito";
    public final byte[] bytes;
    public final String mime;
    public final double seconds;
    public AudioInput(byte[] bytes, String mime, double seconds) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES
                || !Double.isFinite(seconds) || seconds <= 0 || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("Audio fuera de límites (5 MB y 3 minutos).");
        this.mime = mime(mime); this.bytes = bytes; this.seconds = seconds;
    }
    public static String mime(String value) {
        String type = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!java.util.Arrays.asList("audio/ogg", "audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac", "audio/wav",
                "audio/x-wav", "audio/flac", "audio/webm").contains(type)) throw new IllegalArgumentException("Tipo de audio no permitido.");
        return type;
    }
}
