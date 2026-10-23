package dev.poco.asistente.core;

import java.util.*;

public final class ContactPolicy {
    private ContactPolicy() {}
    public static final class Contact {
        public final String name, number;
        public Contact(String name, String number) { this.name = name == null ? "" : name; this.number = number == null ? "" : number; }
    }
    /** Closed destination policy: ordinary Paraguay mobile numbers only, no extensions/USSD/short codes. */
    public static String mobile(String raw) {
        String n = raw.replaceAll("[ ()-]", "");
        if (n.matches("09[0-9]{8}")) n = "+595" + n.substring(1);
        else if (n.matches("5959[0-9]{8}")) n = "+" + n;
        if (!n.matches("\\+5959[6-9][0-9]{7}")) throw new IllegalArgumentException("Solo admito móviles paraguayos completos (+595 96/97/98/99…). No números cortos, especiales ni extensiones.");
        return n;
    }
    public static Contact resolve(String query, List<Contact> contacts) {
        String wanted = Rules.normalize(query);
        List<Contact> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (Contact c : contacts) {
            String name = Rules.normalize(c.name), number;
            try { number = mobile(c.number); } catch (IllegalArgumentException e) { number = c.number; }
            Contact normalized = new Contact(c.name, number);
            if (wanted.equals(name) || wanted.equals(name + " (" + number + ")")) exact.add(normalized);
            else if (!name.isEmpty() && name.contains(wanted)) partial.add(normalized);
        }
        List<Contact> selected = exact.isEmpty() ? partial : exact;
        if (selected.isEmpty()) throw new IllegalArgumentException("No encontré ese contacto. Repetí el pedido con el nombre completo.");
        Set<String> numbers = new LinkedHashSet<>();
        for (Contact c : selected) numbers.add(mobile(c.number)); // do not silently drop unsafe candidates
        if (numbers.size() != 1) throw new IllegalArgumentException("Hay varios números: " + String.join(", ", numbers) + ". Repetí el pedido con nombre (+número). Si no podés distinguirlos, elegí desde Contactos.");
        // Do not expose arbitrary text read from the contacts provider to a tool-capable model.
        return new Contact(query, numbers.iterator().next());
    }
    public static void whatsapp(String number, String bot) {
        if (bot == null || bot.isEmpty()) throw new IllegalArgumentException("Configurá el número del bot en Ajustes antes de preparar mensajes.");
        if (mobile(number).equals(mobile(bot))) throw new IllegalArgumentException("No preparo mensajes al número del bot.");
    }
}
