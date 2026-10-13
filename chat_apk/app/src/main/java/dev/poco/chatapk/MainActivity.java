package dev.poco.chatapk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "PocoChat";
    // El mismo color que --bg en chat.html: es lo que se ve detrás de las barras del sistema
    private static final int BACKGROUND = 0xFF0B0F17;
    // El motor copia el prompt a un búfer de 512 bytes sin comprobar su tamaño
    private static final int MAX_PROMPT_BYTES = 480;
    private static final String ANSWER_PREFIX = "Assistant: ";
    // Lo que el motor escribe al empezar otro turno: la respuesta anterior ya terminó
    private static final String NEXT_TURN = "User: " + ANSWER_PREFIX;
    private static final long CLOCK_TICKS = clockTicks();

    private static final Pattern PID = Pattern.compile("pid=(\\d+)");
    // Lo que el motor (run_nnapi.c) dice por stderr sobre dónde corren los matmul
    private static final Pattern APU_DEVICE = Pattern.compile("nnapi: matmul runs on (\\S+) with");
    private static final Pattern APU_GRAPHS = Pattern.compile("nnapi: (\\d+) of (\\d+) matmul graphs compiled");
    private static final Pattern APU_RUNS = Pattern.compile("nnapi: (\\d+) matmuls on \\S+ at \\S+ ms each, (\\d+) on the CPU");
    private static final Pattern CPU_ONLY = Pattern.compile("nnapi: not used, (\\d+) matmuls on the CPU");

    // Modelos GGUF: los corre llama.cpp (libllama_completion.so), con las capas en la GPU por Vulkan
    // si el APK trae esa compilación. Los archivos se bajan aparte
    private static final String GGUF_DIR = "/data/local/tmp/lc/";
    // Modelos convertidos para run_nnapi (la APU), su tokenizador y sus archivos de pesos
    private static final String LLM_DIR = "/data/local/tmp/llm/";
    // Lo que llama.cpp dice al cargar el modelo: "ggml_vulkan: 0 = Mali-G615 MC6 (...) | uma: 1 | ..."
    // y "load_tensors: offloaded 29/29 layers to GPU" (0 de N si no hay GPU que usar)
    private static final Pattern VULKAN_DEVICE = Pattern.compile("ggml_vulkan: \\d+ = (.+?) \\(");
    private static final Pattern GPU_LAYERS = Pattern.compile("offloaded (\\d+)/(\\d+) layers to GPU");
    private static final String SYSTEM_PROMPT =
            "Eres un asistente útil. Responde siempre en español, de forma clara y breve.";
    // Con lo que llama.cpp cierra la respuesta en su salida
    private static final String END_OF_TEXT = "[end of text]";
    private static final int MAX_GGUF_PROMPT_CHARS = 4000;
    // "eval time = 9074.51 ms / 32 runs ( 283.58 ms per token, 3.53 tokens per second)". La línea del
    // prompt dice "tokens" donde esta dice "runs"
    private static final Pattern LLAMA_EVAL = Pattern.compile(
            "eval time =\\s*[\\d.]+ ms /\\s*(\\d+) runs\\s*\\(\\s*[\\d.]+ ms per token,\\s*([\\d.]+) tokens per second");

    private String nativeLibDir;
    private WebView webView;
    private volatile Run current = null;
    private volatile boolean destroyed = false;

    /** Una generación: el proceso del motor y lo que se va sabiendo de él. */
    private static final class Run {
        final Process process;
        final int pid;
        final long startMs = SystemClock.elapsedRealtime();
        volatile long firstTokenMs;
        volatile long lastTokenMs;
        volatile long endMs;
        volatile int tokens;
        // lo que el propio motor escribe por stderr
        volatile String apuDevice;
        volatile boolean apuMissing;
        volatile int onCpu; // matrices que el acelerador no aceptó
        volatile int graphs = -1;
        volatile int graphsTotal = -1;
        volatile long apuRuns = -1;
        volatile long cpuRuns = -1;
        // llama.cpp: cierra la respuesta de otra forma que run_nnapi, y con Vulkan pone capas en la GPU
        volatile boolean llamaCpp;
        volatile String hardware = "apu"; // el que eligió el usuario para esta respuesta: gpu, apu o cpu
        volatile String gpuDevice;  // la que encontró por Vulkan
        volatile int gpuLayers = -1; // capas que le dio, de gpuLayersTotal. -1 = todavía no lo ha dicho
        volatile int gpuLayersTotal = -1;
        volatile boolean stopped;   // lo paró el usuario
        // última lectura del tiempo de CPU del motor
        long cpuTicks;
        long cpuTicksAtMs;

        Run(Process process) {
            this.process = process;
            this.pid = pidOf(process);
            // el proceso acaba de nacer: lleva cero tiempo de CPU. Sin pid no hay lectura
            this.cpuTicks = pid > 0 ? 0 : -1;
            this.cpuTicksAtMs = startMs;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        nativeLibDir = getApplicationInfo().nativeLibraryDir;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND); // sin destello blanco mientras carga la página
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new AndroidBackend(), "AndroidBackend");
        webView.loadUrl("file:///android_asset/chat.html");

        // WebView no aplica su propio padding al contenido web: los márgenes del sistema van en
        // un contenedor que lo envuelve
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        keepClearOfSystemBars(root);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        Run run = current;
        if (run != null) {
            run.process.destroy(); // sin esto el motor seguiría generando sin pantalla
        }
        ((ViewGroup) webView.getParent()).removeView(webView);
        webView.destroy();
        super.onDestroy();
    }

    private void keepClearOfSystemBars(View root) {
        // Con targetSdk 35, Android 15 y posteriores dibujan la app detrás de la barra de estado
        // y de la de navegación. Aquí se pide lo mismo desde Android 11 y el contenedor se aparta
        // de las barras, del recorte de la cámara y del teclado
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                // iconos claros en las dos barras: el fondo es oscuro
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // sin el velo translúcido que el sistema pone bajo los tres botones
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = Math.max(bars.bottom, keyboard.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            // consumidos: el WebView no los recibe y env(safe-area-inset-*) vale 0 en la página,
            // así el margen no se aplica dos veces
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    private void callPage(final String function, final String argument) {
        runOnUiThread(() -> {
            if (!destroyed) {
                webView.evaluateJavascript("window." + function + " && window." + function + "(" + argument + ");", null);
            }
        });
    }

    private static long clockTicks() {
        try {
            long ticks = Os.sysconf(OsConstants._SC_CLK_TCK);
            return ticks > 0 ? ticks : 100;
        } catch (Exception e) {
            return 100;
        }
    }

    private static int pidOf(Process process) {
        // java.lang.Process no expone el pid en Android, pero su toString() lo lleva: "Process[pid=1234, ...]"
        Matcher matcher = PID.matcher(String.valueOf(process));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static long readCpuTicks(int pid) {
        // tiempo de CPU (usuario + núcleo) de un proceso, en ticks. -1 si ya no existe
        if (pid <= 0) {
            return -1;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
            String line = reader.readLine();
            // el nombre va entre paréntesis y puede llevar espacios: se cuenta desde el último ")"
            String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int cpuPercent(long ticks, long ms) {
        // 100 = un núcleo completo durante todo el intervalo
        return ms <= 0 ? -1 : (int) Math.round(ticks * 1000.0 / CLOCK_TICKS * 100.0 / ms);
    }

    private static int heldBack(CharSequence text, String marker) {
        // cuántos caracteres del final de text son el principio de marker
        for (int k = Math.min(text.length(), marker.length() - 1); k > 0; k--) {
            if (marker.startsWith(text.subSequence(text.length() - k, text.length()).toString())) {
                return k;
            }
        }
        return 0;
    }

    /** El archivo GGUF de un modelo que corre llama.cpp, o null si el modelo es de run_nnapi. */
    private static String ggufFor(String modelChoice) {
        if ("qwen15".equals(modelChoice)) {
            return "qwen15-q4km.gguf";
        }
        if ("llama32_1b".equals(modelChoice)) {
            return "llama32-1b-q4km.gguf";
        }
        if ("llama32_3b".equals(modelChoice)) {
            return "llama32-3b-q4km.gguf";
        }
        if ("lfm25".equals(modelChoice)) {
            return "lfm25-q4_0.gguf";
        }
        return null;
    }

    /** Los archivos de un modelo en el teléfono: de cuáles haya depende con qué hardware puede correr. */
    private static final class ModelFiles {
        File gguf;            // para llama.cpp: GPU, o CPU. null = el modelo no tiene esa versión
        File bin;             // convertido para run_nnapi: APU, o CPU sin acelerador. null = no tiene
        File tokenizer;       // el de ese .bin
        String weightsPrefix; // de los archivos de pesos de 8 bits que run_nnapi deja para la APU
        int steps = 64;       // tokens que run_nnapi procesa, contando los de la pregunta
    }

    private ModelFiles filesOf(String modelChoice) {
        File files = getFilesDir();
        ModelFiles m = new ModelFiles();
        String gguf = ggufFor(modelChoice);
        if (gguf != null) {
            m.gguf = new File(GGUF_DIR, gguf);
        }
        File tmpTok = new File(LLM_DIR + "tokenizer.bin");
        m.tokenizer = tmpTok.exists() ? tmpTok : new File(files, "tokenizer.bin");
        if ("qwen15".equals(modelChoice)) {
            m.bin = new File(LLM_DIR + "qwen2.5_1.5b.bin");
            m.weightsPrefix = LLM_DIR + "wf_qwen/q15";
            File own = new File(LLM_DIR + "qwen2.5_1.5b.bin.tokenizer");
            if (own.exists()) { m.tokenizer = own; }
        } else if ("llama32_3b".equals(modelChoice)) {
            m.bin = new File(LLM_DIR + "llama3_2_3b.bin");
            m.weightsPrefix = LLM_DIR + "wf_l3/l3";
            File own = new File(LLM_DIR + "llama3_2_3b.bin.tokenizer");
            if (own.exists()) { m.tokenizer = own; }
        } else if ("llama2_7b".equals(modelChoice)) {
            File tmp = new File(LLM_DIR + "llama2_7b.bin");
            m.bin = tmp.exists() ? tmp : new File(files, "llama2_7b.bin");
            m.weightsPrefix = LLM_DIR + "wf7/l2";
        } else if ("stories15M".equalsIgnoreCase(modelChoice)) {
            File tmp = new File(LLM_DIR + "stories15M.bin");
            m.bin = tmp.exists() ? tmp : new File(files, "stories15M.bin");
            m.weightsPrefix = LLM_DIR + "wf/s15";
            m.steps = 128;
        }
        return m;
    }

    private static String clampPrompt(String prompt) {
        String text = prompt == null ? "" : prompt;
        while (text.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        }
        return text;
    }

    public final class AndroidBackend {

        @JavascriptInterface
        public void send(final String prompt) {
            sendWithModel(prompt, "llama2_7b");
        }

        @JavascriptInterface
        public void sendWithModel(final String prompt, final String modelChoice) {
            sendMessage(prompt, modelChoice, "auto");
        }

        /** hardwareChoice: "gpu", "apu" o "cpu", lo que el usuario eligió en la página para este modelo. */
        @JavascriptInterface
        public void sendMessage(final String prompt, final String modelChoice, final String hardwareChoice) {
            new Thread(() -> runInference(prompt, modelChoice, hardwareChoice)).start();
        }

        /**
         * Con qué hardware puede correr un modelo ahora mismo, según los archivos que hay en el
         * teléfono: {"gpu": {"ok": true, "how": "..."}, "apu": {"ok": false, "why": "..."}, "cpu": ...}
         */
        @JavascriptInterface
        public String getHardware(final String modelChoice) {
            JSONObject info = new JSONObject();
            try {
                ModelFiles mf = filesOf(modelChoice);
                boolean hasGguf = mf.gguf != null && mf.gguf.exists();
                boolean hasBin = mf.bin != null && mf.bin.exists();
                info.put("gpu", new JSONObject().put("ok", hasGguf)
                        .put("how", "llama.cpp con todas las capas en la GPU Mali-G615, por Vulkan")
                        .put("why", mf.gguf == null ? "Este modelo no tiene versión GGUF, que es la que usa la GPU."
                                : "Falta en el teléfono " + mf.gguf.getAbsolutePath()));
                info.put("apu", new JSONObject().put("ok", hasBin)
                        .put("how", "run_nnapi sobre la APU 780 (NNAPI, mtk-mdla_shim)")
                        .put("why", mf.bin == null ? "Este modelo no tiene archivo .bin compilado para la APU."
                                : "Falta en el teléfono " + mf.bin.getAbsolutePath()));
                info.put("cpu", new JSONObject().put("ok", hasGguf || hasBin)
                        .put("how", hasGguf ? "llama.cpp en los 4 núcleos grandes, sin GPU"
                                : "run_nnapi sin acelerador, en 1 núcleo")
                        .put("why", "Falta en el teléfono el archivo de este modelo."));
            } catch (Exception ignored) {}
            return info.toString();
        }

        @JavascriptInterface
        public void stop() {
            Run run = current;
            if (run != null) {
                run.stopped = true;
                try {
                    run.process.destroy();
                } catch (Exception ignored) {}
            }
        }

        @JavascriptInterface
        public String getSystemStats() {
            return collectStats(null, false).toString();
        }

        private JSONObject collectStats(Run run, boolean finished) {
            JSONObject stats = new JSONObject();
            try {
                // 1. RAM de todo el teléfono
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(memInfo);
                    long totalMb = memInfo.totalMem / (1024 * 1024);
                    long availMb = memInfo.availMem / (1024 * 1024);
                    long usedMb = totalMb - availMb;
                    int percent = (int) (100 * usedMb / totalMb);
                    stats.put("ramTotalGb", String.format(Locale.US, "%.1f", totalMb / 1024.0));
                    stats.put("ramUsedGb", String.format(Locale.US, "%.1f", usedMb / 1024.0));
                    stats.put("ramFreeGb", String.format(Locale.US, "%.1f", availMb / 1024.0));
                    stats.put("ramPercent", percent);
                }

                // 2. CPU del motor. -1 = sin dato
                stats.put("cpuPercent", run == null ? -1 : engineCpuPercent(run, finished));

                // 3. GPU, y el papel de la CPU
                describeGpu(run, stats);
                describeCpu(run, stats);
                if (run != null) {
                    stats.put("hardware", run.hardware);
                }

                if (run != null) {
                    // 4. Tokens, tiempo y velocidad. La velocidad se cuenta desde el primer token,
                    // como hace el motor: lo anterior es cargar el modelo y preparar la APU
                    long now = finished ? run.endMs : SystemClock.elapsedRealtime();
                    long first = run.firstTokenMs;
                    long until = finished ? run.lastTokenMs : now;
                    int tokens = run.tokens;
                    double tokPerSec = (tokens > 1 && until > first) ? (tokens - 1) * 1000.0 / (until - first) : 0.0;
                    stats.put("final", finished);
                    stats.put("tokenCount", tokens);
                    stats.put("elapsedMs", now - run.startMs);
                    stats.put("elapsedSec", (now - run.startMs) / 1000);
                    stats.put("ttftMs", first > 0 ? first - run.startMs : -1);
                    stats.put("tokPerSec", String.format(Locale.US, "%.2f", tokPerSec));

                    // 5. APU
                    describeApu(run, stats);
                }
            } catch (Exception ignored) {}
            return stats;
        }

        private int engineCpuPercent(Run run, boolean finished) {
            // /proc/stat (la CPU de todo el sistema) no es legible para las apps desde Android 8.
            // Lo que sí se puede leer es el tiempo de CPU del proceso del motor, que es hijo de la app
            synchronized (run) {
                long ticks = finished ? -1 : readCpuTicks(run.pid);
                long now = SystemClock.elapsedRealtime();
                if (ticks < 0) {
                    // el proceso ya no está: la media de toda la generación, hasta la última lectura
                    return run.cpuTicks < 0 ? -1 : cpuPercent(run.cpuTicks, run.cpuTicksAtMs - run.startMs);
                }
                int percent = run.cpuTicks < 0 ? -1 : cpuPercent(ticks - run.cpuTicks, now - run.cpuTicksAtMs);
                run.cpuTicks = ticks;
                run.cpuTicksAtMs = now;
                return percent;
            }
        }

        private void describeGpu(Run run, JSONObject stats) throws JSONException {
            // Android no da a las apps la carga de la GPU. Lo que se muestra es lo que llama.cpp
            // dice al cargar el modelo: qué GPU encontró por Vulkan y cuántas capas le dio. No es
            // una medida de uso. run_nnapi no usa la GPU
            String state = "idle";
            String status = "En reposo";
            if (run != null && !"gpu".equals(run.hardware)) {
                status = "En reposo · seleccionada " + run.hardware.toUpperCase(Locale.US);
            } else if (run != null) {
                if (run.gpuLayers > 0) {
                    state = "active";
                    status = "Activa · " + (run.gpuDevice != null ? run.gpuDevice : "Mali-G615")
                            + " (Vulkan · " + run.gpuLayers + " capas)";
                } else if (run.gpuLayers == 0) {
                    // se eligió la GPU y el motor dice que no le dio ninguna capa: corre en CPU
                    state = "warn";
                    status = "Sin usar · el motor no le dio capas";
                } else {
                    state = "starting";
                    status = run.gpuDevice != null ? "Preparando · Vulkan" : "Preparando…";
                }
            }
            stats.put("gpuState", state);
            stats.put("gpuStatus", status);
        }

        private void describeCpu(Run run, JSONObject stats) throws JSONException {
            // La carga está en el medidor de CPU (cpuPercent). Esto dice qué papel hace: con la GPU
            // o la APU elegidas sigue trabajando, porque prepara lo que el acelerador calcula
            String state = "idle";
            String status = "En reposo";
            if (run != null && "cpu".equals(run.hardware)) {
                state = "active";
                status = run.llamaCpp ? "Activa · llama.cpp en los 4 núcleos grandes"
                        : "Activa · run_nnapi en 1 núcleo, sin acelerador";
            } else if (run != null) {
                status = "De apoyo · seleccionada " + run.hardware.toUpperCase(Locale.US);
            }
            stats.put("cpuState", state);
            stats.put("cpuStatus", status);
        }

        private void describeApu(Run run, JSONObject stats) throws JSONException {
            // No hay forma de leer la carga de la APU desde una app. Lo que se muestra es lo que el
            // motor dice de sí mismo: si está usando el acelerador y, al terminar, qué parte de los
            // matmul corrió en él
            if (!"apu".equals(run.hardware)) {
                stats.put("apuState", "off");
                stats.put("apuSummary", "En reposo · seleccionada " + run.hardware.toUpperCase(Locale.US));
                stats.put("apuPercent", -1);
                return;
            }
            String state;
            String summary;
            int percent = -1;
            long apu = run.apuRuns;
            long cpu = run.cpuRuns;
            if (apu >= 0 && cpu >= 0 && apu + cpu > 0) {
                percent = (int) Math.round(100.0 * apu / (apu + cpu));
                state = apu > 0 ? "active" : "cpu";
                summary = percent + " % de los matmul en APU";
                if (run.graphsTotal > 0) {
                    summary += " · " + run.graphs + "/" + run.graphsTotal + " matrices";
                }
            } else if (run.apuMissing) {
                state = "cpu";
                summary = "No disponible · todo en CPU";
            } else if (run.apuDevice != null) {
                state = "active";
                summary = "Activa · " + run.apuDevice;
                if (run.onCpu > 0) {
                    summary += " · " + run.onCpu + " matrices en CPU";
                }
            } else {
                state = "starting";
                summary = "Preparando…";
            }
            stats.put("apuState", state);
            stats.put("apuSummary", summary);
            stats.put("apuPercent", percent);
        }

        private void readEngineLog(Run run) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    run.process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, "[APU] " + line);
                    Matcher matcher;
                    if ((matcher = APU_DEVICE.matcher(line)).find()) {
                        run.apuDevice = matcher.group(1);
                    } else if ((matcher = APU_GRAPHS.matcher(line)).find()) {
                        run.graphs = Integer.parseInt(matcher.group(1));
                        run.graphsTotal = Integer.parseInt(matcher.group(2));
                    } else if ((matcher = APU_RUNS.matcher(line)).find()) {
                        run.apuRuns = Long.parseLong(matcher.group(1));
                        run.cpuRuns = Long.parseLong(matcher.group(2));
                    } else if ((matcher = CPU_ONLY.matcher(line)).find()) {
                        run.apuRuns = 0;
                        run.cpuRuns = Long.parseLong(matcher.group(1));
                        run.apuMissing = true;
                    } else if (line.contains("matmul runs on the CPU") || line.contains("nnapi: dlopen failed")
                            || line.contains("nnapi: missing symbol")) {
                        run.apuMissing = true;
                    } else if (run.llamaCpp && (matcher = VULKAN_DEVICE.matcher(line)).find()) {
                        run.gpuDevice = matcher.group(1).trim();
                    } else if (run.llamaCpp && (matcher = GPU_LAYERS.matcher(line)).find()) {
                        run.gpuLayers = Integer.parseInt(matcher.group(1));
                        run.gpuLayersTotal = Integer.parseInt(matcher.group(2));
                    } else if (run.llamaCpp && (matcher = LLAMA_EVAL.matcher(line)).find()) {
                        // llama.cpp: los tokens que generó y su velocidad, medidos por el propio motor
                        run.tokens = Integer.parseInt(matcher.group(1));
                        callPage("onFinalSpeedReport", JSONObject.quote(matcher.group(2)));
                    } else if (line.contains("it stays on the CPU")) {
                        run.onCpu++;
                    } else if (line.contains("achieved tok/s:")) {
                        // solo en modo generate: la velocidad que mide el propio motor
                        String[] parts = line.split("achieved tok/s:");
                        if (parts.length > 1) {
                            callPage("onFinalSpeedReport", JSONObject.quote(parts[1].trim()));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        private void streamAnswer(Run run) throws IOException {
            // stdout: el texto, trozo a trozo según lo va escribiendo el motor. El lector guarda
            // los bytes de un carácter UTF-8 que llegue partido entre dos lecturas
            // run_nnapi antepone "Assistant: " y avisa del turno siguiente; llama.cpp no antepone nada y
            // cierra con "[end of text]"
            final String prefix = run.llamaCpp ? "" : ANSWER_PREFIX;
            final String end = run.llamaCpp ? END_OF_TEXT : NEXT_TURN;
            try (Reader reader = new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[256];
                StringBuilder pending = new StringBuilder(); // recibido y todavía sin enviar a la página
                boolean prefixSeen = prefix.isEmpty();
                boolean ended = false;
                int length;
                while (!ended && (length = reader.read(buffer)) != -1) {
                    pending.append(buffer, 0, length);
                    if (!prefixSeen) {
                        if (pending.length() < prefix.length() && prefix.startsWith(pending.toString())) {
                            continue; // todavía puede ser el prefijo
                        }
                        if (pending.indexOf(prefix) == 0) {
                            pending.delete(0, prefix.length());
                        }
                        prefixSeen = true;
                    }
                    int cut = pending.indexOf(end);
                    ended = cut >= 0;
                    if (!ended) {
                        // se retiene el final que todavía podría ser el principio de ese aviso
                        cut = pending.length() - heldBack(pending, end);
                    }
                    if (cut > 0) {
                        sendChunk(run, pending.substring(0, cut));
                        pending.delete(0, cut);
                    }
                }
                if (ended && run.llamaCpp) {
                    // llama.cpp escribe su velocidad al salir: se le deja terminar, sin cortarle la salida
                    while (reader.read(buffer) != -1) { /* nada: ya no es parte de la respuesta */ }
                } else if (ended) {
                    run.process.destroy(); // lo que siga ya no es de esta respuesta
                } else if (prefixSeen && pending.length() > 0) {
                    sendChunk(run, pending.toString());
                }
            }
        }

        private void sendChunk(Run run, String text) {
            // cada trozo cuenta como un token: el motor vacía su salida tras cada uno. Si dos
            // llegan juntos en una lectura se cuentan como uno, así que es una aproximación
            long now = SystemClock.elapsedRealtime();
            if (run.firstTokenMs == 0) {
                run.firstTokenMs = now;
            }
            run.lastTokenMs = now;
            run.tokens++;
            callPage("onStreamChunk", JSONObject.quote(text));
        }

        /**
         * "/sonda [formato [sin|con]]" en la caja del chat: corre la sonda de la API Neuron
         * (npu-probe/neuron_quant_probe.c, empaquetada como libneuron_probe.so) como proceso de la
         * app y escribe lo que diga. La pregunta que contesta: el cargador le niega a la app la
         * biblioteca de MediaTek, pero ¿se la niega también a un ejecutable que la app lanza? De
         * eso depende que la APU pueda usarse con pesos de 4 bits desde la app.
         */
        private void runProbe(String command) {
            String[] parts = command.split("\\s+");
            List<String> cmd = new ArrayList<>();
            cmd.add(MainActivity.this.nativeLibDir + "/libneuron_probe.so");
            for (int i = 1; i < parts.length && i < 3; i++) {
                cmd.add(parts[i]);
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.directory(MainActivity.this.getFilesDir());
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "[SONDA] " + line);
                        callPage("onStreamChunk", JSONObject.quote(line + "\n\n"));
                    }
                }
                int exit = process.waitFor();
                Log.d(TAG, "[SONDA] código de salida " + exit);
                callPage("onStreamChunk", JSONObject.quote("(la sonda terminó con código " + exit + ")"));
            } catch (Exception e) {
                Log.e(TAG, "[SONDA] no arrancó", e);
                callPage("onStreamChunk", JSONObject.quote("No se pudo lanzar la sonda: " + e.getMessage()));
            }
            callPage("onStreamDone", "");
        }

        private void runInference(final String prompt, final String modelChoice, final String hardwareChoice) {
            Run run = null;
            Thread telemetry = null;
            String error = null;
            boolean done = false;
            try {
                if (prompt != null && prompt.trim().startsWith("/sonda")) {
                    runProbe(prompt.trim()); // diagnóstico: no pasa por ningún modelo
                    return;
                }
                File files = MainActivity.this.getFilesDir();
                File cache = new File(files, "nnapi_cache");
                cache.mkdirs();

                // El hardware lo elige el usuario, y de él sale el motor:
                //   gpu: llama.cpp con todas las capas del GGUF en la Mali-G615, por Vulkan
                //   apu: run_nnapi con el modelo convertido (.bin), sobre NNAPI
                //   cpu: llama.cpp sin GPU o, si el modelo no tiene GGUF, run_nnapi sin acelerador
                // Si el modelo no tiene el archivo que ese hardware pide, se dice y no se cambia a otro
                ModelFiles mf = filesOf(modelChoice);
                boolean hasGguf = mf.gguf != null && mf.gguf.exists();
                boolean hasBin = mf.bin != null && mf.bin.exists();
                String wanted = hardwareChoice == null ? "" : hardwareChoice.toLowerCase(Locale.US);
                if (!"gpu".equals(wanted) && !"apu".equals(wanted) && !"cpu".equals(wanted)) {
                    // sin elección (sendWithModel): la GPU si el modelo tiene GGUF, y si no la APU
                    wanted = hasGguf || mf.bin == null ? "gpu" : "apu";
                }
                final String hardware = wanted;
                final boolean llamaCpp;
                if ("gpu".equals(hardware)) {
                    if (!hasGguf) {
                        error = mf.gguf == null ? "Este modelo no tiene versión GGUF: no puede ir por la GPU."
                                : "Falta el modelo para la GPU: " + mf.gguf.getAbsolutePath();
                        return;
                    }
                    llamaCpp = true;
                } else if ("apu".equals(hardware)) {
                    if (!hasBin) {
                        error = mf.bin == null ? "Este modelo no tiene archivo .bin compilado para la APU."
                                : "Falta el modelo para la APU: " + mf.bin.getAbsolutePath();
                        return;
                    }
                    llamaCpp = false;
                } else {
                    if (!hasGguf && !hasBin) {
                        error = "Falta en el teléfono el archivo de este modelo.";
                        return;
                    }
                    llamaCpp = hasGguf;
                }
                final boolean gpu = "gpu".equals(hardware);
                final boolean apu = "apu".equals(hardware);
                File model = llamaCpp ? mf.gguf : mf.bin;
                File tokenizer = mf.tokenizer;
                String weightsPrefix = mf.weightsPrefix;

                List<String> cmd = new ArrayList<>();
                if (llamaCpp) {
                    // llama.cpp: una sola pregunta con la plantilla de chat del modelo. Con la GPU va la
                    // compilación con Vulkan; sin ella, la de solo CPU si el APK la trae. Los hilos, a
                    // los 4 núcleos grandes
                    File cpuBuild = new File(MainActivity.this.nativeLibDir, "libllama_completion_cpu.so");
                    cmd.add(gpu || !cpuBuild.exists()
                            ? MainActivity.this.nativeLibDir + "/libllama_completion.so" : cpuBuild.getAbsolutePath());
                    cmd.add("-m");
                    cmd.add(model.getAbsolutePath());
                    cmd.add("-ngl");
                    cmd.add(gpu ? "99" : "0");
                    cmd.add("-t");
                    cmd.add("4");
                    cmd.add("-C");
                    cmd.add("f0");
                    cmd.add("-c");
                    cmd.add("4096");
                    cmd.add("-n");
                    cmd.add("512");
                    cmd.add("--temp");
                    cmd.add("0.7");
                    cmd.add("-sys");
                    cmd.add(SYSTEM_PROMPT);
                    cmd.add("-p");
                    cmd.add(prompt.length() > MAX_GGUF_PROMPT_CHARS ? prompt.substring(0, MAX_GGUF_PROMPT_CHARS) : prompt);
                    cmd.add("-cnv");
                    cmd.add("-st");
                    cmd.add("--simple-io");
                    cmd.add("--no-display-prompt");
                } else {
                    cmd.add(MainActivity.this.nativeLibDir + "/librun_nnapi.so");
                    cmd.add(model.getAbsolutePath());
                    cmd.add("-z");
                    cmd.add(tokenizer.getAbsolutePath());
                    cmd.add("-m");
                    cmd.add("chat");
                    cmd.add("-y");
                    cmd.add("");
                    cmd.add("-n");
                    cmd.add(String.valueOf(mf.steps));
                    cmd.add("-i");
                    cmd.add(clampPrompt(prompt));
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                Map<String, String> env = pb.environment();
                if (!llamaCpp && apu) {
                    env.put("NNAPI_DEVICE", "mtk-mdla_shim");
                    env.put("NNAPI_Q8", "2");
                    env.put("NNAPI_CACHE_DIR", cache.getAbsolutePath());
                    if (weightsPrefix != null) {
                        env.put("NNAPI_WEIGHTS_FILE", weightsPrefix);
                    }
                } else if (!llamaCpp) {
                    // CPU con un modelo que solo existe convertido: run_nnapi sin NNAPI, en C plano
                    env.put("NNAPI_DEVICE", "off");
                }
                pb.directory(files);
                pb.redirectErrorStream(false);

                synchronized (MainActivity.this) {
                    if (current != null) {
                        error = "Ya hay una generación en curso.";
                        return;
                    }
                    run = new Run(pb.start());
                    run.llamaCpp = llamaCpp;
                    run.hardware = hardware;
                    current = run;
                }
                final Run active = run;

                // El motor solo lee de su entrada al empezar otro turno, cuando el modelo da su
                // respuesta por terminada antes de agotar los tokens. Con una línea vacía y la
                // entrada cerrada no se queda esperando ahí para siempre: sigue, escribe el aviso
                // de turno nuevo y streamAnswer lo corta
                try (OutputStream stdin = active.process.getOutputStream()) {
                    stdin.write('\n');
                } catch (IOException ignored) {}

                // Telemetría periódica (cada 1 s)
                telemetry = new Thread(() -> {
                    try {
                        while (true) {
                            Thread.sleep(1000);
                            callPage("onTelemetryUpdate", collectStats(active, false).toString());
                        }
                    } catch (InterruptedException ignored) {}
                });
                telemetry.start();

                Thread engineLog = new Thread(() -> readEngineLog(active));
                engineLog.start();

                streamAnswer(active);
                active.endMs = SystemClock.elapsedRealtime();

                int exit = active.process.waitFor();
                telemetry.interrupt();
                telemetry.join(1500);
                engineLog.join(1000); // el reparto entre APU y CPU es lo último que escribe el motor
                if (active.llamaCpp && active.tokens == 0 && exit != 0 && !active.stopped) {
                    // llama.cpp murió sin escribir nada. El hardware lo eligió el usuario: no se cambia solo
                    error = "El motor terminó sin responder (código " + exit + ").";
                    if ("gpu".equals(active.hardware)) {
                        error += " Iba con la GPU: puedes probar el mismo modelo con CPU.";
                    }
                } else {
                    done = true;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in inference", e);
                error = "Error: " + e.getMessage();
            } finally {
                if (telemetry != null) {
                    telemetry.interrupt();
                }
                if (run != null) {
                    run.process.destroy();
                    synchronized (MainActivity.this) {
                        if (current == run) {
                            current = null;
                        }
                    }
                }
                // los avisos a la página van con el motor ya liberado: puede pedir otra generación enseguida
                if (done) {
                    callPage("onTelemetryUpdate", collectStats(run, true).toString());
                    callPage("onStreamDone", "");
                } else if (error != null) {
                    callPage("onStreamError", JSONObject.quote(error));
                }
            }
        }
    }
}
