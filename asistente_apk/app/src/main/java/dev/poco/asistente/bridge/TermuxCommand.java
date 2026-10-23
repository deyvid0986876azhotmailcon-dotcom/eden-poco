package dev.poco.asistente.bridge;

/** Exact RUN_COMMAND contract, kept Android-free for JVM tests. */
public final class TermuxCommand {
    private TermuxCommand() { }
    public static final String ACTION = "com.termux.RUN_COMMAND";
    public static final String PACKAGE = "com.termux";
    public static final String SERVICE = "com.termux.app.RunCommandService";
    public static final String PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static java.util.Map<String, Object> extras() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put(PATH, "/data/data/com.termux/files/usr/bin/bash");
        result.put(ARGUMENTS, new String[]{"-c", "pgrep -f 'node index.cjs' >/dev/null || setsid nohup bash $HOME/.termux/boot/asistente.sh >/dev/null 2>&1 </dev/null &"});
        result.put(BACKGROUND, true);
        return result;
    }
}
