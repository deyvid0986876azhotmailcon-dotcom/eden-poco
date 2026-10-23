package dev.poco.asistente.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneActionsTest {
    private final TestStore store = new TestStore();
    private final TestStore.MutableClock clock = new TestStore.MutableClock();
    private final AtomicInteger effects = new AtomicInteger();
    private boolean enabled = true;
    private JSONObject executed;
    private final PhoneTools.Port port = new PhoneTools.Port() {
        @Override public JSONObject prepare(String tool, JSONObject a) throws Exception {
            JSONObject copy = Json.copy(a);
            if (tool.equals("llamar") || tool.equals("mensaje_whatsapp")) copy.put("numero", "+595981123456");
            if (tool.equals("agendar")) copy.put("calendario", 7);
            return copy;
        }
        @Override public String execute(String tool, JSONObject a) throws Exception { effects.incrementAndGet(); executed = Json.copy(a); return "Hecho " + tool; }
    };
    private final ToolExecutor executor = new ToolExecutor(store, clock, clock::millis, () -> enabled, port);
    private static String code(String proposal) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("SÍ ([A-F0-9]{16})").matcher(proposal);
        assertTrue(proposal, m.find()); return m.group(1);
    }
    private String propose(String actor, String tool, JSONObject args) throws Exception {
        return executor.execute(actor, UUID.randomUUID().toString(), tool, args);
    }
    @Test public void everyActionWaitsForExactCodeAndAuditsOnlyTheEffect() throws Exception {
        String[] names = {"llamar", "mensaje_whatsapp", "agendar", "no_molestar", "brillo"};
        JSONObject[] args = {Json.object("contacto","Mamá"), Json.object("contacto","Mamá","texto","Hola"),
                Json.object("titulo","Dentista","inicio","2026-10-25T15:00"), Json.object("accion","activar","minutos",20), Json.object("porcentaje",25)};
        for (int i = 0; i < names.length; i++) {
            String proposal = propose("local", names[i], args[i]), token = code(proposal);
            assertEquals(i, effects.get());
            assertEquals(i, store.receipts.size());
            executor.confirm("other", "bad-actor" + i, token);
            executor.confirm("local", "bad-code" + i, "0000000000000000");
            assertEquals(i, effects.get());
            assertEquals("Hecho " + names[i], executor.confirm("local", "confirm" + i, token));
            executor.confirm("local", "replay" + i, token);
            executor.confirm("local", "confirm" + i, token);
            assertEquals(i + 1, effects.get());
            assertEquals(i + 1, store.receipts.size());
        }
    }
    @Test public void snapshotDoesNotFollowArgumentMutationAndNewProposalInvalidatesOld() throws Exception {
        JSONObject args = Json.object("contacto","Mamá","texto","Hola");
        String token = code(propose("local", "mensaje_whatsapp", args)); args.put("texto", "Cambiado");
        executor.confirm("local", "confirmed", token); assertEquals("Hola", executed.getString("texto"));
        String first = code(propose("local","brillo",Json.object("porcentaje",10)));
        String second = code(propose("local","brillo",Json.object("porcentaje",20)));
        executor.confirm("local","old",first); assertEquals(1,effects.get());
        executor.confirm("local","new",second); assertEquals(20,executed.getInt("porcentaje"));
    }
    @Test public void expiredCancelledDisabledAndFailedActionsCannotReplay() throws Exception {
        String token = code(propose("local","brillo",Json.object("porcentaje",10)));
        clock.millis += 300000;
        assertTrue(executor.confirm("local","expired",token).contains("venció"));
        token = code(propose("local","brillo",Json.object("porcentaje",10))); executor.cancel();
        executor.confirm("local","cancelled",token); assertEquals(0,effects.get());
        final String disabled = code(propose("local","brillo",Json.object("porcentaje",10))); enabled = false;
        assertThrows(IllegalArgumentException.class, () -> executor.confirm("local","disabled",disabled));
        enabled = true; executor.confirm("local","again",disabled); assertEquals(0,effects.get());
        ToolExecutor failing = new ToolExecutor(store,clock,clock::millis,()->true,(tool,a)-> { effects.incrementAndGet(); throw new Exception("uncertain"); });
        String fail = code(failing.execute("local","propose","brillo",Json.object("porcentaje",10)));
        assertThrows(Exception.class,()->failing.confirm("local","failure",fail));
        failing.confirm("local","failure-retry",fail); assertEquals(1,effects.get());
        assertTrue(store.receipt("failure").contains("Revisá"));
    }
    @Test public void rejectsMalformedArgumentsAndModelSuppliedResolvedTargets() throws Exception {
        for (Object value : new Object[]{-1,101,1.5,"50",JSONObject.NULL})
            assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("brillo",Json.object("porcentaje",value)));
        for (String time : new String[]{"2026-02-30T12:00","2026-10-25T25:00","25/10/2026 15:00","2026-10-25T15:00Z"})
            assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("agendar",Json.object("titulo","X","inicio",time)));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("agendar",Json.object("titulo","X","inicio","2026-10-25T15:00","fin","2026-10-25T14:59")));
        assertEquals("2026-10-25T16:00",PhoneTools.validate("agendar",Json.object("titulo","X","inicio","2026-10-25T15:00")).getString("fin"));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("llamar",Json.object("contacto","Mamá","numero","+595981123456")));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("llamar",Json.object("contacto",123)));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("mensaje_whatsapp",Json.object("contacto","Mamá","texto","")));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("no_molestar",Json.object("accion","desactivar","minutos",5)));
        for (Object value : new Object[]{0,1441,2.5,"5"})
            assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("no_molestar",Json.object("accion","activar","minutos",value)));
        assertThrows(IllegalArgumentException.class,()->PhoneTools.validate("wifi",Json.object("activar",true)));
        assertEquals(0,PhoneTools.validate("bluetooth",new JSONObject()).length());
        assertThrows(IllegalArgumentException.class,()->executor.proposePhone("local","pagar",new JSONObject(),new VoiceConfirmation()));
    }
    @Test public void contactsAreExplicitAndUnsafeNumbersAndBotAreRejected() {
        List<ContactPolicy.Contact> contacts = Arrays.asList(new ContactPolicy.Contact("Mamá", "0981123456"), new ContactPolicy.Contact("Mamá trabajo", "+595982123456"));
        assertEquals("+595981123456",ContactPolicy.resolve("Mama",contacts).number);
        assertThrows(IllegalArgumentException.class,()->ContactPolicy.resolve("Papá",contacts));
        List<ContactPolicy.Contact> ambiguous = Arrays.asList(new ContactPolicy.Contact("Mamá", "0981123456"),new ContactPolicy.Contact("Mamá", "0982123456"));
        assertThrows(IllegalArgumentException.class,()->ContactPolicy.resolve("Mamá",ambiguous));
        assertEquals("+595982123456",ContactPolicy.resolve("Mamá (+595982123456)",ambiguous).number);
        for (String number : new String[]{"911","112","*123#","+19001234567","0900123456","0981123456;123","0981123456,123"})
            assertThrows(IllegalArgumentException.class,()->ContactPolicy.mobile(number));
        assertThrows(IllegalArgumentException.class,()->ContactPolicy.whatsapp("0981123456",""));
        assertThrows(IllegalArgumentException.class,()->ContactPolicy.whatsapp("0981123456","+595981123456"));
        ContactPolicy.whatsapp("0982123456","+595981123456");
    }
    private AssistantCore core() {
        AssistantCore.Settings settings = new AssistantCore.Settings() {
            public String apiKey() { return "abcdefghijklmnopqrstuv"; }
            public String model() { return GeminiProtocol.MODELS[0]; }
            public String currency() { return "PYG"; }
            public boolean phoneEnabled() { return enabled; }
            public void disablePhone() { enabled = false; }
        };
        return new AssistantCore(store,settings,(k,m,b)->new GeminiProtocol.Reply(null,"brillo",Json.object("porcentaje",20),""),clock,clock::millis,port);
    }
    @Test public void directTextConfirmationStopAndExternalSourceUseExistingGate() {
        AssistantCore core = core();
        String token = code(core.receive("poné el brillo a 20"));
        assertEquals(0,effects.get());
        core.receive("SÍ " + token,AssistantCore.Source.UNTRUSTED_DATA,"external"); assertEquals(0,effects.get());
        core.receive("SÍ " + token); assertEquals(1,effects.get());
        token = code(core.receive("poné el brillo a 20")); core.stop();
        core.receive("SÍ " + token); assertEquals(1,effects.get()); assertFalse(enabled);
    }
    @Test public void voiceRequiresExactSpeechAfterFullDescriptionAndPlaybackInSameCall() throws Exception {
        AssistantCore core = core(); ExternalData.Guard guard = new ExternalData.Guard(); long ticket = core.cancellationToken();
        core.callTool("brillo",Json.object("porcentaje",30),guard,ticket); assertEquals(0,effects.get());
        assertNull(core.callSpeech(guard,"sí, confirmo",true,true,ticket));
        guard.voice.output("Voy a poner el brillo manual al 30 por ciento."); guard.voice.outputComplete();
        assertNull(core.callSpeech(guard,"sí, confirmo",true,false,ticket));
        assertNull(core.callSpeech(guard,"sí",true,true,ticket));
        assertNull(core.callSpeech(new ExternalData.Guard(),"sí, confirmo",true,true,ticket));
        assertEquals("Hecho brillo",core.callSpeech(guard,"sí, confirmo",true,true,ticket)); assertEquals(1,effects.get());
        assertNull(core.callSpeech(guard,"sí, confirmo",true,true,ticket));
    }
    @Test public void voicePartialTranscriptsMismatchInterruptionCloseStopAndExternalExposure() throws Exception {
        VoiceConfirmation voice = new VoiceConfirmation();
        voice.propose("one","Voy a poner el brillo manual al 30 por ciento.");
        voice.output("Voy a poner el brillo manual al 40 por ciento."); voice.outputComplete();
        assertNull(voice.input("sí, confirmo",true,true));
        voice.propose("two","Voy a poner el brillo manual al 30 por ciento.");
        voice.output("Voy a poner el brillo manual al 30 por ciento."); voice.outputComplete();
        assertNull(voice.input("sí, ",false,true)); assertEquals("two",voice.input("confirmo",true,true));
        for (String mode : new String[]{"interrupt","close","stop","external","expire"}) {
            enabled = true; AssistantCore core = core(); ExternalData.Guard guard = new ExternalData.Guard(); long ticket = core.cancellationToken();
            core.callTool("brillo",Json.object("porcentaje",30),guard,ticket);
            guard.voice.output("Voy a poner el brillo manual al 30 por ciento."); guard.voice.outputComplete();
            switch (mode) {
                case "interrupt": guard.voice.interrupted(); break;
                case "close": guard.close(); break;
                case "stop": core.stop(); break;
                case "external": guard.expose(); break;
                default: clock.millis += 300000;
            }
            core.callSpeech(guard,"sí, confirmo",true,true,ticket); assertEquals(0,effects.get());
        }
    }
}
