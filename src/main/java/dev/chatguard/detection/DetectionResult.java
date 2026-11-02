package dev.chatguard.detection;

/**
 * Result of a detection check. Contains the violation type, confidence score,
 * human-readable details, and whether the threshold was crossed.
 */
public record DetectionResult(
        ViolationType type,
        double score,
        String details,
        boolean violated
) {

    /**
     * Create a clean (no violation) result.
     */
    public static DetectionResult clean(ViolationType type) {
        return new DetectionResult(type, 0.0, null, false);
    }

    /**
     * Create a violation result with the given score and details.
     */
    public static DetectionResult violation(ViolationType type, double score, String details) {
        return new DetectionResult(type, score, details, true);
    }
}

