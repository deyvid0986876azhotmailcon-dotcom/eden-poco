package dev.poco.asistente.messaging;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.poco.asistente.AssistantApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** System-only binding; never reads personal WhatsApp notifications or other packages. */
public final class WhatsAppNotifications extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !NotificationPolicy.isWhatsApp(sbn.getPackageName())) return;
        AssistantApplication app = (AssistantApplication) getApplication();
        if (!app.settings.whatsappEnabled()) return;
        try {
            Notification notification = sbn.getNotification();
            app.whatsapp.accept(snapshot(sbn), reply(notification));
        } catch (RuntimeException e) {
            app.whatsapp.systemLog("Aviso rechazado: formato no compatible; no se leyó como orden.");
        }
    }
    @Override public void onListenerConnected() {
        ((AssistantApplication) getApplication()).whatsapp.systemLog("Acceso a notificaciones conectado. Se esperan avisos nuevos; no se reproducen avisos antiguos.");
    }
    @Override public void onListenerDisconnected() {
        AssistantApplication app = (AssistantApplication) getApplication();
        app.stop();
        app.whatsapp.systemLog("Acceso a notificaciones desconectado; cola cancelada.");
    }
    @Override public void onDestroy() {
        ((AssistantApplication) getApplication()).stop();
        super.onDestroy();
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    static NotificationPolicy.Notice snapshot(StatusBarNotification sbn) {
        Notification n = sbn.getNotification(); Bundle extras = n.extras == null ? new Bundle() : n.extras;
        Notification.MessagingStyle style = Notification.MessagingStyle.extractMessagingStyleFromNotification(n);
        List<NotificationPolicy.Message> messages = new ArrayList<>();
        Boolean group = extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)
                ? extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) : null;
        String conversation = text(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (style != null) {
            if (Build.VERSION.SDK_INT >= 28) group = style.isGroupConversation() || Boolean.TRUE.equals(group);
            if (style.getConversationTitle() != null) conversation = style.getConversationTitle().toString();
            for (Notification.MessagingStyle.Message m : style.getMessages()) {
                NotificationPolicy.Sender sender = null;
                if (Build.VERSION.SDK_INT >= 28) {
                    Person person = m.getSenderPerson();
                    if (person != null) sender = new NotificationPolicy.Sender(text(person.getName()), person.getKey(), person.getUri());
                } else if (m.getSender() != null) sender = new NotificationPolicy.Sender(text(m.getSender()), "", "");
                messages.add(new NotificationPolicy.Message(sender, text(m.getText()), m.getTimestamp()));
            }
        }
        return new NotificationPolicy.Notice(sbn.getPackageName(), sbn.getKey(), (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                group, text(extras.getCharSequence(Notification.EXTRA_TITLE)), conversation, messages,
                text(extras.getCharSequence(Notification.EXTRA_TEXT)), n.when,
                extras.containsKey(Notification.EXTRA_REMOTE_INPUT_HISTORY));
    }
    private static WhatsAppBridge.Reply reply(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action action : n.actions) {
            if (action.actionIntent == null || !NotificationPolicy.isWhatsApp(action.actionIntent.getCreatorPackage())) continue;
            String label = text(action.title).toLowerCase(Locale.ROOT);
            boolean isReply = Build.VERSION.SDK_INT >= 28 && action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY;
            if (!isReply && !label.startsWith("responder") && !label.startsWith("reply")) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null) continue;
            for (RemoteInput input : inputs) if (input.getAllowFreeFormInput()) return new InlineReply(action.actionIntent, input);
        }
        return null;
    }
    private static final class InlineReply implements WhatsAppBridge.Reply {
        private final PendingIntent intent;
        private final RemoteInput input;
        InlineReply(PendingIntent intent, RemoteInput input) { this.intent = intent; this.input = input; }
        @Override public void send(Context context, String response) throws PendingIntent.CanceledException {
            Intent fill = new Intent(); Bundle result = new Bundle(); result.putCharSequence(input.getResultKey(), response);
            RemoteInput.addResultsToIntent(new RemoteInput[]{input}, fill, result);
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
            intent.send(context, 0, fill);
        }
    }
}
