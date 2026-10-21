package dev.poco.asistente.call;

import java.io.*;
import java.lang.reflect.Constructor;
import java.util.*;
import javax.net.ssl.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveSocketTest {
    private static final class Socket extends SSLSocket {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;
        Socket(byte[] input) { this.input = new ByteArrayInputStream(input); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() { closed = true; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public String[] getEnabledCipherSuites() { return new String[0]; }
        public void setEnabledCipherSuites(String[] values) { }
        public String[] getSupportedProtocols() { return new String[0]; }
        public String[] getEnabledProtocols() { return new String[0]; }
        public void setEnabledProtocols(String[] values) { }
        public SSLSession getSession() { return null; }
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        public void startHandshake() { }
        public void setUseClientMode(boolean value) { }
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean value) { }
        public boolean getNeedClientAuth() { return false; }
        public void setWantClientAuth(boolean value) { }
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean value) { }
        public boolean getEnableSessionCreation() { return true; }
    }
    private LiveSocket client(Socket socket) throws Exception {
        Constructor<LiveSocket> constructor = LiveSocket.class.getDeclaredConstructor(SSLSocket.class);
        constructor.setAccessible(true);
        return constructor.newInstance(socket);
    }
    private List<String> read(Socket socket) throws Exception {
        List<String> messages = new ArrayList<>();
        client(socket).readLoop(new LiveSocket.Listener() {
            public void onText(String text) { messages.add(text); }
            public void onClosed(String reason) { }
        });
        assertTrue(socket.closed);
        return messages;
    }
    @Test public void fragmentedTextAllowsInterleavedPingAndEchoesClose() throws Exception {
        Socket socket = new Socket(new byte[]{1, 1, 'a', (byte) 137, 1, 'p', (byte) 128, 1, 'b', (byte) 136, 2, 3, (byte) 232});
        assertEquals(Collections.singletonList("ab"), read(socket));
        byte[] frames = socket.output.toByteArray();
        assertEquals(138, frames[0] & 255); assertEquals(129, frames[1] & 255);
        assertEquals('p', (frames[6] ^ frames[2]) & 255);
        assertEquals(136, frames[7] & 255); assertEquals(130, frames[8] & 255);
        assertEquals(3, (frames[13] ^ frames[9]) & 255);
        assertEquals(232, (frames[14] ^ frames[10]) & 255);
    }
    @Test public void rejectsMalformedFramesBeforeDelivery() throws Exception {
        byte[][] bad = {
            {(byte) 128, 1, 'a'}, // orphan continuation
            {1, 1, 'a', (byte) 129, 1, 'b'}, // new message inside fragments
            {(byte) 193, 0}, // RSV
            {(byte) 131, 0}, // unknown opcode
            {9, 0}, // fragmented ping
            {(byte) 137, 126, 0, 0}, // oversized control encoding
            {(byte) 129, 126, 0, 0}, // non-minimal length
            {(byte) 129, 126, 0}, // truncated length
            {(byte) 129, 127, (byte) 128, 0, 0, 0, 0, 0, 0, 0},
            {(byte) 129, (byte) 128}, // masked server
            {(byte) 129, 1, (byte) 255}, // invalid UTF-8
            {(byte) 136, 1, 0}, // invalid close length
            {(byte) 136, 2, 3, (byte) 237} // reserved close code 1005
        };
        for (byte[] bytes : bad) assertTrue(read(new Socket(bytes)).isEmpty());
    }
    @Test public void outgoingLengthsAndMasksRoundTrip() throws Exception {
        for (int length : new int[]{0, 125, 126, 65535, 65536}) {
            Socket socket = new Socket(new byte[0]);
            char[] chars = new char[length]; Arrays.fill(chars, 'x');
            client(socket).sendText(new String(chars));
            byte[] frame = socket.output.toByteArray();
            assertEquals(129, frame[0] & 255); assertTrue((frame[1] & 128) != 0);
            int offset = 2; long decoded = frame[1] & 127;
            if (decoded >= 126) {
                int count = decoded == 126 ? 2 : 8; decoded = 0;
                for (int i = 0; i < count; i++) decoded = (decoded << 8) | (frame[offset++] & 255);
            }
            assertEquals(length, decoded);
            for (int i = 0; i < length; i++) assertEquals('x', (frame[offset + 4 + i] ^ frame[offset + (i & 3)]) & 255);
        }
    }
}
