package dev.chatguard.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpamDetectorTest {

    private SpamDetector detector;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        detector = new SpamDetector(
                3,    // duplicateThreshold
                30,   // duplicateWindowSeconds
                1.0,  // cooldownSeconds
                5,    // rateLimitCount
                10,   // rateLimitWindowSeconds
                0.85, // similarityThreshold
                70,   // capsPercentage
                6     // capsMinLength
        );
    }

    private DetectionResult check(String raw) {
        String normalized = TextNormalizer.normalize(raw);
        String stripped = TextNormalizer.stripSymbolsAndSpaces(normalized);
        return detector.check(raw, normalized, stripped, playerId);
    }

    @Test
    @DisplayName("Single normal message is clean")
    void testSingleNormalMessage() {
        DetectionResult result = check("Hello everyone, how are you doing today?");
        assertFalse(result.violated());
    }

    @Test
    @DisplayName("Excessive caps trigger violation")
    void testExcessiveCaps() {
        DetectionResult result = check("PLEASE COME TO MY BASE RIGHT NOW IMMEDIATELY!!!");
        assertTrue(result.violated() || result.score() > 0.0);
        assertTrue(result.details().contains("caps") || result.score() >= 0.3);
    }

    @Test
    @DisplayName("Message cooldown triggers if sent too rapidly")
    void testMessageCooldown() {
        check("First message");
        // Immediate second message (within 1 second)
        DetectionResult result = check("Second message immediately after");
        assertTrue(result.score() > 0.0, "Cooldown should add to score");
        assertTrue(result.details().contains("cooldown"));
    }

    @Test
    @DisplayName("Duplicate messages trigger spam threshold")
    void testDuplicateSpam() {
        check("spam message test");
        check("spam message test");
        DetectionResult result3 = check("spam message test");
        assertTrue(result3.violated(), "Third identical message within window should trigger violation");
        assertEquals(ViolationType.SPAM, result3.type());
    }
}
