package dev.chatguard.detection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest {

    @Test
    @DisplayName("Null or empty string normalization returns empty string")
    void testEmptyAndNull() {
        assertEquals("", TextNormalizer.normalize(null));
        assertEquals("", TextNormalizer.normalize(""));
        assertEquals("", TextNormalizer.stripSymbolsAndSpaces(null));
        assertEquals("", TextNormalizer.stripSymbolsAndSpaces(""));
    }

    @Test
    @DisplayName("Plain text is lowercased")
    void testBasicLowercase() {
        assertEquals("hello world", TextNormalizer.normalize("Hello World"));
        assertEquals("minecraft server", TextNormalizer.normalize("MINECRAFT SERVER"));
    }

    @Test
    @DisplayName("Leet speak digits and symbols are converted to letters")
    void testLeetSpeak() {
        // 1->i, 3->e, 4->a, 0->o, 5->s, 7->t, @->a, $->s, !->i
        assertEquals("hello", TextNormalizer.normalize("h3ll0"));

        String leet = TextNormalizer.normalize("pl4y.m1n3cr4ft.n3t");
        assertEquals("play.minecraft.net", leet);
        assertTrue(leet.contains("play") && leet.contains("net"),
                "Expected decoded leet speak but got: " + leet);
    }

    @Test
    @DisplayName("Cyrillic homoglyphs are mapped to ASCII equivalents")
    void testHomoglyphs() {
        // 'а' (Cyrillic a \u0430) -> 'a'
        // 'с' (Cyrillic s/c \u0441) -> 'c'
        // 'е' (Cyrillic e \u0435) -> 'e'
        // 'о' (Cyrillic o \u043E) -> 'o'
        // 'р' (Cyrillic p \u0440) -> 'p'
        String cyrillicWord = "\u0440\u0430\u0441\u0435\u043E"; // р, а, с, е, о
        String normalized = TextNormalizer.normalize(cyrillicWord);
        assertEquals("paceo", normalized);
    }

    @Test
    @DisplayName("stripSymbolsAndSpaces removes spaces, punctuation and dots")
    void testStripSymbols() {
        assertEquals("playminecraftnet", TextNormalizer.stripSymbolsAndSpaces("p l a y . m i n e c r a f t . n e t"));
        assertEquals("discordggtest", TextNormalizer.stripSymbolsAndSpaces("d-i-s-c-o-r-d . g g / t e s t"));
        assertEquals("19216811", TextNormalizer.stripSymbolsAndSpaces("1 9 2 . 1 6 8 . 1 . 1"));
    }
}
