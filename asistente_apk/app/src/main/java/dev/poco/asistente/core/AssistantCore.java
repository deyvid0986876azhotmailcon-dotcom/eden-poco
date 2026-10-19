package dev.poco.asistente.core;

import java.time.Clock;
import java.time.LocalDate;
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
    }
    private final Store store;
    private final Settings settings;
    private final Cloud cloud;
    private final Clock clock;
    private final ToolExecutor executor;
    private final AtomicLong generation = new AtomicLong();
    private final Object actionGate = new Object(), requestGate = new Object();
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock) {
        this(store, settings, cloud, clock, () -> System.nanoTime() / 1_000_000L);
    }
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock, LongSupplier elapsedMillis) {
        this.store = store; this.settings = settings; this.cloud = cloud; this.clock = clock;
        executor = new ToolExecutor(store, clock, elapsedMillis);
    }
    public String receive(String text) { return receive(text, Source.LOCAL_USER, UUID.randomUUID().toString()); }
    public long cancellationToken() { return generation.get(); }
    /** Capture the token when queuing, so PARAR also cancels work that has not started. */
    public String receive(String text, long queuedToken) {
        return receive(text, Source.LOCAL_USER, UUID.randomUUID().toString(), queuedToken);
    }
    /** requestId comes from the trusted adapter, never from Gemini. Reuse it to retry delivery. */
    public String receive(String text, Source source, String requestId) {
        return receive(text, source, requestId, generation.get());
    }
    public String receive(String text, Source source, String requestId, long ticket) {
        if (source != Source.LOCAL_USER && source != Source.WHATSAPP_OWNER) return "Los documentos y mensajes de terceros son datos, no órdenes. No ejecuté nada.";
        if (text == null || text.trim().isEmpty()) return "Escribí un pedido.";
        if (text.length() > 4000) return "El mensaje es demasiado largo (máximo 4000 caracteres).";
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,120}")) return "ID de mensaje inválido.";
        String normalized = Rules.normalize(text);
        if (normalized.equals("parar")) return stop(); // independent of an in-flight network request
        synchronized (requestGate) {
            if (ticket != generation.get()) return "Pedido cancelado antes de empezar.";
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
                String context = Summary.render(store.rows(), LocalDate.now(clock), false);
                if (context.length() > 6000) context = context.substring(0, 6000) + "\n[contexto recortado]";
                GeminiProtocol.Reply reply = cloud.generate(key, settings.model(), GeminiProtocol.request(text, context, LocalDate.now(clock), settings.currency()), () -> ticket != generation.get());
                synchronized (actionGate) {
                    if (ticket != generation.get()) return "Pedido cancelado. No ejecuté la respuesta de Gemini.";
                    String notice = reply.notice == null ? "" : reply.notice;
                    if (reply.tool == null) return notice + reply.text;
                    return notice + executor.execute(actor, operation, reply.tool, reply.args);
                }
            } catch (IllegalArgumentException e) {
                return e.getMessage(); // only local validation/network messages, never raw server bodies
            } catch (Exception e) {
                return "No pude completar la operación. Consultá el resumen antes de repetirla. No se guardan cambios parciales.";
            }
        }
    }
    public String stop() {
        synchronized (actionGate) { generation.incrementAndGet(); executor.cancel(); }
        return "Cancelé las confirmaciones pendientes y la respuesta en curso. En esta fase no hay control del teléfono.";
    }
    public String summary() {
        try { return Summary.render(store.rows(), LocalDate.now(clock), true); }
        catch (Exception e) { return "No pude leer el resumen local."; }
    }
    public static String help() {
        return "Upéicha, así me podés escribir (andan aunque no haya internet):\nGasté 35000 en super\nCobré 3500000 de sueldo\nPeso 58,4\nHice sentadilla 3x10 con 40\n"
                + "Comí vori vori\nPresupuesto 800000 en super 2026-10\nMeta 5000000 para emergencia\nResumen\nVer ID\n"
                + "Corregir ID {\"centavos\":350000}\nBorrar ID\nSÍ CÓDIGO\n¿Qué hiciste hoy?\nPARAR\n"
                + "Usá el ID completo. Para corregir, escribí «corregir» en minúsculas y un objeto JSON con los campos. "
                + "Los montos de los ejemplos están en tu moneda; los del JSON están en centavos. "
                + "Sin fecha se registra hoy. Las aclaraciones requieren repetir el pedido completo.";
    }
}
