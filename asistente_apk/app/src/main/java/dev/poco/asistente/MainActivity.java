package dev.poco.asistente;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import dev.poco.asistente.core.GeminiProtocol;
import dev.poco.asistente.core.Validation;
import dev.poco.asistente.bridge.BridgeService;
import dev.poco.asistente.data.AppSettings;
import dev.poco.asistente.ui.Icons;
import dev.poco.asistente.ui.Palette;
import dev.poco.asistente.ui.Views;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Native views only. Notification access is granted separately by Android.
 * Look: logs/delegado/diseno_asistente.md (Gemini), Font Awesome Free icons, pieces in ui/Views.
 */
public final class MainActivity extends Activity {
    private static final String[] SCREENS = {"Chat", "Resumen", "Registro", "Ajustes"};
    private static final String[] SCREEN_ICONS = {Icons.COMMENTS, Icons.CHART_PIE, Icons.CLOCK, Icons.GEAR};
    private AssistantApplication app;
    private Views ui;
    private Palette color;
    private LinearLayout body, tabs, bubbles;
    private TextView progress;
    private ScrollView chatScroll;
    private EditText input;
    private TextView send;
    private TextView messagingLog;
    private boolean readingLog;
    private Switch bridgeToggle, noticeToggle, phoneToggle;
    private TextView phoneStatus;
    private String screen = "Chat", draft = "", shownChat = null;
    private final Runnable observer = this::refreshChat;
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        app = (AssistantApplication) getApplication();
        ui = new Views(this); color = ui.palette;
        getWindow().setStatusBarColor(color.background); getWindow().setNavigationBarColor(color.surface);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color.background);
        // Android 15/target 35 enforces edge-to-edge; consume status/navigation/IME insets.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        View line = new View(this); line.setBackgroundColor(color.divider);
        root.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        tabs = new LinearLayout(this); tabs.setBackgroundColor(color.surface);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, ui.dp(56)));
        setContentView(root); root.requestApplyInsets();
        if (savedState != null) screen = savedState.getString("screen", "Chat");
        show(screen);
        if (app.settings.bridgeEnabled()) {
            try { startForegroundService(new Intent(this, BridgeService.class)); }
            catch (RuntimeException e) { app.settings.setBridgeEnabled(false); app.bridgeLog("Fallido: no se pudo iniciar el servicio"); }
        }
    }
    @Override protected void onStart() { super.onStart(); app.listen(observer); refreshChat(); }
    @Override protected void onResume() { super.onResume(); refreshPhoneStatus(); }
    @Override protected void onStop() { app.unlisten(observer); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("screen", screen); // no key or chat copied to Bundle
    }

    // ---- small pieces in the colors of the app

    private TextView label(String text) {
        TextView view = ui.text(text, 14, color.muted, false); view.setPadding(0, ui.dp(4), 0, ui.dp(4)); return view;
    }
    /** A 48dp button: filled in the brand color, or outlined for secondary actions. */
    private TextView button(String text, boolean primary, Runnable action) {
        TextView b = ui.text(text, 15, primary ? color.onBrand : color.brand, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable shape = ui.rounded(primary ? color.brand : color.surface, 24);
        if (!primary) shape.setStroke(ui.dp(1), color.brand);
        b.setBackground(shape);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(48));
        params.topMargin = ui.dp(8);
        b.setLayoutParams(params);
        return b;
    }
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextColor(color.text); e.setHintTextColor(color.muted); e.setTextSize(15);
        GradientDrawable shape = ui.rounded(color.background, 12); shape.setStroke(ui.dp(1), color.divider);
        e.setBackground(shape); e.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = ui.dp(8);
        e.setLayoutParams(params);
        return e;
    }
    private Switch toggle(String text) {
        Switch s = new Switch(this); s.setText(text); s.setTextColor(color.text); s.setTextSize(15);
        s.setPadding(0, ui.dp(8), 0, ui.dp(8)); return s;
    }
    private ScrollView page(LinearLayout content) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return scroll;
    }
    private TextView title(String text) {
        TextView t = ui.text(text, 20, color.text, true); t.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(6)); return t;
    }

    private void show(String name) {
        if (input != null) draft = input.getText().toString();
        screen = name; body.removeAllViews(); input = null; bubbles = null; shownChat = null; messagingLog = null; bridgeToggle = null; noticeToggle = null; phoneToggle = null; phoneStatus = null;
        tabs.removeAllViews();
        for (int i = 0; i < SCREENS.length; i++) {
            String target = SCREENS[i];
            tabs.addView(ui.tab(SCREEN_ICONS[i], target, target.equals(name), v -> show(target)), new LinearLayout.LayoutParams(0, -1, 1));
        }
        if (name.equals("Chat")) chat(); else if (name.equals("Resumen")) summary();
        else if (name.equals("Registro")) messaging(); else settings();
    }

    // ---- Chat: bubbles, "pensando…", rounded input and a round send button

    private void chat() {
        body.addView(title("Asistente"));
        bubbles = new LinearLayout(this); bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
        chatScroll = new ScrollView(this); chatScroll.setFillViewport(true); chatScroll.addView(bubbles);
        body.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        progress = ui.text("", 13, color.muted, false);
        progress.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4));
        body.addView(progress);

        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8));
        TextView stop = ui.icon(Icons.STOP, 14, color.negative, false);
        stop.setOnClickListener(v -> app.stop()); stop.setContentDescription("Parar");
        bar.addView(stop, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        input = new EditText(this); input.setHint("Escribí un gasto, tu peso o una pregunta…");
        input.setTextColor(color.text); input.setHintTextColor(color.muted); input.setTextSize(15);
        input.setMinLines(1); input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)}); input.setText(draft); input.setSaveEnabled(false);
        GradientDrawable shape = ui.rounded(color.surface, 24); shape.setStroke(ui.dp(1), color.divider);
        input.setBackground(shape); input.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
        inputParams.leftMargin = ui.dp(4); inputParams.rightMargin = ui.dp(8);
        bar.addView(input, inputParams);
        send = ui.roundButton(Icons.PAPER_PLANE, v -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty() || app.busy()) return;
            input.setText(""); draft = ""; app.send(message);
        }, "Enviar");
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        body.addView(bar);
        refreshChat();
    }
    /** The transcript is "David: …", "Asistente: …" and "Sistema: …" entries, each starting a line. */
    private static List<String[]> entries(String transcript) {
        List<String[]> list = new ArrayList<>();
        for (String line : transcript.split("\n", -1)) {
            String who = line.startsWith("David: ") ? "David" : line.startsWith("Asistente: ") ? "Asistente"
                    : line.startsWith("Sistema: ") ? "Sistema" : null;
            if (who != null) list.add(new String[]{who, line.substring(who.length() + 2)});
            else if (!list.isEmpty()) list.get(list.size() - 1)[1] += "\n" + line;
            else if (!line.trim().isEmpty()) list.add(new String[]{"Sistema", line});
        }
        for (String[] e : list) e[1] = e[1].trim();
        return list;
    }
    private void refreshChat() {
        refreshPhoneStatus();
        if (bridgeToggle != null) bridgeToggle.setChecked(app.settings.bridgeEnabled());
        if (noticeToggle != null) noticeToggle.setChecked(app.settings.whatsappEnabled());
        if (screen.equals("Registro")) { refreshMessaging(); return; }
        if (!screen.equals("Chat") || bubbles == null) return;
        String transcript = app.chat();
        if (!transcript.equals(shownChat)) {
            shownChat = transcript; bubbles.removeAllViews();
            int width = chatScroll.getWidth() > 0 ? chatScroll.getWidth() : getResources().getDisplayMetrics().widthPixels;
            for (String[] e : entries(transcript)) {
                if (e[1].isEmpty()) continue;
                if (e[0].equals("Sistema")) {
                    TextView note = ui.text(e[1], 12, color.muted, false); note.setGravity(Gravity.CENTER);
                    note.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), ui.dp(6)); bubbles.addView(note);
                } else bubbles.addView(ui.bubble(e[1], null, e[0].equals("David"), width));
            }
            chatScroll.post(() -> { if (chatScroll != null) chatScroll.fullScroll(View.FOCUS_DOWN); });
        }
        send.setEnabled(!app.busy()); send.setAlpha(app.busy() ? 0.4f : 1f);
        progress.setText(app.busy() ? "Gemini está pensando…" : "");
    }

    // ---- Registro

    private void messaging() {
        body.addView(title("Registro de acciones y WhatsApp"));
        LinearLayout content = new LinearLayout(this); page(content);
        LinearLayout card = ui.card(); content.addView(card);
        card.addView(ui.cardTitle(Icons.WHATSAPP, "Mensajes y puente", true));
        messagingLog = ui.text("Leyendo registro…", 13, color.text, false);
        messagingLog.setTextIsSelectable(true); card.addView(messagingLog);
        content.addView(button("Actualizar registro", false, this::refreshMessaging));
        content.addView(button("Parar y vaciar la cola", false, app::stop));
        refreshMessaging();
    }
    private void refreshMessaging() {
        if (messagingLog == null || readingLog) return;
        readingLog = true; TextView target = messagingLog;
        app.io.execute(() -> {
            String result;
            try { result = app.db.history(java.time.LocalDate.now(dev.poco.asistente.core.Reminders.ZONE).toString()) + "\n" + app.db.messagingHistory(); }
            catch (RuntimeException e) { result = "No pude leer el registro de WhatsApp."; }
            String display = result;
            app.main.post(() -> {
                readingLog = false;
                if (!isDestroyed() && target == messagingLog && target.isAttachedToWindow()) target.setText(display);
                else if (!isDestroyed() && messagingLog != null) refreshMessaging();
            });
        });
    }

    // ---- Resumen: the summary text, sorted into cards

    private void summary() {
        body.addView(title("Resumen"));
        LinearLayout content = new LinearLayout(this); page(content);
        content.addView(label("Leyendo datos locales…"));
        app.worker.execute(() -> {
            String result = app.core.summary();
            app.main.post(() -> { if (!isDestroyed() && content.isAttachedToWindow()) summaryCards(content, result); });
        });
    }
    private static long amount(String text) {
        // "₲ 25.000" or "$ 3.500,50": the whole part, enough to size the bars
        String whole = text.contains(",") ? text.substring(0, text.lastIndexOf(',')) : text;
        String digits = whole.replaceAll("[^0-9]", "");
        try { return digits.isEmpty() ? 0 : Long.parseLong(digits); } catch (NumberFormatException e) { return 0; }
    }
    private void summaryCards(LinearLayout content, String text) {
        content.removeAllViews();
        List<String> money = new ArrayList<>(), spending = new ArrayList<>(), budgets = new ArrayList<>(),
                weight = new ArrayList<>(), training = new ArrayList<>(), goals = new ArrayList<>(), other = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("Registros recientes")) continue;
            if (l.startsWith("Saldo") || l.startsWith("Sin movimientos")) money.add(l);
            else if (l.startsWith("Gasto / ")) spending.add(l.substring(8));
            else if (l.startsWith("Presupuesto") || l.startsWith("Sin presupuestos")) budgets.add(l.replace("Presupuesto / ", ""));
            else if (l.contains("peso")) weight.add(l);
            else if (l.startsWith("Entrenos")) training.add(l);
            else if (l.startsWith("Meta")) goals.add(l.replace("Meta: ", ""));
            else other.add(l);
        }
        card(content, Icons.WALLET, "Finanzas del mes", money, color.text);
        if (!spending.isEmpty()) {
            LinearLayout c = ui.card(); content.addView(c);
            c.addView(ui.cardTitle(Icons.ARROW_DOWN, "Gastos por categoría", false));
            long top = 1;
            for (String s : spending) top = Math.max(top, amount(s.substring(s.indexOf(':') + 1)));
            for (String s : spending) {
                int colon = s.indexOf(':');
                LinearLayout row = new LinearLayout(this);
                TextView name = ui.text(s.substring(0, colon), 14, color.text, false);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(ui.text(s.substring(colon + 1).trim(), 14, color.text, true));
                c.addView(row);
                View bar = ui.bar(amount(s.substring(colon + 1)) / (float) top, color.negative);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(8));
                p.topMargin = ui.dp(4); p.bottomMargin = ui.dp(10); c.addView(bar, p);
            }
        }
        card(content, Icons.PIGGY_BANK, "Presupuestos", budgets, color.text);
        card(content, Icons.WEIGHT_SCALE, "Peso", weight, color.text);
        card(content, Icons.DUMBBELL, "Entrenos de la semana", training, color.text);
        if (!goals.isEmpty()) card(content, Icons.BULLSEYE, "Metas", goals, color.text);
        if (!other.isEmpty()) card(content, Icons.INFO, "Más", other, color.muted);
        memoryCard(content);
        content.addView(button("Actualizar", false, () -> show("Resumen")));
    }
    private void memoryCard(LinearLayout content) {
        LinearLayout card = ui.card(); content.addView(card);
        card.addView(ui.cardTitle(Icons.INFO, "Memoria", false));
        TextView loading = label("Leyendo libreta…"); card.addView(loading);
        app.worker.execute(() -> {
            try {
                List<dev.poco.asistente.core.Store.Row> memories = app.db.memories();
                String reminders = dev.poco.asistente.core.Reminders.list(app.db.reminders());
                app.main.post(() -> {
                    if (isDestroyed() || !card.isAttachedToWindow()) return;
                    card.removeView(loading);
                    if (memories.isEmpty()) card.addView(label("La libreta está vacía."));
                    for (dev.poco.asistente.core.Store.Row row : memories) {
                        TextView text = label(row.data.optString("texto") + "\n" + row.data.optString("categoria") + " · " + row.id);
                        text.setTextIsSelectable(true); card.addView(text);
                        card.addView(button("Borrar entrada", false, () -> proposeForget(row.id, row.data.optString("texto"))));
                    }
                    card.addView(button("Olvidar conversación", false, () -> new android.app.AlertDialog.Builder(this)
                            .setTitle("Olvidar conversación").setMessage("Borrar el historial usado por Gemini y el chat visible. La libreta y tus registros se conservan.")
                            .setNegativeButton("Cancelar", null).setPositiveButton("Borrar", (dialog, which) -> { app.forgetConversation(); show("Resumen"); }).show()));
                    LinearLayout rc = ui.card(); content.addView(rc); rc.addView(ui.cardTitle(Icons.CLOCK, "Recordatorios", false));
                    TextView rt = label(reminders); rt.setTextIsSelectable(true); rc.addView(rt);
                });
            } catch (Exception e) { app.main.post(() -> loading.setText("No pude leer la memoria.")); }
        });
    }
    private void proposeForget(String id, String text) {
        long ticket = app.core.cancellationToken();
        app.worker.execute(() -> {
            String proposal = app.core.receive("olvidá " + id, ticket);
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("SÍ ([A-F0-9]{16})").matcher(proposal);
            String confirm = code.find() ? "SÍ " + code.group(1) : null;
            app.main.post(() -> {
                if (isDestroyed()) return;
                android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this).setTitle("Borrar memoria")
                        .setMessage(text + "\n\n" + proposal).setNegativeButton("Cancelar", (d, w) -> app.stop());
                if (confirm != null) dialog.setPositiveButton("Confirmar borrado", (d, w) -> {
                    long confirmationTicket = app.core.cancellationToken();
                    app.worker.execute(() -> {
                        String result = app.core.receive(confirm, confirmationTicket);
                        app.main.post(() -> { if (!isDestroyed()) { new android.app.AlertDialog.Builder(this).setMessage(result).setPositiveButton("Aceptar", null).show(); show("Resumen"); } });
                    });
                });
                dialog.show();
            });
        });
    }
    private void card(LinearLayout content, String icon, String title, List<String> lines, int textColor) {
        LinearLayout c = ui.card(); content.addView(c);
        c.addView(ui.cardTitle(icon, title, false));
        if (lines.isEmpty()) { c.addView(ui.text("Sin datos todavía.", 14, color.muted, false)); return; }
        for (String l : lines) {
            TextView t = ui.text(l, 15, textColor, false); t.setTextIsSelectable(true);
            t.setPadding(0, ui.dp(2), 0, ui.dp(2)); c.addView(t);
        }
    }

    // ---- Ajustes in cards

    private Spinner spinner(String[] values, String selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Arrays.asList(values).indexOf(selected))); return spinner;
    }
    private void settings() {
        body.addView(title("Ajustes"));
        LinearLayout form = new LinearLayout(this); page(form);
        phoneCard(form);

        LinearLayout gemini = ui.card(); form.addView(gemini);
        gemini.addView(ui.cardTitle(Icons.KEY, "Clave de Gemini", false));
        TextView status = ui.text(app.settings.vault.hasKey() ? "Clave cifrada guardada." : "Sin clave guardada.", 14, color.text, false);
        gemini.addView(status);
        EditText key = field("Pegá la clave de AI Studio"); key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        key.setSaveEnabled(false); key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)}); gemini.addView(key);
        gemini.addView(button("Guardar clave", true, () -> {
            String value = key.getText().toString().trim(); key.setText("");
            try { app.settings.vault.save(value); report(status, "Clave cifrada guardada."); }
            catch (Exception e) { report(status, "No pude guardar la clave. Revisá el formato y volvé a pegarla."); }
        }));
        gemini.addView(button("Borrar clave", false, () -> {
            app.stop(); key.setText("");
            try { app.settings.vault.clear(); report(status, "Clave borrada. Las reglas locales siguen disponibles."); }
            catch (Exception e) { report(status, "No pude completar el borrado de la clave."); }
        }));

        LinearLayout prefs = ui.card(); form.addView(prefs);
        prefs.addView(ui.cardTitle(Icons.MICROCHIP, "Modelo y moneda", false));
        prefs.addView(label("Modelo preferido (los demás se prueban si hay errores de red, 429 o 503)"));
        Spinner model = spinner(GeminiProtocol.MODELS, app.settings.model()); prefs.addView(model);
        prefs.addView(label("Moneda predeterminada"));
        Spinner currency = spinner(Validation.CURRENCIES, app.settings.currency()); prefs.addView(currency);
        prefs.addView(button("Guardar ajustes", true, () -> {
            try { app.settings.save((String) model.getSelectedItem(), (String) currency.getSelectedItem()); report(status, "Modelo y moneda guardados."); }
            catch (Exception e) { report(status, "No pude guardar los ajustes."); }
        }));

        LinearLayout audioPrefs = ui.card(); form.addView(audioPrefs);
        audioPrefs.addView(label("Responder con audio (WhatsApp)"));
        String[] audioLabels = {"Nunca", "Cuando David manda audio", "Siempre"};
        String[] audioModes = {"nunca", "audio", "siempre"};
        int audioSelected = java.util.Arrays.asList(audioModes).indexOf(app.settings.audioReplyMode());
        Spinner audioMode = spinner(audioLabels, audioLabels[audioSelected < 0 ? 1 : audioSelected]); audioPrefs.addView(audioMode);
        TextView audioStatus = label("También se conserva la respuesta escrita."); audioPrefs.addView(audioStatus);
        audioPrefs.addView(button("Guardar audio", true, () -> {
            try { app.settings.saveAudioReplyMode(audioModes[audioMode.getSelectedItemPosition()]); report(audioStatus, "Preferencia de audio guardada."); }
            catch (Exception e) { report(audioStatus, "No pude guardar la preferencia de audio."); }
        }));

        LinearLayout summaries = ui.card(); form.addView(summaries);
        summaries.addView(ui.cardTitle(Icons.CLOCK, "Resúmenes por WhatsApp", false));
        Switch daily = toggle("Resumen diario"); daily.setChecked(app.settings.dailySummary()); summaries.addView(daily);
        Switch weekly = toggle("Resumen semanal los domingos"); weekly.setChecked(app.settings.weeklySummary()); summaries.addView(weekly);
        EditText hour = field("Hora local HH:mm"); hour.setSingleLine(true); hour.setText(app.settings.summaryTime()); summaries.addView(hour);
        TextView summaryStatus = label("Hora de Paraguay. Silencio de 23:00 a 07:00, salvo recordatorios que pediste a esa hora."); summaries.addView(summaryStatus);
        summaries.addView(button("Guardar resúmenes", true, () -> {
            try { app.settings.saveSummaries(daily.isChecked(), weekly.isChecked(), hour.getText().toString().trim()); report(summaryStatus, "Resúmenes guardados."); }
            catch (Exception e) { report(summaryStatus, e.getMessage()); }
        }));
        Switch morning = toggle("Buen día a las 7:30 (recordatorios, gastos de ayer, presupuesto)"); morning.setChecked(app.settings.morningBrief()); summaries.addView(morning);
        morning.setOnCheckedChangeListener((view, checked) -> { app.settings.setMorningBrief(checked); say(checked ? "Buen día activado." : "Buen día desactivado."); });
        Switch nudges = toggle("Avisos: gasto fuera de lo normal y gym"); nudges.setChecked(app.settings.nudges()); summaries.addView(nudges);
        nudges.setOnCheckedChangeListener((view, checked) -> { app.settings.setNudges(checked); say(checked ? "Avisos activados." : "Avisos desactivados."); });

        LinearLayout whatsapp = ui.card(); form.addView(whatsapp);
        whatsapp.addView(ui.cardTitle(Icons.WHATSAPP, "WhatsApp (puente QR)", true));
        whatsapp.addView(label("Termux corre el puente vinculado por QR al WhatsApp del Segundo espacio. Solo se acepta el remitente configurado en Termux."));
        TextView bridgeStatus = ui.text("", 14, color.text, false); whatsapp.addView(bridgeStatus);
        Switch proToggle = toggle("Usar mi Google AI Pro"); proToggle.setChecked(app.settings.proEnabled()); whatsapp.addView(proToggle);
        TextView proStatus = label(app.settings.proEnabled() ? "Pro preferido para charla y búsquedas. Si no responde, uso el respaldo." : "Pro apagado; uso el modelo gratis y Tavily.");
        whatsapp.addView(proStatus);
        proToggle.setOnCheckedChangeListener((view, checked) -> {
            if (checked == app.settings.proEnabled()) return;
            try {
                app.settings.setProEnabled(checked);
                if (!checked) app.core.clearChatMode();
                report(proStatus, checked ? "Pro preferido. Termux necesita una sesión de Antigravity iniciada." : "Pro apagado; uso el modelo gratis y Tavily.");
            } catch (RuntimeException e) { view.setChecked(app.settings.proEnabled()); report(proStatus, "No pude guardar el ajuste Pro."); }
        });
        bridgeToggle = toggle("Puente de WhatsApp");
        bridgeToggle.setChecked(app.settings.bridgeEnabled()); whatsapp.addView(bridgeToggle);
        bridgeToggle.setOnCheckedChangeListener((view, checked) -> {
            if (checked == app.settings.bridgeEnabled()) return;
            try {
                if (checked) {
                    app.settings.bridgeToken();
                    app.configureWhatsapp(false, app.settings.whatsappName(), app.settings.whatsappPhone());
                    app.settings.setBridgeEnabled(true);
                    startForegroundService(new Intent(this, BridgeService.class));
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
                } else {
                    app.settings.setBridgeEnabled(false); app.stop(); stopService(new Intent(this, BridgeService.class));
                }
                report(bridgeStatus, checked ? "Puente habilitado. Consultá Registro para comprobar el inicio." : "Puente apagado; cola y confirmaciones canceladas.");
            } catch (Exception e) {
                app.settings.setBridgeEnabled(false); stopService(new Intent(this, BridgeService.class));
                view.setChecked(false); report(bridgeStatus, "No pude iniciar el puente. Revisá Registro.");
            }
        });
        whatsapp.addView(button("Copiar configuración del puente", false, () -> {
            try {
                String configuration = new org.json.JSONObject().put("puerto", AppSettings.BRIDGE_PORT).put("token", app.settings.bridgeToken()).toString();
                android.content.ClipData clip = android.content.ClipData.newPlainText("Configuración privada del puente", configuration);
                if (Build.VERSION.SDK_INT >= 33) {
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true); clip.getDescription().setExtras(extras);
                }
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
                report(bridgeStatus, "Copiado: pegá puerto y token en config.json de Termux y agregá tu remitente autorizado.");
            } catch (Exception e) { report(bridgeStatus, "No pude copiar la configuración cifrada."); }
        }));

        LinearLayout notices = ui.card(); form.addView(notices);
        notices.addView(ui.cardTitle(Icons.SHIELD, "Lector de avisos (anterior)", false));
        notices.addView(label("Apagado por defecto. No recibe mensajes en segundo plano en el Segundo espacio. No lo uses junto al puente QR."));
        EditText owner = field("Nombre exacto del contacto, por ejemplo Yo David"); owner.setSingleLine(true);
        owner.setText(app.settings.whatsappName()); owner.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)}); notices.addView(owner);
        EditText phone = field("Número personal E.164 (opcional): +595…"); phone.setSingleLine(true);
        phone.setInputType(InputType.TYPE_CLASS_PHONE); phone.setText(app.settings.whatsappPhone()); notices.addView(phone);
        TextView whatsappStatus = ui.text("", 14, color.text, false); notices.addView(whatsappStatus);
        Switch enabled = toggle("Responder desde avisos (anterior)"); noticeToggle = enabled;
        enabled.setChecked(app.settings.whatsappEnabled()); notices.addView(enabled);
        enabled.setOnCheckedChangeListener((view, checked) -> {
            if (checked == app.settings.whatsappEnabled()) return;
            if (checked && app.settings.bridgeEnabled()) { view.setChecked(false); report(whatsappStatus, "Apagá primero el puente QR."); return; }
            try {
                // Turning off always works, even if an unfinished field is currently invalid.
                app.configureWhatsapp(checked, checked ? owner.getText().toString() : app.settings.whatsappName(),
                        checked ? phone.getText().toString().trim() : app.settings.whatsappPhone());
                report(whatsappStatus, checked ? "Respuestas habilitadas. Falta conceder el acceso si aún no lo diste." : "Respuestas desactivadas; cola cancelada.");
            } catch (Exception e) { enabled.setChecked(app.settings.whatsappEnabled()); report(whatsappStatus, e.getMessage()); }
        });
        notices.addView(button("Guardar remitente de WhatsApp", false, () -> {
            try {
                app.configureWhatsapp(enabled.isChecked(), owner.getText().toString(), phone.getText().toString().trim());
                report(whatsappStatus, "Remitente guardado. Se cancelaron los pedidos anteriores.");
            } catch (Exception e) { report(whatsappStatus, e.getMessage()); }
        }));
        notices.addView(button("Dar acceso a notificaciones", false, () -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (RuntimeException e) { report(whatsappStatus, "Abrí Ajustes del sistema → Acceso a notificaciones → Asistente."); }
        }));

        LinearLayout calls = ui.card(); form.addView(calls);
        calls.addView(ui.cardTitle(Icons.PHONE, "Llamadas", false));
        calls.addView(label("Hablá con la asistente por voz (Gemini Live). Si lo activás, los recordatorios te suenan como una llamada."));
        TextView callStatus = ui.text("", 14, color.text, false); calls.addView(callStatus);
        Switch ringReminders = toggle("Recordatorios por llamada");
        ringReminders.setChecked(app.settings.callReminders());
        ringReminders.setOnCheckedChangeListener((view, checked) -> { app.settings.setCallReminders(checked); say(checked ? "Los recordatorios te van a llamar." : "Los recordatorios ya no llaman."); });
        calls.addView(ringReminders);
        calls.addView(button("Llamar a la asistente", true, () -> dev.poco.asistente.call.CallActivity.callNow(this)));
        calls.addView(button("Probar: que me llame en 10 segundos", false, () -> {
            report(callStatus, "En 10 segundos te llama. Podés bloquear la pantalla.");
            app.main.postDelayed(() -> dev.poco.asistente.call.CallLauncher.ring(app, "Esta es una llamada de prueba"), 10_000);
        }));
        if (!dev.poco.asistente.call.CallLauncher.canRingFullScreen(this)) {
            callStatus.setText("Para que la llamada aparezca con la pantalla bloqueada, permití las notificaciones de pantalla completa.");
            calls.addView(button("Permitir llamada en pantalla completa", false, () -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + getPackageName()))); }
                catch (RuntimeException e) { report(callStatus, "Abrí Ajustes → Apps → Asistente → Notificaciones de pantalla completa."); }
            }));
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 43);

        LinearLayout privacy = ui.card(); form.addView(privacy);
        privacy.addView(ui.cardTitle(Icons.SHIELD, "Privacidad", false));
        privacy.addView(label("Gemini recibe tu mensaje, los últimos diez intercambios recortados, la libreta y un resumen local. En la cuota gratis Google puede usar esos datos para mejorar sus productos. Las reglas funcionan sin clave."));
        privacy.addView(label("WhatsApp por QR es una integración no oficial: puede romperse si WhatsApp cambia, y existe riesgo de bloqueo del número del bot."));

        LinearLayout about = ui.card(); form.addView(about);
        about.addView(ui.cardTitle(Icons.INFO, "Acerca de", false));
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        about.addView(label("Asistente " + version + " — firmada con tu clave privada."));
        about.addView(label(Icons.CREDIT));
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    private static String yes(boolean on) { return on ? "permitido" : "sin permiso"; }
    /** Every settings action ends with a visible toast: the status labels sit above the buttons, out of sight. */
    private void say(String text) {
        android.widget.Toast.makeText(this, text == null || text.isEmpty() ? "No se pudo completar." : text, android.widget.Toast.LENGTH_LONG).show();
    }
    private void report(TextView label, String text) { if (label != null) label.setText(text); say(text); }
    /** Android shows no dialog for a permission already granted: say so instead of doing nothing. */
    private void ask(int code, String... permissions) {
        boolean all = true;
        for (String p : permissions) all &= granted(p);
        if (all) say("Ya está permitido."); else requestPermissions(permissions, code);
    }
    private void refreshPhoneStatus() {
        if (phoneToggle != null) phoneToggle.setChecked(app.settings.phoneEnabled());
        if (phoneStatus == null) return;
        boolean notices = getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(
                new android.content.ComponentName(this, dev.poco.asistente.phone.PhoneNotifications.class));
        boolean calendar = granted(android.Manifest.permission.READ_CALENDAR), events = granted(android.Manifest.permission.WRITE_CALENDAR);
        boolean contacts = granted(android.Manifest.permission.READ_CONTACTS), calls = granted(android.Manifest.permission.CALL_PHONE);
        boolean dnd = getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted();
        phoneStatus.setText("Control del teléfono: " + (app.settings.phoneEnabled() ? "encendido" : "APAGADO")
                + "\nAccesibilidad: " + (dev.poco.asistente.phone.PhoneAccessibility.connected() ? "conectada" : "sin conectar")
                + "\nNotificaciones: " + (notices ? "permitidas" : "sin permiso") + "\nAgenda: " + (calendar ? "lectura permitida" : "sin permiso")
                + "\nCrear eventos: " + yes(events) + "\nContactos: " + yes(contacts) + "\nLlamadas: " + yes(calls)
                + "\nNo molestar: " + yes(dnd) + "\nBrillo: " + yes(Settings.System.canWrite(this))
                + "\nNúmero del bot: " + (app.settings.phoneBotNumber().isEmpty() ? "sin cargar" : "guardado")
                + "\nBúsquedas este mes: " + app.settings.searchesThisMonth());
    }
    private void phoneCard(LinearLayout form) {
        LinearLayout card = ui.card(); form.addView(card);
        card.addView(ui.cardTitle(Icons.PHONE, "Control del teléfono", false));
        card.addView(label("Abrir apps, reloj, música y lecturas. Llamadas, borradores de WhatsApp, eventos y ajustes piden confirmación. PARAR lo apaga. No lee ni toca la pantalla."));
        phoneToggle = toggle("Control del teléfono"); phoneToggle.setChecked(app.settings.phoneEnabled()); card.addView(phoneToggle);
        phoneToggle.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled == app.settings.phoneEnabled()) return;
            if (enabled) {
                // Invalidate work admitted while control was off before enabling from the visible app.
                app.stop(); app.settings.setPhoneEnabled(true); app.changed();
            } else app.stop();
            refreshPhoneStatus(); say(enabled ? "Control del teléfono encendido." : "Control del teléfono apagado.");
        });
        phoneStatus = label(""); card.addView(phoneStatus);
        card.addView(button("Accesibilidad", false, () -> phoneSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        card.addView(button("Notificaciones", false, () -> phoneSetting(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        card.addView(button("Permitir lectura de agenda", false, () -> ask(44, android.Manifest.permission.READ_CALENDAR)));
        card.addView(button("Permitir contactos y llamadas", false, () -> ask(45, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE)));
        card.addView(button("Permitir crear eventos", false, () -> ask(46, android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)));
        card.addView(button("Acceso a No molestar", false, () -> phoneSetting(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        card.addView(button("Modificar brillo del sistema", false, () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException e) { report(phoneStatus, "Abrí Modificar ajustes del sistema desde los permisos de Asistente."); }
        }));
        card.addView(label("Número del bot (se bloqueará como destinatario). Obligatorio para preparar WhatsApp. Solo móviles paraguayos y espacio principal."));
        EditText botNumber = field("+5959…"); botNumber.setSingleLine(true); botNumber.setText(app.settings.phoneBotNumber()); card.addView(botNumber);
        card.addView(button("Guardar número del bot", false, () -> {
            try { app.stop(); app.settings.savePhoneBotNumber(botNumber.getText().toString().trim()); refreshPhoneStatus(); say("Número guardado. Volvé a encender Control del teléfono."); }
            catch (RuntimeException e) { report(phoneStatus, e.getMessage()); }
        }));
        card.addView(label("Activá «Asistente: abrir apps» y «Asistente: leer avisos». Si Android lo bloquea, permití ajustes restringidos en la información de Asistente."));
        refreshPhoneStatus();
    }
    private void phoneSetting(String action) {
        try { startActivity(new Intent(action)); }
        catch (RuntimeException e) { if (phoneStatus != null) report(phoneStatus, "Abrí ese permiso desde Ajustes del teléfono → Asistente."); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); refreshPhoneStatus();
        boolean all = results.length > 0;
        for (int r : results) all &= r == android.content.pm.PackageManager.PERMISSION_GRANTED;
        say(all ? "Permiso dado." : "No se dio el permiso. Si Android ya no pregunta, dalo en Ajustes del teléfono → Apps → Asistente → Permisos.");
    }
}
