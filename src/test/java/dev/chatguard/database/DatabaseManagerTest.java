package dev.chatguard.database;

import dev.chatguard.detection.ViolationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    private DatabaseManager databaseManager;
    private File dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        dbFile = tempDir.resolve("test_chatguard.db").toFile();
        databaseManager = new DatabaseManager(dbFile, 500, Logger.getLogger("DatabaseManagerTest"));
        databaseManager.initialize().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    @Test
    @DisplayName("Violations can be logged, queried, counted and cleared")
    void testLogAndQueryViolations() throws Exception {
        UUID playerId = UUID.randomUUID();
        String playerName = "TestPlayer";

        databaseManager.logViolation(
                playerId,
                playerName,
                ViolationType.ADVERTISING,
                0.9,
                "join 192.168.1.1",
                "IP matched"
        );

        // Allow async write to execute
        Thread.sleep(200);

        List<ViolationRecord> violations = databaseManager.getViolations(playerId, 10).get(5, TimeUnit.SECONDS);
        assertFalse(violations.isEmpty(), "Violations list should not be empty");
        ViolationRecord record = violations.get(0);
        assertEquals(playerId.toString(), record.playerId());
        assertEquals(playerName, record.playerName());
        assertEquals(ViolationType.ADVERTISING, record.type());
        assertEquals(0.9, record.score(), 0.001);
        assertEquals("join 192.168.1.1", record.originalMessage());

        int count = databaseManager.getViolationCount().get(5, TimeUnit.SECONDS);
        assertEquals(1, count);

        databaseManager.clearViolations(playerId).get(5, TimeUnit.SECONDS);
        List<ViolationRecord> afterClear = databaseManager.getViolations(playerId, 10).get(5, TimeUnit.SECONDS);
        assertTrue(afterClear.isEmpty());
    }
}
