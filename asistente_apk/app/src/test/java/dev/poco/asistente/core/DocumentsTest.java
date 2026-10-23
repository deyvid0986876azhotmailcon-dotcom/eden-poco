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
    @Test public void blocksRecognizeTitlesBulletsAndAmountRows() {
        java.util.List<Documents.Block> b = Documents.blocks("# Gastos\n- Comida: ₲ 25.000\nTransporte — ₲ 10.000\n- pan y leche\n\nTotal: US$ 10,50\nHora: 15:00\n#");
        assertEquals('h', b.get(0).kind); assertEquals("Gastos", b.get(0).text);
        assertEquals('r', b.get(1).kind); assertEquals("Comida", b.get(1).text); assertEquals("₲ 25.000", b.get(1).amount);
        assertEquals('r', b.get(2).kind); assertEquals("₲ 10.000", b.get(2).amount);
        assertEquals('b', b.get(3).kind); assertEquals("pan y leche", b.get(3).text);
        assertEquals(' ', b.get(4).kind);
        assertEquals("US$ 10,50", b.get(5).amount);
        assertEquals('p', b.get(6).kind); // a time is not an amount
        assertEquals('p', b.get(7).kind);
    }
    @Test public void wrapKeepsEveryWord() {
        assertEquals(java.util.Arrays.asList("uno dos", "tres"), Documents.wrap("uno dos tres", s -> s.length(), 7));
    }
    @Test public void pdfPageLimit() {
        assertThrows(IllegalArgumentException.class, () -> Documents.pages("x\n".repeat(101), s -> s.length(), 10, 1));
    }
}
