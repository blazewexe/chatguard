package dev.chatguard.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdvertisingDetectorTest {

    private AdvertisingDetector detector;
    private final UUID dummyUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        detector = new AdvertisingDetector(
                0.7, // ipThreshold
                0.6, // domainThreshold
                0.8, // discordThreshold
                Set.of("minecraft.net", "youtube.com"), // whitelistedDomains
                Set.of("allowedinvite") // whitelistedDiscordInvites
        );
    }

    private DetectionResult check(String raw) {
        String normalized = TextNormalizer.normalize(raw);
        String stripped = TextNormalizer.stripSymbolsAndSpaces(normalized);
        return detector.check(raw, normalized, stripped, dummyUuid);
    }

    @Test
    @DisplayName("Clean chat message should not be flagged")
    void testCleanMessage() {
        DetectionResult result = check("Hey guys, let's build a castle together!");
        assertFalse(result.violated());
        assertEquals(0.0, result.score());
    }

    @Test
    @DisplayName("Plain IP address is detected")
    void testPlainIp() {
        DetectionResult result = check("Join my server at 192.168.1.50:25565 now!");
        assertTrue(result.violated());
        assertEquals(ViolationType.ADVERTISING, result.type());
        assertTrue(result.score() >= 0.7);
    }

    @Test
    @DisplayName("Domain name is detected")
    void testDomain() {
        DetectionResult result = check("Check out mc.epiccraft.net for cool minigames");
        assertTrue(result.violated());
        assertEquals(ViolationType.ADVERTISING, result.type());
        assertTrue(result.score() >= 0.6);
    }

    @Test
    @DisplayName("Discord invite is detected")
    void testDiscordInvite() {
        DetectionResult result = check("Join our discord: discord.gg/supercoolserver");
        assertTrue(result.violated());
        assertEquals(ViolationType.ADVERTISING, result.type());
        assertTrue(result.score() >= 0.8);
    }

    @Test
    @DisplayName("Whitelisted domain is not flagged")
    void testWhitelistedDomain() {
        DetectionResult result = check("Watch this tutorial at youtube.com/watch?v=123");
        assertFalse(result.violated());
    }

    @Test
    @DisplayName("Minecraft version numbers like 1.21.4 are not flagged as IPs")
    void testVersionNumberExemption() {
        DetectionResult result = check("I am playing on version 1.21.4 and 1.20.1");
        assertFalse(result.violated());
    }
}
