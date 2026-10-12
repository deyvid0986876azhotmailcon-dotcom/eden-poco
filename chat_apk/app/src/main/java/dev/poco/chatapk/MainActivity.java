package dev.poco.chatapk;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.util.Log;
import org.json.JSONObject;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "PocoChat";
    private String nativeLibDir;
    private WebView webView;
    private Process currentProcess = null;

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
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new AndroidBackend(), "AndroidBackend");
        webView.loadUrl("file:///android_asset/chat.html");
        setContentView(webView);
    }

    public final class AndroidBackend {

        @JavascriptInterface
        public void send(final String prompt) {
            sendWithModel(prompt, "llama2_7b");
        }

        @JavascriptInterface
        public void sendWithModel(final String prompt, final String modelChoice) {
            new Thread(() -> runInference(prompt, modelChoice)).start();
        }

        @JavascriptInterface
        public void stop() {
            if (currentProcess != null) {
                try {
                    currentProcess.destroy();
                } catch (Exception ignored) {}
            }
        }

        private void runInference(final String prompt, final String modelChoice) {
            try {
                File files = MainActivity.this.getFilesDir();
                File cache = new File(files, "nnapi_cache");
                cache.mkdirs();

                File model = null;
                File tokenizer = null;
                String weightsPrefix = null;

                boolean useLlama = !"stories15M".equalsIgnoreCase(modelChoice);
                if (useLlama) {
                    File tmp7b = new File("/data/local/tmp/llm/llama2_7b.bin");
                    File internal7b = new File(files, "llama2_7b.bin");
                    model = tmp7b.exists() ? tmp7b : internal7b;
                    weightsPrefix = "/data/local/tmp/llm/wf7/l2";
                } else {
                    File tmp15m = new File("/data/local/tmp/llm/stories15M.bin");
                    File internal15m = new File(files, "stories15M.bin");
                    model = tmp15m.exists() ? tmp15m : internal15m;
                    weightsPrefix = "/data/local/tmp/llm/wf/s15";
                }

                File tmpTok = new File("/data/local/tmp/llm/tokenizer.bin");
                File internalTok = new File(files, "tokenizer.bin");
                tokenizer = tmpTok.exists() ? tmpTok : internalTok;

                if (!model.exists()) {
                    sendErrorToWeb("Modelo no encontrado: " + model.getAbsolutePath());
                    return;
                }

                List<String> cmd = new ArrayList<>();
                cmd.add(MainActivity.this.nativeLibDir + "/librun_nnapi.so");
                cmd.add(model.getAbsolutePath());
                cmd.add("-z");
                cmd.add(tokenizer.getAbsolutePath());
                cmd.add("-m");
                cmd.add("chat");
                cmd.add("-y");
                cmd.add("");
                cmd.add("-n");
                cmd.add(useLlama ? "64" : "128");
                cmd.add("-i");
                cmd.add(prompt == null ? "" : prompt);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                Map<String, String> env = pb.environment();
                env.put("NNAPI_DEVICE", "mtk-mdla_shim");
                env.put("NNAPI_Q8", "2");
                env.put("NNAPI_CACHE_DIR", cache.getAbsolutePath());
                if (weightsPrefix != null) {
                    env.put("NNAPI_WEIGHTS_FILE", weightsPrefix);
                }
                pb.directory(files);
                pb.redirectErrorStream(false);

                currentProcess = pb.start();

                // Hilo de stderr para logs y telemetría de velocidad
                new Thread(() -> {
                    try (BufferedReader errReader = new BufferedReader(new InputStreamReader(
                            currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = errReader.readLine()) != null) {
                            Log.d(TAG, "[APU] " + line);
                            if (line.contains("achieved tok/s:")) {
                                String[] parts = line.split("achieved tok/s:");
                                if (parts.length > 1) {
                                    final String speed = parts[1].trim();
                                    MainActivity.this.runOnUiThread(() ->
                                        webView.evaluateJavascript("window.onSpeedReport && window.onSpeedReport(" + JSONObject.quote(speed) + ");", null)
                                    );
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }).start();

                // Hilo de stdout para tokens en tiempo real
                try (InputStream in = currentProcess.getInputStream()) {
                    byte[] buffer = new byte[64];
                    int len;
                    boolean prefixSkipped = false;
                    StringBuilder prefixBuffer = new StringBuilder();

                    while ((len = in.read(buffer)) != -1) {
                        String chunk = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        if (!prefixSkipped) {
                            prefixBuffer.append(chunk);
                            String full = prefixBuffer.toString();
                            if (full.startsWith("Assistant: ")) {
                                chunk = full.substring("Assistant: ".length());
                                prefixSkipped = true;
                            } else if (full.length() > "Assistant: ".length()) {
                                chunk = full;
                                prefixSkipped = true;
                            } else {
                                continue;
                            }
                        }
                        if (chunk.length() > 0) {
                            final String toSend = chunk;
                            MainActivity.this.runOnUiThread(() ->
                                webView.evaluateJavascript("window.onStreamChunk && window.onStreamChunk(" + JSONObject.quote(toSend) + ");", null)
                            );
                        }
                    }
                }

                currentProcess.waitFor();
                MainActivity.this.runOnUiThread(() ->
                    webView.evaluateJavascript("window.onStreamDone && window.onStreamDone();", null)
                );

            } catch (Exception e) {
                Log.e(TAG, "Error in inference", e);
                sendErrorToWeb("Error: " + e.getMessage());
            } finally {
                currentProcess = null;
            }
        }

        private void sendErrorToWeb(final String errorMsg) {
            MainActivity.this.runOnUiThread(() ->
                webView.evaluateJavascript("window.onStreamError && window.onStreamError(" + JSONObject.quote(errorMsg) + ");", null)
            );
        }
    }
}
