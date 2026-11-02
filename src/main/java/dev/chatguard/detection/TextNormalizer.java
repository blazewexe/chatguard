package dev.chatguard.detection;

import java.text.Normalizer;

/**
 * Utility class for normalizing chat messages to defeat evasion techniques.
 * <p>
 * Handles Unicode decomposition, homoglyph substitution (Cyrillic, fullwidth,
 * leet-speak, symbols), and symbol/space stripping in efficient single-pass operations.
 */
public final class TextNormalizer {

    private static final char[] CHAR_MAP = new char[65536];

    static {
        // Identity mapping for all entries
        for (int i = 0; i < CHAR_MAP.length; i++) {
            CHAR_MAP[i] = (char) i;
        }

        // Leet-speak mappings
        CHAR_MAP['1'] = 'i';
        CHAR_MAP['3'] = 'e';
        CHAR_MAP['4'] = 'a';
        CHAR_MAP['0'] = 'o';
        CHAR_MAP['5'] = 's';
        CHAR_MAP['7'] = 't';
        CHAR_MAP['@'] = 'a';
        CHAR_MAP['$'] = 's';
        CHAR_MAP['!'] = 'i';
        CHAR_MAP['|'] = 'l';

        // Cyrillic homoglyphs
        CHAR_MAP['\u0430'] = 'a'; // а
        CHAR_MAP['\u0410'] = 'a'; // А
        CHAR_MAP['\u0432'] = 'b'; // в
        CHAR_MAP['\u0412'] = 'b'; // В
        CHAR_MAP['\u0441'] = 'c'; // с
        CHAR_MAP['\u0421'] = 'c'; // С
        CHAR_MAP['\u0435'] = 'e'; // е
        CHAR_MAP['\u0415'] = 'e'; // Е
        CHAR_MAP['\u043D'] = 'h'; // н
        CHAR_MAP['\u041D'] = 'h'; // Н
        CHAR_MAP['\u043A'] = 'k'; // к
        CHAR_MAP['\u041A'] = 'k'; // К
        CHAR_MAP['\u043C'] = 'm'; // м
        CHAR_MAP['\u041C'] = 'm'; // М
        CHAR_MAP['\u043E'] = 'o'; // о
        CHAR_MAP['\u041E'] = 'o'; // О
        CHAR_MAP['\u0440'] = 'p'; // р
        CHAR_MAP['\u0420'] = 'p'; // Р
        CHAR_MAP['\u0442'] = 't'; // т
        CHAR_MAP['\u0422'] = 't'; // Т
        CHAR_MAP['\u0443'] = 'y'; // у
        CHAR_MAP['\u0423'] = 'y'; // У
        CHAR_MAP['\u0445'] = 'x'; // х
        CHAR_MAP['\u0425'] = 'x'; // Х
        CHAR_MAP['\u0455'] = 's'; // ѕ
        CHAR_MAP['\u0405'] = 's'; // Ѕ
        CHAR_MAP['\u0456'] = 'i'; // і
        CHAR_MAP['\u0406'] = 'i'; // І
        CHAR_MAP['\u0458'] = 'j'; // ј
        CHAR_MAP['\u0408'] = 'j'; // Ј

        // Fullwidth uppercase A-Z (U+FF21 to U+FF3A) → a-z
        for (int i = 0; i < 26; i++) {
            CHAR_MAP[0xFF21 + i] = (char) ('a' + i);
        }

        // Fullwidth lowercase a-z (U+FF41 to U+FF5A) → a-z
        for (int i = 0; i < 26; i++) {
            CHAR_MAP[0xFF41 + i] = (char) ('a' + i);
        }

        // Fullwidth digits 0-9 (U+FF10 to U+FF19) → 0-9
        for (int i = 0; i < 10; i++) {
            CHAR_MAP[0xFF10 + i] = (char) ('0' + i);
        }

        // Common symbols
        CHAR_MAP['\u212E'] = 'e'; // ℮
        CHAR_MAP['\u2080'] = '0'; // ₀
        CHAR_MAP['\u2081'] = '1'; // ₁
        CHAR_MAP['\u2082'] = '2'; // ₂
        CHAR_MAP['\u2083'] = '3'; // ₃
        CHAR_MAP['\u2084'] = '4'; // ₄
        CHAR_MAP['\u2085'] = '5'; // ₅
        CHAR_MAP['\u2086'] = '6'; // ₆
        CHAR_MAP['\u2087'] = '7'; // ₇
        CHAR_MAP['\u2088'] = '8'; // ₈
        CHAR_MAP['\u2089'] = '9'; // ₉
        CHAR_MAP['\u2460'] = '1'; // ①
        CHAR_MAP['\u2461'] = '2'; // ②
        CHAR_MAP['\u2462'] = '3'; // ③
        CHAR_MAP['\u2463'] = '4'; // ④
        CHAR_MAP['\u2464'] = '5'; // ⑤
        CHAR_MAP['\u2465'] = '6'; // ⑥
        CHAR_MAP['\u2466'] = '7'; // ⑦
        CHAR_MAP['\u2467'] = '8'; // ⑧
        CHAR_MAP['\u2468'] = '9'; // ⑨
        CHAR_MAP['\u24EA'] = '0'; // ⓪
        CHAR_MAP['\u2070'] = '0'; // ⁰
        CHAR_MAP['\u00B9'] = '1'; // ¹
        CHAR_MAP['\u00B2'] = '2'; // ²
        CHAR_MAP['\u00B3'] = '3'; // ³
        CHAR_MAP['\u2074'] = '4'; // ⁴
        CHAR_MAP['\u2075'] = '5'; // ⁵
        CHAR_MAP['\u2076'] = '6'; // ⁶
        CHAR_MAP['\u2077'] = '7'; // ⁷
        CHAR_MAP['\u2078'] = '8'; // ⁸
        CHAR_MAP['\u2079'] = '9'; // ⁹
    }

    private TextNormalizer() {
        // Utility class — no instantiation
    }

    /**
     * Normalizes a chat message by applying Unicode NFKD decomposition, homoglyph
     * mapping, combining-mark removal, and lowercasing in a single pass.
     *
     * @param input the raw chat message
     * @return the normalized lowercase string with homoglyphs resolved, or an empty
     *         string if input is null or empty
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // Apply NFKD decomposition first
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);

        // Single pass: skip combining marks, map homoglyphs, lowercase
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);

            // Skip combining marks (diacritics)
            int type = Character.getType(c);
            if (type == Character.COMBINING_SPACING_MARK
                    || type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }

            // Map through homoglyph table (only BMP chars)
            char mapped = (c < CHAR_MAP.length) ? CHAR_MAP[c] : c;

            // Lowercase
            sb.append(Character.toLowerCase(mapped));
        }

        return sb.toString();
    }

    /**
     * Strips all non-alphanumeric characters from the input, keeping only
     * ASCII letters (a-z, A-Z) and digits (0-9).
     *
     * @param input the string to strip
     * @return a string containing only alphanumeric characters
     */
    public static String stripSymbolsAndSpaces(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
