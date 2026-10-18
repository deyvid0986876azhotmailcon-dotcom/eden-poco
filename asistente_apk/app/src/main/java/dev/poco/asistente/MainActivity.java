package dev.poco.asistente;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import dev.poco.asistente.core.GeminiProtocol;
import dev.poco.asistente.core.Validation;
import java.util.Arrays;

/** Native views only. No exported bridge, WebView, remote service or accessibility permission. */
public final class MainActivity extends Activity {
    private AssistantApplication app;
    private LinearLayout body;
    private TextView transcript, progress;
    private ScrollView chatScroll;
    private EditText input;
    private Button send;
    private String screen = "Chat", draft = "";
    private final Runnable observer = this::refreshChat;
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        app = (AssistantApplication) getApplication();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Android 15/target 35 enforces edge-to-edge; consume status/navigation/IME insets.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(12) + safe.left, dp(8) + safe.top, dp(12) + safe.right, dp(8) + safe.bottom);
            } else v.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                        dp(12) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout tabs = new LinearLayout(this);
        for (String name : new String[]{"Chat", "Resumen", "Ajustes"}) {
            Button button = new Button(this); button.setText(name); button.setAllCaps(false);
            button.setOnClickListener(v -> show(name));
            tabs.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestApplyInsets();
        if (savedState != null) screen = savedState.getString("screen", "Chat");
        show(screen);
    }
    @Override protected void onStart() { super.onStart(); app.listen(observer); refreshChat(); }
    @Override protected void onStop() { app.unlisten(observer); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("screen", screen); // no key or chat copied to Bundle
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text) {
        TextView view = new TextView(this); view.setTextSize(16); view.setText(text); view.setPadding(0, dp(8), 0, dp(8)); return view;
    }
    private Button button(String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
    private void show(String name) {
        if (input != null) draft = input.getText().toString();
        screen = name; body.removeAllViews(); input = null; transcript = null;
        if (name.equals("Chat")) chat(); else if (name.equals("Resumen")) summary(); else settings();
    }
    private void chat() {
        transcript = label(app.chat()); transcript.setTextIsSelectable(true);
        chatScroll = new ScrollView(this); chatScroll.setFillViewport(true); chatScroll.addView(transcript);
        body.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        progress = label(""); body.addView(progress);
        input = new EditText(this); input.setHint("Gasté 3500 en super"); input.setMinLines(1); input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)}); input.setText(draft); input.setSaveEnabled(false);
        body.addView(input);
        LinearLayout controls = new LinearLayout(this);
        send = button("Enviar", () -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty() || app.busy()) return;
            input.setText(""); draft = ""; app.send(message);
        });
        controls.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("PARAR", app::stop), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(controls); refreshChat();
    }
    private void refreshChat() {
        if (!screen.equals("Chat") || transcript == null) return;
        transcript.setText(app.chat()); send.setEnabled(!app.busy());
        progress.setText(app.busy() ? "Procesando… Si hay fallos de red, probar los otros modelos puede tardar. PARAR cancela la acción." : "");
        chatScroll.post(() -> { if (chatScroll != null) chatScroll.fullScroll(View.FOCUS_DOWN); });
    }
    private void summary() {
        body.addView(button("Actualizar", () -> show("Resumen")));
        ScrollView scroll = new ScrollView(this); TextView text = label("Leyendo datos locales…");
        text.setTextIsSelectable(true); scroll.addView(text); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        app.worker.execute(() -> {
            String result = app.core.summary();
            app.main.post(() -> { if (!isDestroyed() && text.isAttachedToWindow()) text.setText(result); });
        });
    }
    private Spinner spinner(String[] values, String selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Arrays.asList(values).indexOf(selected))); return spinner;
    }
    private void settings() {
        ScrollView scroll = new ScrollView(this); LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form); body.addView(scroll);
        form.addView(label("Gemini recibe tu mensaje y un resumen local. En la cuota gratis Google puede usar esos datos para mejorar sus productos. Las reglas funcionan sin clave."));
        form.addView(label("Fase 1: firma debug pública. Cambiar por una firma privada antes de fase 2."));
        TextView status = label(app.settings.vault.hasKey() ? "Clave cifrada guardada." : "Sin clave guardada."); form.addView(status);
        EditText key = new EditText(this); key.setHint("Pegá la clave de AI Studio"); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        key.setSaveEnabled(false); key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)}); form.addView(key);
        form.addView(button("Guardar clave", () -> {
            String value = key.getText().toString().trim(); key.setText("");
            try { app.settings.vault.save(value); status.setText("Clave cifrada guardada."); }
            catch (Exception e) { status.setText("No pude guardar la clave. Revisá el formato y volvé a pegarla."); }
        }));
        form.addView(button("Borrar clave", () -> {
            app.stop(); key.setText("");
            try { app.settings.vault.clear(); status.setText("Clave borrada. Las reglas locales siguen disponibles."); }
            catch (Exception e) { status.setText("No pude completar el borrado de la clave."); }
        }));
        form.addView(label("Modelo preferido (los demás se prueban si hay errores de red, 429 o 503)"));
        Spinner model = spinner(GeminiProtocol.MODELS, app.settings.model()); form.addView(model);
        form.addView(label("Moneda predeterminada")); Spinner currency = spinner(Validation.CURRENCIES, app.settings.currency()); form.addView(currency);
        form.addView(button("Guardar ajustes", () -> {
            try { app.settings.save((String) model.getSelectedItem(), (String) currency.getSelectedItem()); status.setText("Modelo y moneda guardados."); }
            catch (Exception e) { status.setText("No pude guardar los ajustes."); }
        }));
    }
}
