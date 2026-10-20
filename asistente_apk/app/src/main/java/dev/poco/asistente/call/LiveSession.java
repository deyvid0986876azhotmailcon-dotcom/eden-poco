package dev.poco.asistente.call;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Base64;
import dev.poco.asistente.core.AssistantCore;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One voice call with Gemini Live: the microphone goes up as 16 kHz PCM, her voice comes back as 24 kHz PCM,
 * and the tools she may use run through AssistantCore (the same executor that validates everything else).
 * Only safe tools are offered in a call: nothing that deletes, since a code cannot be read out reliably.
 */
public final class LiveSession {
    public interface Events { void onState(String text); void onEnded(String why); }

    static final String HOST = "generativelanguage.googleapis.com";
    static final String MODEL = "models/gemini-3.1-flash-live-preview";
    public static final String[] CALL_TOOLS = {"recordar", "programar_recordatorio", "listar_recordatorios", "registrar_movimiento",
            "registrar_peso", "registrar_comida", "registrar_entreno", "consultar_resumen"};
    private static final int IN_RATE = 16000, OUT_RATE = 24000;

    private final AssistantCore core;
    private final AudioManager audio;
    private final Events events;
    private volatile LiveSocket socket;
    private volatile boolean running, muted;
    private AudioRecord mic;
    private AudioTrack speaker;
    private Thread reader, recorder;
    private int previousMode;

    public LiveSession(AssistantCore core, AudioManager audio, Events events) {
        this.core = core; this.audio = audio; this.events = events;
    }

    /** Starts on a background thread; reason is what the call is about (a reminder), or null when David calls. */
    public void start(String apiKey, String reason) {
        running = true;
        reader = new Thread(() -> run(apiKey, reason), "live-call");
        reader.start();
    }

    public void setMuted(boolean value) { muted = value; }

