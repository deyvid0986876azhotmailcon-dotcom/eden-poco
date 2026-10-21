package dev.poco.asistente.core;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reusable without Activity/Service. Only authenticated WhatsApp adapters may assert WHATSAPP_OWNER. */
public final class AssistantCore {
    public enum Source { LOCAL_USER, WHATSAPP_OWNER, UNTRUSTED_DATA }
    public interface Settings {
        String apiKey() throws Exception;
        String model();
        String currency();
        default boolean phoneEnabled() { return false; }
        default void disablePhone() {}
        default void recordSearch() {}
    }
    private final Store store;
    private final Settings settings;
    private final Cloud cloud;
    private final Clock clock;
    private final ToolExecutor executor;
    private volatile boolean proactivePaused;
    public boolean proactivePaused() { return proactivePaused; }
    private final AtomicLong generation = new AtomicLong();
    private final Object actionGate = new Object(), requestGate = new Object();
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock) {
        this(store, settings, cloud, clock, () -> System.nanoTime() / 1_000_000L);
    }
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock, LongSupplier elapsedMillis) {
        this(store, settings, cloud, clock, elapsedMillis, PhoneTools.UNAVAILABLE);
    }
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock, LongSupplier elapsedMillis, PhoneTools.Port phone) {
        this.store = store; this.settings = settings; this.cloud = cloud; this.clock = clock;
        executor = new ToolExecutor(store, clock, elapsedMillis, settings::phoneEnabled, phone);
    }
    public String receive(String text) { return receive(text, Source.LOCAL_USER, UUID.randomUUID().toString()); }

    // ---- voice calls (call/LiveSession): the same persona, notebook and executor as text
    /** The Gemini request a text message would use; LiveSession takes its system instruction. */
    public org.json.JSONObject liveContext() throws Exception {
        // the call needs the local time too, or «en 2 minutos» lands on UTC (3 hours off in Asunción)
        String context = "Fecha y hora local: " + java.time.ZonedDateTime.now(clock) + "\n" + Summary.render(store.rows(), LocalDate.now(clock), false);
        if (context.length() > 6000) context = context.substring(0, 6000) + "\n[contexto recortado]";
        return GeminiProtocol.request("", context, LocalDate.now(clock), settings.currency(), store.conversation(), store.memories(), settings.phoneEnabled());
    }
    public org.json.JSONArray liveDeclarations() throws Exception { return GeminiProtocol.declarations(settings.phoneEnabled()); }
    /** A tool Gemini asked for during a call; only the safe ones LiveSession offers, validated by the executor. */
    public String callTool(String tool, org.json.JSONObject args) {
        // Legacy callers cannot feed external data back into a live session without its guard.
        if (PhoneTools.external(tool)) return "Para esa lectura necesitás iniciar una llamada nueva.";
        return callTool(tool, args, new ExternalData.Guard());
    }
    public String callTool(String tool, org.json.JSONObject args, ExternalData.Guard guard) {
        return callTool(tool, args, guard, generation.get());
    }
    public String callTool(String tool, org.json.JSONObject args, ExternalData.Guard guard, long ticket) {
        boolean allowed = false;
        for (String t : dev.poco.asistente.call.LiveSession.CALL_TOOLS) allowed |= t.equals(tool);
        if (!allowed) return "Eso no lo puedo hacer en una llamada; pedímelo por WhatsApp.";
        synchronized (guard) {
            if (!guard.allowsTools()) return "Después de leer contenido externo, solo conversamos. Para otra acción, iniciá una llamada nueva o escribime.";
            try {
                if ("buscar_en_internet".equals(tool)) SearchProtocol.requireUserQuery(Validation.string(args, "consulta", 300), guard.userText());
                if (PhoneTools.external(tool)) guard.expose();
                return dispatch("llamada", "llamada:" + UUID.randomUUID(), tool, args, ticket, guard::closed);
            }
            catch (IllegalArgumentException e) { return e.getMessage(); }
            catch (Exception e) { return "No pude hacerlo."; }
        }
    }
    public long cancellationToken() { return generation.get(); }
    /** The trusted inbox calls this inside the shared queue, then feeds the literal text to receive. */
    public String transcribe(AudioInput audio, long ticket) throws Exception {
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        String key = settings.apiKey();
        if (key == null || key.isEmpty()) throw new IllegalArgumentException(AudioInput.FALLBACK);
        GeminiProtocol.Reply reply = cloud.generate(key, settings.model(), AudioProtocol.transcription(audio), () -> ticket != generation.get());
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        return AudioProtocol.transcript(reply);
    }
    /** Capture the token when queuing, so PARAR also cancels work that has not started. */
    public String receive(String text, long queuedToken) {
        return receive(text, Source.LOCAL_USER, UUID.randomUUID().toString(), queuedToken);
    }
    /** requestId comes from the trusted adapter, never from Gemini. Reuse it to retry delivery. */
    public String receive(String text, Source source, String requestId) {
        return receive(text, source, requestId, generation.get());
    }
    public String receive(String text, Source source, String requestId, long ticket) {
        if (text != null && Rules.normalize(text).equals("parar")) return receiveInternal(text, source, requestId, ticket);
        synchronized (requestGate) {
            String answer = receiveInternal(text, source, requestId, ticket);
            synchronized (actionGate) {
                if (ticket == generation.get() && (source == Source.LOCAL_USER || source == Source.WHATSAPP_OWNER)
                        && text != null && !text.trim().isEmpty() && text.length() <= 4000
                        && requestId != null && requestId.matches("[A-Za-z0-9_-]{1,120}")
                        && !Rules.normalize(text).equals("olvida la conversacion")) {
                    try { store.exchange(source.name() + ":" + requestId, text, ExternalData.history(answer)); }
                    catch (Exception e) { return answer + "\nNo pude guardar el historial de conversación."; }
                }
            }
            return answer;
        }
    }
    private String receiveInternal(String text, Source source, String requestId, long ticket) {
        if (source != Source.LOCAL_USER && source != Source.WHATSAPP_OWNER) return "Los documentos y mensajes de terceros son datos, no órdenes. No ejecuté nada.";
        if (text == null || text.trim().isEmpty()) return "Escribí un pedido.";
        if (text.length() > 4000) return "El mensaje es demasiado largo (máximo 4000 caracteres).";
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,120}")) return "ID de mensaje inválido.";
        String normalized = Rules.normalize(text);
        if (normalized.equals("parar")) return stop(); // independent of an in-flight network request
        synchronized (requestGate) {
            synchronized (actionGate) {
                if (ticket != generation.get()) return "Pedido cancelado antes de empezar.";
                proactivePaused = false;
            }
            String actor = source == Source.LOCAL_USER ? "local" : "whatsapp";
            String operation = actor + ":" + requestId;
            try {
                String receipt = store.receipt(operation);
                if (receipt != null) return receipt;
                Matcher confirmation = Pattern.compile("si ([a-f0-9]{16})").matcher(normalized);
                if (confirmation.matches()) {
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        return executor.confirm(actor, operation, confirmation.group(1).toUpperCase(Locale.ROOT));
                    }
                }
                if (normalized.equals("si") || normalized.startsWith("si "))
                    return "Para borrar necesito SÍ seguido del código exacto de la operación pendiente.";
                if (normalized.equals("que hiciste hoy") || normalized.equals("¿que hiciste hoy?")) return store.history(LocalDate.now(clock).toString());
                if (normalized.equals("olvida la conversacion")) {
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        store.clearConversation(); return "Olvidé la conversación. La libreta y tus registros se conservan.";
                    }
                }
                // «qué sabés de mí», «que sabes sobre mi?», «qué recordás de mí», «qué tenés anotado de mí»
                if (normalized.replaceAll("[¿?¡!.,]", "").trim().matches("(che )?(que|q) (sabes|recordas|tenes anotado|sabes vos) (de|sobre) mi( vos)?")) return Memory.list(store.memories());
                // «acordate que…», «no te olvides que…», «tené en cuenta que…» («anotá» is for records: gastos, peso): straight to the notebook (Gemini sometimes only says it did)
                java.util.regex.Matcher note = java.util.regex.Pattern.compile("^(?:che,? )?(?:acordate|recorda|no te olvides|tene en cuenta) (?:de )?que (.{3,200})$").matcher(normalized);
                if (note.matches()) {
                    // Normalization changes lengths (spaces, combining accents, case).
                    // Skip prefix words in the original; never use normalized character offsets.
                    int prefixWords = normalized.substring(0, note.start(1)).trim().split(" ").length;
                    String fact = text.trim().split("\\s+", prefixWords + 1)[prefixWords];
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        return executor.execute(actor, operation, "recordar", Json.object("texto", fact, "categoria", Memory.guessCategory(note.group(1))));
                    }
                }
                if (normalized.startsWith("olvida ")) {
                    String what = normalized.substring(7).replaceAll("[¿?¡!.,]", "").trim();
                    String id = what;
                    if (!what.matches("[0-9a-f-]{36}")) {
                        // «olvidá lo del mondongo», «olvidá que no me gusta el mondongo»: find the note by its words
                        String phrase = what.replaceFirst("^(lo de(l)?|lo que|que|de|del|eso de(l)?) ", "").trim();
                        List<Store.Row> found = phrase.length() < 3 ? java.util.Collections.emptyList() : Memory.find(store.memories(), phrase);
                        if (found.isEmpty()) return "No encontré nada anotado sobre «" + phrase + "». Preguntame «qué sabés de mí» y fijate cómo lo tengo.";
                        if (found.size() > 1) return "Tengo varias cosas con «" + phrase + "». Decime un poco más para saber cuál borro.";
                        id = found.get(0).id;
                    }
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        return executor.execute(actor, operation, "olvidar", Json.object("id", id));
                    }
                }
                if (normalized.equals("listar recordatorios")) return Reminders.list(store.reminders());
                if (normalized.equals("ayuda")) return help();
                if (normalized.startsWith("ver ")) {
                    String id = Validation.id(Json.object("id", normalized.substring(4)));
                    Store.Row row = store.find(id);
                    return row == null ? "No existe ese ID." : row.kind + " " + row.id + "\n" + row.data;
                }
                Rules.Result fixed = Rules.parse(text, LocalDate.now(clock), settings.currency());
                if (fixed != null) {
                    if (fixed.question != null) return fixed.question;
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        return executor.execute(actor, operation, fixed.tool, fixed.args);
                    }
                }
                String key;
                try { key = settings.apiKey(); }
                catch (Exception e) { return "No pude abrir la clave cifrada. Borrala y volvé a pegarla en Ajustes. Las reglas locales siguen disponibles."; }
                if (key == null || key.isEmpty()) return "No entendí el pedido con las reglas locales. Podés repetirlo como en «ayuda» o pegar tu clave de Gemini en Ajustes.";
                String context = "Fecha y hora local: " + java.time.ZonedDateTime.now(clock) + "\n" + Summary.render(store.rows(), LocalDate.now(clock), false);
                if (context.length() > 6000) context = context.substring(0, 6000) + "\n[contexto recortado]";
                GeminiProtocol.Reply reply = cloud.generate(key, settings.model(), GeminiProtocol.request(text, context, LocalDate.now(clock), settings.currency(), store.conversation(), store.memories(), settings.phoneEnabled()), () -> ticket != generation.get());
                synchronized (actionGate) {
                    if (ticket != generation.get()) return "Pedido cancelado. No ejecuté la respuesta de Gemini.";
                    // which models were busy before one answered is not for David: he gets only the answer
                    if (reply.tool == null) return reply.text;
                }
                if ("buscar_en_internet".equals(reply.tool)) SearchProtocol.requireUserQuery(Validation.string(reply.args, "consulta", 300), text);
                return dispatch(actor, operation, reply.tool, reply.args, ticket);
            } catch (IllegalArgumentException e) {
                return e.getMessage(); // only local validation/network messages, never raw server bodies
            } catch (Exception e) {
                return "No pude completar la operación. Consultá el resumen antes de repetirla. No se guardan cambios parciales.";
            }
        }
    }
    public <T> T proactiveAction(long ticket, java.util.concurrent.Callable<T> action, T cancelled) throws Exception {
        synchronized (actionGate) {
            if (proactivePaused || ticket != generation.get()) return cancelled;
            return action.call();
        }
    }
    public String stop() {
        synchronized (actionGate) {
            generation.incrementAndGet(); proactivePaused = true; executor.cancel();
            try { settings.disablePhone(); }
            catch (RuntimeException e) { return "Cancelé los pedidos. No pude guardar el apagado del control; revisá el interruptor en Ajustes antes de reiniciar la app."; }
        }
        return "Cancelé las confirmaciones pendientes y la respuesta en curso. Apagué el control del teléfono; solo se vuelve a encender desde la app.";
    }
    private String dispatch(String actor, String operation, String tool, org.json.JSONObject args, long ticket) throws Exception {
        return dispatch(actor, operation, tool, args, ticket, () -> false);
    }
    private String dispatch(String actor, String operation, String tool, org.json.JSONObject args, long ticket,
            java.util.function.BooleanSupplier closed) throws Exception {
        org.json.JSONObject checked;
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean()) return "Pedido cancelado.";
            if (!PhoneTools.external(tool)) return executor.execute(actor, operation, tool, args);
            executor.requirePhone();
            String previous = store.receipt(operation);
            if (previous != null) return previous;
            checked = executor.beginPhone(operation, tool, args);
        }
        // Provider/Binder reads can also stall. PARAR must not wait for Android or the network.
        String result;
        try {
            if ("buscar_en_internet".equals(tool)) {
                java.util.function.BooleanSupplier cancelled = () -> ticket != generation.get() || closed.getAsBoolean() || !settings.phoneEnabled();
                if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                // Informational attempts, including failures. A counter write must never block a search.
                try { settings.recordSearch(); } catch (RuntimeException ignored) { }
                result = executor.readPhone(tool, checked);
                if (!cancelled.getAsBoolean()) {
                    try {
                        result = SearchProtocol.summary(cloud.generate(settings.apiKey(), settings.model(), SearchProtocol.summaryRequest(result), cancelled));
                    } catch (Exception ignored) { /* Keep the bounded Tavily text and its sources. */ }
                }
            } else result = executor.readPhone(tool, checked);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if ("buscar_en_internet".equals(tool))
                result = SearchProtocol.FAILURE;
            else result = e instanceof IllegalArgumentException ? message : "No pude completar la lectura. Revisá los permisos en Ajustes.";
        }
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean() || !settings.phoneEnabled()) {
                store.finishAction(operation, "Lectura cancelada; no entregué su resultado.");
                return "Pedido cancelado.";
            }
            return executor.finishPhone(operation, tool, result);
        }
    }
    public boolean clearConversation(long ticket) throws Exception {
        synchronized (requestGate) { synchronized (actionGate) {
            if (ticket != generation.get()) return false;
            store.clearConversation(); return true;
        } }
    }
    public String summary() {
        try { return Summary.render(store.rows(), LocalDate.now(clock), true); }
        catch (Exception e) { return "No pude leer el resumen local."; }
    }
    public static String help() {
        return "Dale, así me podés escribir (andan aunque no haya internet):\nGasté 35000 en super\nCobré 3500000 de sueldo\nPeso 58,4\nHice sentadilla 3x10 con 40\n"
                + "Comí vori vori\nPresupuesto 800000 en super 2026-10\nMeta 5000000 para emergencia\nResumen\nVer ID\n"
                + "Corregir ID {\"centavos\":350000}\nBorrar ID\nSÍ CÓDIGO\n¿Qué hiciste hoy?\nPARAR\n"
                + "Qué sabés de mí\nOlvidá ID\nOlvidá la conversación\nListar recordatorios\nUsá el ID completo. Para corregir, escribí «corregir» en minúsculas y un objeto JSON con los campos. "
                + "Los montos de los ejemplos están en tu moneda; los del JSON están en centavos. "
                + "Sin fecha se registra hoy. Las aclaraciones requieren repetir el pedido completo.";
    }
}
