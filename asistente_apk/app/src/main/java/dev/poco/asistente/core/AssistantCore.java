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
        default boolean proEnabled() { return true; }
    }
    private final Store store;
    private final Settings settings;
    private final Cloud cloud;
    private final Clock clock;
    private final ToolExecutor executor;
    private final Pro pro;
    private Documents.Port documents;
    private final ChatMode chatMode;
    private final LongSupplier elapsedMillis;
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
        this(store, settings, cloud, clock, elapsedMillis, phone, Pro.UNAVAILABLE);
    }
    public AssistantCore(Store store, Settings settings, Cloud cloud, Clock clock, LongSupplier elapsedMillis, PhoneTools.Port phone, Pro pro) {
        this.store = store; this.settings = settings; this.cloud = cloud; this.clock = clock;
        this.pro = pro; this.chatMode = new ChatMode(elapsedMillis); this.elapsedMillis = elapsedMillis;
        executor = new ToolExecutor(store, clock, elapsedMillis, settings::phoneEnabled, phone);
    }
    public void setDocuments(Documents.Port documents) { this.documents = documents; }
    public void clearChatMode() { chatMode.clear(); }
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
                if (PhoneTools.confirmation(tool)) return phoneProposal(guard.voice.actor, tool, args, guard.voice, ticket, guard::closed);
                return dispatch("llamada", "llamada:" + UUID.randomUUID(), tool, args, ticket, guard::closed, new RequestBudget(elapsedMillis));
            }
            catch (IllegalArgumentException e) { return e.getMessage(); }
            catch (Exception e) { return "No pude hacerlo."; }
        }
    }
    public long cancellationToken() { return generation.get(); }
    /** Only Live's input transcription path calls this; no function declaration exposes confirmation. */
    public String callSpeech(ExternalData.Guard guard, String text, boolean finished, boolean drained, long ticket) {
        synchronized (guard) {
            synchronized (actionGate) {
                if (ticket != generation.get() || guard.closed() || !guard.allowsTools()) { guard.voice.cancel(); return null; }
                String code = guard.voice.input(text, finished, drained);
                if (code == null) return null;
                try { return executor.confirm(guard.voice.actor, "voz-confirmacion:" + UUID.randomUUID(), code); }
                catch (IllegalArgumentException e) { return e.getMessage(); }
                catch (Exception e) { return "No pude completar la acción. Revisá antes de repetirla."; }
            }
        }
    }
    public void closeCall(ExternalData.Guard guard) {
        guard.close();
        // Hanging up is a UI action; never wait for an in-flight provider/Android operation.
        new Thread(() -> { synchronized (actionGate) { executor.cancelActor(guard.voice.actor); } }, "voice-confirmation-cleanup").start();
    }
    public RequestBudget newRequestBudget() { return new RequestBudget(elapsedMillis); }
    /** The trusted inbox calls this inside the shared queue, then feeds the literal text to receive. */
    public String transcribe(AudioInput audio, long ticket) throws Exception {
        return transcribe(audio, ticket, newRequestBudget());
    }
    public String transcribe(AudioInput audio, long ticket, RequestBudget budget) throws Exception {
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        String key = settings.apiKey();
        if (key == null || key.isEmpty()) throw new IllegalArgumentException(AudioInput.FALLBACK);
        GeminiProtocol.Reply reply = budget.read(() -> cloud.generate(key, settings.model(), AudioProtocol.transcription(audio),
                () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()), () -> ticket != generation.get());
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        return AudioProtocol.transcript(reply);
    }
    /** Photos only propose movements; serialize with text so a newer request cannot overtake a proposal. */
    public String describeImage(ImageInput image, long ticket, RequestBudget budget) throws Exception {
        synchronized (requestGate) { return describeImageInternal(image, ticket, budget); }
    }
    private String describeImageInternal(ImageInput image, long ticket, RequestBudget budget) throws Exception {
        synchronized (actionGate) {
            if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
            executor.cancelActor("whatsapp");
        }
        String key = settings.apiKey();
        if (key == null || key.isEmpty()) throw new IllegalArgumentException(ImageInput.FALLBACK);
        GeminiProtocol.Reply reply = budget.read(() -> cloud.generate(key, settings.model(), ImageInput.description(image),
                () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()), () -> ticket != generation.get());
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        String description = ImageInput.described(reply);
        // The only allowed tool output is a proposal. Return its exact validated data immediately:
        // no further cloud call may fail after staging and leave an unseen confirmation behind.
        try {
            GeminiProtocol.Reply movement = budget.read(() -> cloud.generate(key, settings.model(), ImageInput.receipt(description, LocalDate.now(clock)),
                    () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()), () -> ticket != generation.get());
            if ("registrar_movimiento".equals(movement.tool) && movement.args != null) {
                synchronized (actionGate) {
                    if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
                    return ExternalData.wrap(executor.stageMovement("whatsapp", movement.args));
                }
            }
        } catch (IllegalArgumentException e) {
            if (ticket != generation.get()) throw e;
        } catch (Exception ignored) { }
        // External pixels must never enter receive(), its tools, confirmations or history.
        org.json.JSONObject request = GeminiProtocol.request(ImageInput.request(image.caption, description),
                "Respondé sobre la foto sin ejecutar acciones. Si hace falta anotar o actuar, pedí que David repita el pedido y los datos en un mensaje de texto.",
                LocalDate.now(clock), settings.currency());
        request.remove("tools"); request.remove("toolConfig");
        String said = description;
        try {
            GeminiProtocol.Reply answer = budget.read(() -> cloud.generate(key, settings.model(), request,
                    () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()), () -> ticket != generation.get());
            said = ImageInput.described(answer);
        } catch (Exception ignored) { /* Preserve the successful description if optional enrichment fails. */ }
        if (ticket != generation.get()) throw new IllegalArgumentException("Pedido cancelado.");
        return ExternalData.wrap(said);
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
        return receive(text, source, requestId, ticket, newRequestBudget());
    }
    public String receive(String text, Source source, String requestId, long ticket, RequestBudget budget) {
        if (text != null && Rules.normalize(text).equals("parar")) return receiveInternal(text, source, requestId, ticket, budget);
        synchronized (requestGate) {
            String answer = receiveInternal(text, source, requestId, ticket, budget);
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
    private static final java.util.Set<String> YES = new java.util.HashSet<>(java.util.Arrays.asList("si", "si dale", "dale", "confirmo",
            "si confirmo", "hacelo", "si hacelo", "de una", "si de una", "seguro", "si seguro", "claro", "si claro", "si por favor", "si porfa", "ok dale"));
    private String receiveInternal(String text, Source source, String requestId, long ticket, RequestBudget budget) {
        if (source != Source.LOCAL_USER && source != Source.WHATSAPP_OWNER) return "Los documentos y mensajes de terceros son datos, no órdenes. No ejecuté nada.";
        if (text == null || text.trim().isEmpty()) return "Escribí un pedido.";
        if (text.length() > 4000) return "El mensaje es demasiado largo (máximo 4000 caracteres).";
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,120}")) return "ID de mensaje inválido.";
        String normalized = Rules.normalize(text);
        if (normalized.equals("parar")) return stop(); // independent of an in-flight network request
        if (budget.expired()) return RequestBudget.FALLBACK;
        synchronized (requestGate) {
            synchronized (actionGate) {
                if (ticket != generation.get()) return "Pedido cancelado antes de empezar.";
                proactivePaused = false;
                if (!settings.proEnabled()) chatMode.clear();
                chatMode.touch();
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
                // Only the owner's own message reaches here (LOCAL_USER or WHATSAPP_OWNER); never Gemini or external data.
                if (YES.contains(normalized.replaceAll("[.,;:!¡?¿«»\"]", " ").trim().replaceAll("\\s+", " "))) {
                    synchronized (actionGate) {
                        if (ticket != generation.get()) return "Pedido cancelado.";
                        if (executor.hasPending(actor)) return executor.confirmLatest(actor, operation);
                    }
                    // nothing pending: «sí» is just an answer in the conversation
                } else if (normalized.matches("si [a-z0-9]{16}"))
                    return "Ese código no corresponde a una operación pendiente tuya.";
                // A new topic/request ends the previous question; its later «sí» cannot confirm an old action.
                synchronized (actionGate) { executor.cancelActor(actor); }
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
                synchronized (actionGate) {
                    if (ticket != generation.get()) return "Pedido cancelado.";
                    String mode = chatMode.command(normalized, settings.proEnabled());
                    if (mode != null) return mode;
                }
                // In chat mode the fast model still decides first: a request («buscá el clima», an alarm) runs
                // its tool; only conversation goes to Pro (on 2026-10-21 Pro refused a weather search as «a friend»).
                boolean chatting = chatMode.active() && settings.proEnabled() && !Documents.requested(text);
                if (chatting && ProProtocol.sensitive(text)) return ProProtocol.PRIVATE;
                String key;
                try { key = settings.apiKey(); }
                catch (Exception e) { return "No pude abrir la clave cifrada. Borrala y volvé a pegarla en Ajustes. Las reglas locales siguen disponibles."; }
                if (key == null || key.isEmpty()) return "No entendí el pedido con las reglas locales. Podés repetirlo como en «ayuda» o pegar tu clave de Gemini en Ajustes.";
                String context = "Fecha y hora local: " + java.time.ZonedDateTime.now(clock) + "\n" + Summary.render(store.rows(), LocalDate.now(clock), false) + "\nRecordatorios (datos):\n" + Reminders.list(store.reminders());
                if (context.length() > 6000) context = context.substring(0, 6000) + "\n[contexto recortado]";
                org.json.JSONObject request = chatting
                        ? ProProtocol.routingRequest(text, java.time.ZonedDateTime.now(clock), settings.currency(), store.conversation(), store.memories(), settings.phoneEnabled())
                        : GeminiProtocol.request(text, context, LocalDate.now(clock), settings.currency(), store.conversation(), store.memories(), settings.phoneEnabled());
                GeminiProtocol.Reply reply = budget.read(() -> cloud.generate(key, settings.model(), request,
                        () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()),
                        () -> ticket != generation.get(), RequestBudget.ROUTER_MS);
                synchronized (actionGate) {
                    if (ticket != generation.get()) return "Pedido cancelado. No ejecuté la respuesta de Gemini.";
                    // which models were busy before one answered is not for David: he gets only the answer
                    if (reply.tool == null && !chatting) return reply.text;
                }
                if (reply.tool == null) return chat(text, ticket, budget);
                if ("buscar_en_internet".equals(reply.tool)) SearchProtocol.requireUserQuery(Validation.string(reply.args, "consulta", 300), text);
                if ("charla_profunda".equals(reply.tool)) {
                    Validation.keys(reply.args, "");
                    return chat(text, ticket, budget);
                }
                if (Documents.contains(reply.tool)) {
                    if ("descargar".equals(reply.tool)) {
                        Validation.keys(reply.args, "url");
                        Documents.requireUserUrl(Validation.string(reply.args, "url", 2048), text);
                    } else {
                        Validation.keys(reply.args, "titulo,contenido");
                        Validation.string(reply.args, "titulo", 150);
                        Documents.content(reply.args);
                    }
                    return document(operation, reply.tool, reply.args, ticket, budget);
                }
                return dispatch(actor, operation, reply.tool, reply.args, ticket, () -> false, budget);
            } catch (java.util.concurrent.TimeoutException e) {
                return RequestBudget.FALLBACK;
            } catch (IllegalArgumentException e) {
                return e.getMessage(); // only local validation/network messages, never raw server bodies
            } catch (Exception e) {
                return "No pude completar la operación. Consultá el resumen antes de repetirla. No se guardan cambios parciales.";
            }
        }
    }
    /** A movement from the bank mail reader (BankFeed, inside proactiveAction): same executor and validation as WhatsApp. */
    String recordBank(String operation, org.json.JSONObject args) throws Exception {
        if (!operation.matches("banco:[A-Za-z0-9]{8,40}")) throw new IllegalArgumentException("Operación del banco inválida.");
        return executor.execute("banco", operation, "registrar_movimiento", args);
    }
    public <T> T proactiveAction(long ticket, java.util.concurrent.Callable<T> action, T cancelled) throws Exception {
        synchronized (actionGate) {
            if (proactivePaused || ticket != generation.get()) return cancelled;
            return action.call();
        }
    }
    public String stop() {
        synchronized (actionGate) {
            generation.incrementAndGet(); proactivePaused = true; executor.cancel(); chatMode.clear();
            try { settings.disablePhone(); }
            catch (RuntimeException e) { return "Cancelé los pedidos. No pude guardar el apagado del control; revisá el interruptor en Ajustes antes de reiniciar la app."; }
        }
        return "Cancelé las confirmaciones pendientes y la respuesta en curso. Apagué el control del teléfono; solo se vuelve a encender desde la app.";
    }
    private String chat(String text, long ticket, RequestBudget budget) throws Exception {
        if (ticket != generation.get()) return "Pedido cancelado.";
        // Never send current bank/credential text to either chat provider.
        if (ProProtocol.sensitive(text)) return ProProtocol.PRIVATE;
        if (settings.proEnabled() && !budget.canUsePro()) return RequestBudget.FALLBACK;
        if (settings.proEnabled()) {
            try {
                String prompt = ProProtocol.chatPrompt(text, store.conversation(), store.memories());
                String answer = ProProtocol.answer(budget.read(() -> pro.generate("charla", prompt,
                        ProProtocol.CHAT_MODEL, () -> ticket != generation.get() || !settings.proEnabled() || budget.expired() || Thread.currentThread().isInterrupted()),
                        () -> ticket != generation.get() || !settings.proEnabled(), RequestBudget.PRO_CHAT_MS));
                if (ticket != generation.get() || !settings.proEnabled()) return "Pedido cancelado.";
                return answer;
            } catch (java.util.concurrent.TimeoutException timeout) {
                return ticket != generation.get() ? "Pedido cancelado." : RequestBudget.FALLBACK;
            } catch (Exception ignored) { /* Isolated text-only fallback below. */ }
        }
        if (ticket != generation.get()) return "Pedido cancelado.";
        if (budget.expired()) return RequestBudget.FALLBACK;
        String answer = RequestBudget.FALLBACK;
        try {
            String key = settings.apiKey();
            if (key != null && !key.isEmpty()) {
                // Reuse ONLY the already filtered Pro context, not financial summaries or raw old history.
                org.json.JSONObject body = GeminiProtocol.request(text, "", LocalDate.now(clock), settings.currency());
                body = ProProtocol.fallbackRequest(body);
                body.put("contents", new org.json.JSONArray().put(Json.object("role", "user", "parts", new org.json.JSONArray().put(
                        Json.object("text", ProProtocol.chatPrompt(text, store.conversation(), store.memories()))))));
                final org.json.JSONObject fallbackBody = body;
                GeminiProtocol.Reply fallback = budget.read(() -> cloud.generate(key, settings.model(), fallbackBody,
                        () -> ticket != generation.get() || budget.expired() || Thread.currentThread().isInterrupted()), () -> ticket != generation.get());
                if (fallback.tool == null) answer = ProProtocol.answer(fallback.text);
            }
        } catch (Exception ignored) { }
        return ticket != generation.get() ? "Pedido cancelado." : answer + "\n" + ProProtocol.FALLBACK;
    }
    private String document(String operation, String tool, org.json.JSONObject args, long ticket, RequestBudget budget) throws Exception {
        synchronized (actionGate) {
            if (ticket != generation.get()) return "Pedido cancelado.";
            if (documents == null) return "Los documentos no están disponibles.";
            String previous = store.receipt(operation);
            if (previous != null) return previous;
            store.beginAction(operation, tool, clock.millis());
        }
        try {
            java.util.function.BooleanSupplier cancelled = () -> ticket != generation.get() || budget.expired();
            Documents.Prepared result = budget.read(() -> documents.prepare(tool, args, cancelled), cancelled, 45000);
            synchronized (actionGate) {
                if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                return store.enqueueDocument(operation, result, clock.millis());
            }
        } catch (Exception e) {
            synchronized (actionGate) { store.finishAction(operation, "No envié el documento; pedido cancelado o fallido."); }
            if (e instanceof IllegalArgumentException) throw e;
            return "No pude preparar el archivo. No lo envié.";
        }
    }
    /** Contacts/calendar lookups run outside the gate (PARAR must not wait on a provider); staging rechecks the ticket. */
    private String phoneProposal(String actor, String tool, org.json.JSONObject args, VoiceConfirmation voice, long ticket,
            java.util.function.BooleanSupplier closed) throws Exception {
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean()) return "Pedido cancelado.";
            executor.cancelActor(actor);
            if (voice != null) voice.cancel();
            executor.requirePhone();
        }
        org.json.JSONObject snapshot = executor.preparePhone(tool, args);
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean()) return "Pedido cancelado.";
            return executor.stagePhone(actor, tool, snapshot, voice);
        }
    }
    private String dispatch(String actor, String operation, String tool, org.json.JSONObject args, long ticket,
            java.util.function.BooleanSupplier closed, RequestBudget budget) throws Exception {
        if (PhoneTools.confirmation(tool)) return phoneProposal(actor, tool, args, null, ticket, closed);
        if ("musica".equals(tool)) return music(operation, args, ticket, closed, budget);
        org.json.JSONObject checked;
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean()) return "Pedido cancelado.";
            if (budget.expired()) return RequestBudget.FALLBACK;
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
                result = null;
                if (settings.proEnabled() && budget.canUsePro()) try {
                    result = SearchProtocol.summary(new GeminiProtocol.Reply(ProProtocol.answer(budget.read(() -> pro.generate("busqueda",
                            ProProtocol.searchPrompt(checked.getString("consulta")), ProProtocol.SEARCH_MODEL,
                            () -> cancelled.getAsBoolean() || !settings.proEnabled() || budget.expired() || Thread.currentThread().isInterrupted()),
                            cancelled, RequestBudget.PRO_SEARCH_MS)), null, null, ""));
                } catch (Exception ignored) { /* Tavily remains the fallback. */ }
                if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                if (result == null) {
                    result = budget.read(() -> executor.readPhone(tool, checked), cancelled);
                    if (!cancelled.getAsBoolean()) {
                        try {
                            final String raw = result;
                            result = SearchProtocol.summary(budget.read(() -> cloud.generate(settings.apiKey(), settings.model(), SearchProtocol.summaryRequest(raw),
                                    () -> cancelled.getAsBoolean() || budget.expired() || Thread.currentThread().isInterrupted()), cancelled));
                        } catch (Exception ignored) { /* Keep the bounded Tavily text and its sources. */ }
                    }
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
    private String music(String operation, org.json.JSONObject args, long ticket,
            java.util.function.BooleanSupplier closed, RequestBudget budget) throws Exception {
        org.json.JSONObject checked;
        synchronized (actionGate) {
            if (ticket != generation.get() || closed.getAsBoolean()) return "Pedido cancelado.";
            String previous = store.receipt(operation);
            if (previous != null) return previous;
            checked = executor.beginPhone(operation, "musica", args);
        }
        java.util.function.BooleanSupplier cancelled = () -> ticket != generation.get() || closed.getAsBoolean()
                || !settings.phoneEnabled() || budget.expired();
        try {
            org.json.JSONObject prepared = budget.read(() -> executor.prepareMusic(checked), cancelled);
            synchronized (actionGate) {
                if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                return executor.executeMusic(operation, prepared);
            }
        } catch (Exception e) {
            synchronized (actionGate) { store.finishAction(operation, "Música cancelada o fallida; revisá antes de repetir."); }
            throw e;
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
                + "Corregir ID {\"centavos\":350000}\nBorrar ID\nSÍ (para confirmar)\n¿Qué hiciste hoy?\nPARAR\n"
                + "Qué sabés de mí\nOlvidá ID\nOlvidá la conversación\nListar recordatorios\nUsá el ID completo. Para corregir, escribí «corregir» en minúsculas y un objeto JSON con los campos. "
                + "Los montos de los ejemplos están en tu moneda; los del JSON están en centavos. "
                + "Sin fecha se registra hoy. Las aclaraciones requieren repetir el pedido completo.";
    }
}
