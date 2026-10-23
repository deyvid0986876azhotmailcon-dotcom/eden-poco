package dev.poco.asistente.core;

/** Per-call gate. Only server transcriptions (not model function arguments) enter this class. */
public final class VoiceConfirmation {
    public final String actor = "voz:" + java.util.UUID.randomUUID();
    private String code, description, output = "", input = "";
    private boolean spoken, premature;
    public synchronized void propose(String code, String description) {
        cancel(); this.code = code; this.description = normalize(description);
    }
    public synchronized void output(String fragment) {
        if (code != null) output = Memory.clip(output + fragment, 16000);
    }
    public synchronized void outputComplete() {
        // Full literal description must occur contiguously; never trust a model's "confirmed" flag.
        if (code != null && normalize(output).contains(description)) spoken = true;
    }
    public synchronized void interrupted() { spoken = false; output = ""; input = ""; premature = false; }
    public synchronized String input(String fragment, boolean finished, boolean playbackDrained) {
        if (code == null) return null;
        if (!spoken || !playbackDrained) premature = true;
        input = Memory.clip(input + fragment, 4000);
        if (!finished) return null;
        String answer = normalize(input); input = "";
        boolean accepted = !premature && spoken && answer.equals("si confirmo");
        premature = false;
        if (!accepted) return null;
        String result = code; cancel(); return result;
    }
    public synchronized void cancel() { code = null; description = null; output = ""; input = ""; spoken = false; premature = false; }
    private static String normalize(String text) { return Rules.normalize(text).replaceAll("[\\p{Punct}¿¡«»]", " ").replaceAll("\\s+", " ").trim(); }
}
