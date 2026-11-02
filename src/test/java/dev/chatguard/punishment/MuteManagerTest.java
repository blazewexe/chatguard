package dev.chatguard.punishment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MuteManagerTest {

    private MuteManager muteManager;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        muteManager = new MuteManager();
    }

    @Test
    @DisplayName("Player is marked muted after mute() and unmuted after unmute()")
    void testMuteAndUnmute() {
        assertFalse(muteManager.isMuted(playerId));

        muteManager.mute(playerId, 10);
        assertTrue(muteManager.isMuted(playerId));
        assertEquals(1, muteManager.getMutedCount());
        assertNotNull(muteManager.getMuteExpiry(playerId));

        muteManager.unmute(playerId);
        assertFalse(muteManager.isMuted(playerId));
        assertEquals(0, muteManager.getMutedCount());
    }

    @Test
    @DisplayName("Expired mute is lazily removed")
    void testExpiredMute() {
        // Mute with 0 duration or negative minutes
        muteManager.mute(playerId, -1);
        assertFalse(muteManager.isMuted(playerId));
    }
}

