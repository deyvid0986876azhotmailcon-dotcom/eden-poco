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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
                ProcessBuilder pb = new ProcessBuilder(
                        MainActivity.this.nativeLibDir + "/librun_nnapi.so",
                        MainActivity.this.getFilesDir().getAbsolutePath() + "/stories15M.bin",
                        "-z", MainActivity.this.getFilesDir().getAbsolutePath() + "/tokenizer.bin",
                        "-i", prompt == null ? "" : prompt);
                pb.directory(MainActivity.this.getFilesDir());
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
