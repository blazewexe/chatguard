package dev.chatguard.punishment;

import dev.chatguard.detection.ViolationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ViolationTrackerTest {

    private ViolationTracker tracker;
    private final UUID player1 = UUID.randomUUID();
    private final UUID player2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tracker = new ViolationTracker();
    }

    @Test
    @DisplayName("Incrementing violation counts increases escalation level")
    void testEscalationIncrements() {
        assertEquals(0, tracker.getLevel(player1, ViolationType.ADVERTISING));

        int first = tracker.incrementAndGet(player1, ViolationType.ADVERTISING);
        assertEquals(1, first);
        assertEquals(1, tracker.getLevel(player1, ViolationType.ADVERTISING));

        int second = tracker.incrementAndGet(player1, ViolationType.ADVERTISING);
        assertEquals(2, second);
        assertEquals(2, tracker.getLevel(player1, ViolationType.ADVERTISING));

        // Different violation type is independent
        assertEquals(0, tracker.getLevel(player1, ViolationType.SPAM));
    }

    @Test
    @DisplayName("Clearing player removes all their violation data")
    void testClearPlayer() {
        tracker.incrementAndGet(player1, ViolationType.TOXICITY);
        tracker.incrementAndGet(player2, ViolationType.TOXICITY);
        assertEquals(2, tracker.getTrackedPlayerCount());

        tracker.clearPlayer(player1);
        assertEquals(0, tracker.getLevel(player1, ViolationType.TOXICITY));
        assertEquals(1, tracker.getLevel(player2, ViolationType.TOXICITY));
    }
}

