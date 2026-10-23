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
    public interface Width { float measure(String value); }
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
                if (end < remaining.length() && space > 0) end = space;
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
