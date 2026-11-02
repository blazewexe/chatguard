package dev.chatguard.detection;

import java.util.UUID;

/**
 * Interface for all chat detection modules.
 * Each detector analyzes a message in multiple forms and returns a scored result.
 */
public interface ChatDetector {

    /**
     * Check a chat message for violations.
     *
     * @param rawMessage        the original unmodified message text
     * @param normalizedMessage the message after Unicode normalization, homoglyph mapping, and leet-speak decoding
     * @param strippedMessage   the normalized message with all non-alphanumeric characters removed
     * @param playerId          the UUID of the player who sent the message
     * @return a DetectionResult with the score and whether the threshold was crossed
     */
    DetectionResult check(String rawMessage, String normalizedMessage,
                          String strippedMessage, UUID playerId);
}