    private volatile boolean speakerOn;
    /** Earpiece (like a phone call) or loudspeaker; can change during the call. */
    public void setSpeaker(boolean on) {
        speakerOn = on;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            int wanted = on ? android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            for (android.media.AudioDeviceInfo device : audio.getAvailableCommunicationDevices())
                if (device.getType() == wanted) { audio.setCommunicationDevice(device); return; }
        }
        audio.setSpeakerphoneOn(on);
    }

    private void run(String apiKey, String reason) {
        try {
            events.onState("Conectando…");
            socket = LiveSocket.connect(HOST, "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + apiKey);
            socket.sendText(setup().toString());
            socket.readLoop(new LiveSocket.Listener() {
                @Override public void onText(String text) { handle(text, reason); }
                @Override public void onClosed(String why) { end(why == null || why.isEmpty() ? "La llamada terminó." : "La llamada terminó (" + why + ")."); }
            });
        } catch (IOException e) {
            end("No pude conectar con Gemini: " + e.getMessage());
        } catch (Exception e) {
            end("No pude iniciar la llamada.");
        }
    }

    private JSONObject setup() throws Exception {
        JSONObject context = core.liveContext();
        String persona = context.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        String call = persona + " AHORA ESTÁS EN UNA LLAMADA DE VOZ con David: hablá natural, frases cortas, como por teléfono. "
                + "Nunca leas en voz alta IDs, códigos ni JSON. Para borrar o cancelar algo, decile que te lo escriba por WhatsApp "
                + "(eso pide un código). Si te pide que le recuerdes algo más tarde, usá programar_recordatorio; si te cuenta algo de él, recordar.";
        JSONArray all = core.liveDeclarations(), tools = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            String name = all.getJSONObject(i).getString("name");
            for (String allowed : CALL_TOOLS) if (allowed.equals(name)) tools.put(all.getJSONObject(i));
        }
        JSONObject voice = new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", "Aoede")));
        return new JSONObject().put("setup", new JSONObject()
                .put("model", MODEL)
                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("AUDIO")).put("speechConfig", voice))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", call))))
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", tools))));
    }

    private void handle(String text, String reason) {
        try {
            JSONObject m = new JSONObject(text);
            if (m.has("setupComplete")) {
                startAudio();
                String opening = reason == null
                        ? "David te acaba de llamar. Saludalo corto y preguntale qué necesita."
                        : "Lo estás llamando vos para recordarle esto: «" + reason + "». Saludalo, decíselo en una frase y preguntale si necesita algo más.";
                socket.sendText(new JSONObject().put("clientContent", new JSONObject()
                        .put("turns", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", opening)))))
                        .put("turnComplete", true)).toString());
                events.onState("En llamada");
                return;
            }
            JSONObject content = m.optJSONObject("serverContent");
            if (content != null) {
                if (content.optBoolean("interrupted") && speaker != null) { speaker.pause(); speaker.flush(); speaker.play(); }
                JSONObject turn = content.optJSONObject("modelTurn");
                JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject data = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (data != null && data.optString("mimeType").startsWith("audio/pcm") && speaker != null) {
                        byte[] pcm = Base64.decode(data.getString("data"), Base64.DEFAULT);
                        speaker.write(pcm, 0, pcm.length);
                    }
                }
            }
            JSONObject call = m.optJSONObject("toolCall");
            if (call != null) {
                JSONArray calls = call.optJSONArray("functionCalls"), answers = new JSONArray();
                for (int i = 0; calls != null && i < calls.length(); i++) {
                    JSONObject f = calls.getJSONObject(i);
                    String result = core.callTool(f.getString("name"), f.optJSONObject("args") == null ? new JSONObject() : f.getJSONObject("args"));
                    answers.put(new JSONObject().put("id", f.optString("id")).put("name", f.getString("name"))
                            .put("response", new JSONObject().put("result", result)));
                }
                socket.sendText(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", answers)).toString());
            }
            if (m.has("goAway")) events.onState("La llamada va a cortarse pronto");
        } catch (Exception e) {
            end("Se cortó la llamada por un error.");
        }
    }

    @SuppressLint("MissingPermission") // CallActivity asks for RECORD_AUDIO before starting
    private synchronized void startAudio() {
        if (!running || mic != null) return;
        previousMode = audio.getMode();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(speakerOn); // starts on the earpiece, like a phone call
        int inMin = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(inMin, IN_RATE / 5));
        if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler aec = AcousticEchoCanceler.create(mic.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor ns = NoiseSuppressor.create(mic.getAudioSessionId()); if (ns != null) ns.setEnabled(true); }
        int outMin = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        speaker = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(outMin, OUT_RATE)).setTransferMode(AudioTrack.MODE_STREAM).build();
        speaker.play();
        mic.startRecording();
        recorder = new Thread(this::record, "live-mic");
        recorder.start();
    }

    private void record() {
        byte[] chunk = new byte[IN_RATE / 10 * 2]; // 100 ms
        byte[] silence = new byte[chunk.length];
        while (running) {
            int n;
            try { n = mic.read(chunk, 0, chunk.length); } catch (RuntimeException e) { return; } // released by end()
            if (n <= 0) continue;
            try {
                String data = Base64.encodeToString(muted ? silence : chunk, 0, n, Base64.NO_WRAP);
                socket.sendText("{\"realtimeInput\":{\"audio\":{\"data\":\"" + data + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}");
            } catch (IOException e) { end("Se cortó la conexión."); return; }
        }
    }

    public void hangUp() { end("Colgaste."); }

    private synchronized void end(String why) {
        if (!running) return;
        running = false;
        if (socket != null) socket.close();
        if (mic != null) { try { mic.stop(); } catch (RuntimeException ignored) { } mic.release(); mic = null; }
        if (speaker != null) { try { speaker.stop(); } catch (RuntimeException ignored) { } speaker.release(); speaker = null; }
        if (android.os.Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice(); else audio.setSpeakerphoneOn(false);
        audio.setMode(previousMode);
        events.onEnded(why);
    }
}
