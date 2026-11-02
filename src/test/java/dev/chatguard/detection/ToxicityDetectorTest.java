package dev.chatguard.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ToxicityDetectorTest {

    private ToxicityDetector detector;
    private final UUID dummyUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        detector = new ToxicityDetector(
                Set.of("toxicword", "badword", "idiot"),
                List.of(Pattern.compile("k+[i1!]+l+l*\\s*(y+o+u+r+)?\\s*s+e+l+f+", Pattern.CASE_INSENSITIVE)),
                0.6 // threshold
        );
    }

    private DetectionResult check(String raw) {
        String normalized = TextNormalizer.normalize(raw);
        String stripped = TextNormalizer.stripSymbolsAndSpaces(normalized);
        return detector.check(raw, normalized, stripped, dummyUuid);
    }

    @Test
    @DisplayName("Clean chat message should not be flagged as toxic")
    void testCleanMessage() {
        DetectionResult result = check("Good game everyone, nicely played!");
        assertFalse(result.violated());
        assertEquals(0.0, result.score());
    }

    @Test
    @DisplayName("Single blocked word is detected")
    void testBlockedWord() {
        // "toxicword" length is 9 >= 4 -> score 0.4. If paired with another word -> exceeds 0.6
        DetectionResult result = check("You are such a toxicword and a badword");
        assertTrue(result.violated());
        assertEquals(ViolationType.TOXICITY, result.type());
        assertTrue(result.score() >= 0.6);
    }

    @Test
    @DisplayName("Regex pattern match triggers toxicity violation")
    void testPatternMatch() {
        DetectionResult result = check("go k1ll yourself right now");
        assertTrue(result.violated());
        assertEquals(ViolationType.TOXICITY, result.type());
        assertTrue(result.score() >= 0.5);
    }

    @Test
    @DisplayName("Single ambiguous word does not unfairly punish player")
    void testSingleAmbiguousKeywordNoFalsePunish() {
        // "idiot" is a single word -> score 0.4 < 0.6 threshold
        DetectionResult result = check("You are being an idiot");
        assertFalse(result.violated(), "Single ambiguous insult should not cross the strict 0.6 threshold alone");
        assertTrue(result.score() > 0.0);
    }
}
