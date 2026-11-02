package dev.chatguard.database;

import dev.chatguard.detection.ViolationType;

/**
 * Represents a single violation record from the database.
 *
 * @param id              auto-incremented row ID
 * @param playerId        UUID string of the violating player
 * @param playerName      display name at the time of violation
 * @param type            the type of violation
 * @param score           detection confidence score (0.0 - 1.0)
 * @param originalMessage the original unmodified chat message (evidence)
 * @param details         human-readable detection details
 * @param timestamp       epoch milliseconds when the violation occurred
 */
public record ViolationRecord(
        long id,
        String playerId,
        String playerName,
        ViolationType type,
        double score,
        String originalMessage,
        String details,
        long timestamp
) {
}

