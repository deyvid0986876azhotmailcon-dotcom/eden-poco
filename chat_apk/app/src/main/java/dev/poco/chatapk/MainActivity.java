package dev.poco.chatapk;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MainActivity extends Activity {
    private String nativeLibDir;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        nativeLibDir = getApplicationInfo().nativeLibraryDir;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }

        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        webView.addJavascriptInterface(new AndroidBackend(), "AndroidBackend");
        webView.loadUrl("file:///android_asset/chat.html");
        setContentView(webView);
    }

    public final class AndroidBackend {
        @JavascriptInterface
        public String send(String prompt) {
            try {
                File files = MainActivity.this.getFilesDir();
                // the 7B checkpoint if it has been copied in, else the small test model
                File model = new File(files, "llama2_7b.bin");
                if (!model.exists()) {
                    model = new File(files, "stories15M.bin");
                }
                File cache = new File(files, "nnapi_cache");
                cache.mkdirs();
                ProcessBuilder pb = new ProcessBuilder(
                        MainActivity.this.nativeLibDir + "/librun_nnapi.so",
                        model.getAbsolutePath(),
                        "-z", new File(files, "tokenizer.bin").getAbsolutePath(),
                        "-i", prompt == null ? "" : prompt);
                Map<String, String> env = pb.environment();
                env.put("NNAPI_Q8", "2");
                env.put("NNAPI_CACHE_DIR", cache.getAbsolutePath());
                // 8-bit weights mapped from files: kept in RAM, a 7B does not fit
                env.put("NNAPI_WEIGHTS_FILE", model.getAbsolutePath() + ".nnapi8");
                pb.directory(files);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() > 0) {
                            output.append('\n');
                        }
                        output.append(line);
                    }
                }
                process.waitFor();
                return output.toString();
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }
}
