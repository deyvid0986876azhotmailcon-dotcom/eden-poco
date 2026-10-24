package dev.poco.asistente.core;

import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Wire validation and layout, independent of Android. */
public final class Documents {
    public static final int MAX = 15 * 1024 * 1024;
    public interface Port {
        Prepared prepare(String tool, org.json.JSONObject args, BooleanSupplier cancelled) throws Exception;
    }
    public static final class Prepared {
        public final String name, mime;
        public final byte[] bytes;
        public Prepared(String name, String mime, byte[] bytes) {
            validate(name, mime, bytes.length); this.name = name; this.mime = mime; this.bytes = bytes;
        }
    }
    public static boolean contains(String tool) { return "armar_pdf".equals(tool) || "descargar".equals(tool); }
    public static boolean requested(String text) {
        return Rules.normalize(text).matches("(?s).*\\b(pdf|descarga\\p{L}*)\\b.*");
    }
    public static String content(org.json.JSONObject args) {
        Object value = args.opt("contenido");
        if (!(value instanceof String)) throw new IllegalArgumentException("Falta el contenido del PDF.");
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > 100000 || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Contenido del PDF inválido o demasiado largo.");
        return text;
    }
    public static void validate(String name, String mime, long size) {
        if (name == null || name.length() > 120 || !name.matches("[\\p{L}\\p{N} _().-]+") || name.startsWith(".") || name.contains("..") || name.endsWith(".") || name.endsWith(" "))
            throw new IllegalArgumentException("Nombre de archivo inválido.");
        String extension = extension(mime);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(extension) && !(mime.equals("image/jpeg") && lower.endsWith(".jpeg")))
            throw new IllegalArgumentException("El nombre no coincide con el tipo de archivo.");
        size(size);
    }
    public static void size(long size) {
        if (size < 1 || size > MAX) throw new IllegalArgumentException("El archivo debe pesar entre 1 byte y 15 MB.");
    }
    public static String extension(String mime) {
        if (mime == null) throw new IllegalArgumentException("Tipo de archivo no permitido.");
        switch (mime) {
            case "application/pdf": return ".pdf";
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "text/plain": return ".txt";
            default: throw new IllegalArgumentException("Solo puedo mandar PDF, JPG, PNG o TXT.");
        }
    }
    public static URI url(String value) {
        try {
            URI uri = new URI(value);
            if (value.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new Exception();
            return uri;
        } catch (Exception e) { throw new IllegalArgumentException("Necesito una URL HTTPS sin usuario ni contraseña."); }
    }
    public static void requireUserUrl(String value, String message) {
        url(value);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://[^\\s<>\"“”]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            String candidate = m.group();
            if (candidate.equals(value) || candidate.replaceFirst("[.,;!?)]*$", "").equals(value)) return;
        }
        throw new IllegalArgumentException("Pegá la URL de descarga en tu mensaje actual.");
    }
    public static String filename(String title) {
        String name = title.replaceAll("[^\\p{L}\\p{N} _()-]", "_").trim();
        if (name.isEmpty()) name = "Documento";
        return name.substring(0, Math.min(100, name.length())) + ".pdf";
    }
    /** One line of the PDF body: 'h' section title (# ...), 'b' bullet (- ...), 'r' row «concept: amount», 'p' text, ' ' blank. */
    public static final class Block {
        public final char kind;
        public final String text, amount;
        Block(char kind, String text, String amount) { this.kind = kind; this.text = text; this.amount = amount; }
    }
    private static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile(
            "(.+?)\\s*[:—–]\\s*(-?\\s?(?:₲|US\\$|R\\$|\\$|€|Gs\\.?)\\s?-?[0-9][0-9.]*(?:,[0-9]+)?)");
    public static List<Block> blocks(String text) {
        List<Block> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) { out.add(new Block(' ', "", null)); continue; }
            String heading = line.replaceFirst("^#+\\s*", "");
            if (line.startsWith("#") && !heading.isEmpty()) { out.add(new Block('h', heading, null)); continue; }
            boolean bullet = line.matches("[-*•]\\s+.+");
            if (bullet) line = line.replaceFirst("^[-*•]\\s+", "");
            java.util.regex.Matcher row = ROW.matcher(line);
            if (row.matches()) out.add(new Block('r', row.group(1), row.group(2)));
            else out.add(new Block(bullet ? 'b' : 'p', line, null));
        }
        return out;
    }
    /** Word wrap of one paragraph to a width; never loses characters. */
    public static List<String> wrap(String text, Width width, float maxWidth) {
        return pages(text.replace('\n', ' '), width, maxWidth, Integer.MAX_VALUE).get(0);
    }
    public interface Width { float measure(String value); }
    /** Number of wrapped lines that fit without drawing into the footer. */
    public static int fittingLines(float available, float overhead, float lineHeight, int remaining) {
        return Math.min(remaining, Math.max(0, (int) Math.floor((available - overhead) / lineHeight)));
    }
    /** Only unreferenced UUID files in our private document directory are disposable. */
    public static void pruneFiles(java.io.File directory, Set<String> retained) {
        java.io.File[] files = directory.listFiles();
        if (files == null) return;
        for (java.io.File file : files)
            if (file.isFile() && file.getName().matches("[0-9a-f-]{36}") && !retained.contains(file.getName())) file.delete();
    }
    public static List<List<String>> pages(String text, Width width, float maxWidth, int lines) {
        if (text == null || text.length() > 100000 || lines < 1 || maxWidth <= 0) throw new IllegalArgumentException("Contenido demasiado largo o paginado inválido.");
        List<List<String>> pages = new ArrayList<>(); List<String> page = new ArrayList<>(); pages.add(page);
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split("\n", -1)) {
            String remaining = paragraph;
            do {
                int end = 0, space = -1;
                while (end < remaining.length()) {
                    int next = end + Character.charCount(remaining.codePointAt(end));
                    if (width.measure(remaining.substring(0, next)) > maxWidth) break;
                    if (remaining.charAt(end) == ' ') space = end;
                    end = next;
                }
                if (!remaining.isEmpty() && end == 0) throw new IllegalArgumentException("Un carácter no cabe en la página.");
                if (end < remaining.length() && remaining.charAt(end) != ' ' && space > 0) end = space; // a full line ending at a space keeps its last word
                if (page.size() == lines) { page = new ArrayList<>(); pages.add(page); }
                page.add(remaining.substring(0, end));
                remaining = remaining.substring(end);
                if (remaining.startsWith(" ")) remaining = remaining.substring(1);
            } while (!remaining.isEmpty());
        }
        if (pages.size() > 100) throw new IllegalArgumentException("El PDF supera 100 páginas.");
        return pages;
    }
}
