package dev.poco.chatapk;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        webView.addJavascriptInterface(new AndroidBackend(), "AndroidBackend");
        webView.loadUrl("file:///android_asset/chat.html");
        setContentView(webView);
    }

    public static final class AndroidBackend {
        @JavascriptInterface
        public String send(String prompt) {
            try {
                Process process = new ProcessBuilder(
                        "/data/local/tmp/llm/run_nnapi",
                        "/data/local/tmp/llm/stories15M.bin",
                        "-i",
                        prompt == null ? "" : prompt)
                        .redirectErrorStream(true)
                        .start();

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
