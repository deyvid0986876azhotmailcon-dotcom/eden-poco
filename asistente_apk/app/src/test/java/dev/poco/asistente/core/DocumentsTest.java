package dev.poco.asistente.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentsTest {
    @Test public void paginationPreservesLinesAndWrapsWords() {
        java.util.List<java.util.List<String>> pages = Documents.pages("uno dos tres\n\nfin", s -> s.length(), 7, 2);
        assertEquals(java.util.Arrays.asList("uno dos", "tres"), pages.get(0));
        assertEquals(java.util.Arrays.asList("", "fin"), pages.get(1));
        assertEquals(2, pages.size());
    }
    @Test public void longWordsAndUnicodeNeverLoseCharacters() {
        assertEquals(java.util.Arrays.asList("abcd", "efgh", "ij"), Documents.pages("abcdefghij", s -> s.length(), 4, 3).get(0));
        assertEquals(java.util.Arrays.asList("😀", "😀"), Documents.pages("😀😀", s -> s.length(), 2, 4).get(0));
        assertThrows(IllegalArgumentException.class, () -> Documents.pages("x", s -> 100, 10, 2));
    }
    @Test public void typesSizesAndNamesAreBounded() {
        Documents.validate("Gastos de octubre.pdf", "application/pdf", Documents.MAX);
        Documents.validate("foto.jpeg", "image/jpeg", 1);
        for (String name : new String[]{"../x.pdf", "a/b.pdf", "a\\b.pdf", ".pdf", "x.pdf\n", "x.exe", "x..pdf", "x.pdf "})
            assertThrows(name, IllegalArgumentException.class, () -> Documents.validate(name, "application/pdf", 1));
        assertThrows(IllegalArgumentException.class, () -> Documents.validate("x.pdf", "text/html", 1));
        assertThrows(IllegalArgumentException.class, () -> Documents.size(0));
        assertThrows(IllegalArgumentException.class, () -> Documents.size((long) Documents.MAX + 1));
        assertEquals("Gastos_octubre.pdf", Documents.filename("Gastos/octubre"));
    }
    @Test public void urlMustComeFromCurrentMessageExactly() {
        Documents.requireUserUrl("https://example.com/a.pdf", "Descargá https://example.com/a.pdf.");
        for (String url : new String[]{"http://example.com/a", "https://u:p@example.com/a", "file:///a", "https://example.com:80/a", "https://example.com/a#b"})
            assertThrows(url, IllegalArgumentException.class, () -> Documents.url(url));
        assertThrows(IllegalArgumentException.class, () -> Documents.requireUserUrl("https://example.com/a", "Usá el enlace anterior"));
        assertThrows(IllegalArgumentException.class, () -> Documents.requireUserUrl("https://example.com/a", "https://example.com/abc"));
        assertThrows(IllegalArgumentException.class, () -> Documents.requireUserUrl("https://example.com/a", "https://example.com/a?secret=1"));
    }
    @Test public void pdfPageLimit() {
        assertThrows(IllegalArgumentException.class, () -> Documents.pages("x\n".repeat(101), s -> s.length(), 10, 1));
    }
}
