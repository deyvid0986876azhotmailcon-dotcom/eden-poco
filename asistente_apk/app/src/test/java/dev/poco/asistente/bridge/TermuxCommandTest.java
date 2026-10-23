package dev.poco.asistente.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandTest {
    @Test public void exactRunCommandContract() {
        assertEquals("com.termux.RUN_COMMAND", TermuxCommand.ACTION);
        assertEquals("com.termux", TermuxCommand.PACKAGE);
        assertEquals("com.termux.app.RunCommandService", TermuxCommand.SERVICE);
        java.util.Map<String, Object> extras = TermuxCommand.extras();
        assertEquals(3, extras.size());
        assertEquals("/data/data/com.termux/files/usr/bin/bash", extras.get("com.termux.RUN_COMMAND_PATH"));
        assertArrayEquals(new String[]{"-c", "pgrep -f 'node index.cjs' >/dev/null || setsid nohup bash $HOME/.termux/boot/asistente.sh >/dev/null 2>&1 </dev/null &"},
                (String[]) extras.get("com.termux.RUN_COMMAND_ARGUMENTS"));
        assertEquals(Boolean.TRUE, extras.get("com.termux.RUN_COMMAND_BACKGROUND"));
    }
}
