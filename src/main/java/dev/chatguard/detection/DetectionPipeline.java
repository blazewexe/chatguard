package dev.chatguard.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates multiple {@link ChatDetector} instances with a strict time budget.
 * <p>
 * Detectors are executed sequentially in registration order. If the time budget
 * is exceeded, remaining detectors are skipped (fail-safe: unprocessed messages
 * are allowed through).
 */
public class DetectionPipeline {

    private final long budgetMs;
    private final List<ChatDetector> detectors;

    /**
     * Creates a new detection pipeline with the specified time budget and detectors.
     *
     * @param budgetMs  maximum time (milliseconds) to spend running all detectors
     * @param detectors the detectors to execute, in priority order
     */
    public DetectionPipeline(long budgetMs, ChatDetector... detectors) {
        this.budgetMs = budgetMs;
        this.detectors = List.of(detectors);
    }

    /**
     * Analyzes a raw chat message through all registered detectors within the
     * time budget. The message is normalized and stripped once, then passed to
     * each detector.
     *
     * @param rawMessage the original unmodified chat message
     * @param playerId   the UUID of the sending player
     * @return a list of {@link DetectionResult} from all detectors that ran
     *         (both clean and violated); detectors skipped due to budget
     *         exhaustion are not included
     */
    public List<DetectionResult> analyze(String rawMessage, UUID playerId) {
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;

        // Normalize once for all detectors
        String normalized = TextNormalizer.normalize(rawMessage);
        String stripped = TextNormalizer.stripSymbolsAndSpaces(normalized);

        List<DetectionResult> results = new ArrayList<>(detectors.size());

        for (ChatDetector detector : detectors) {
            if (System.nanoTime() >= deadlineNanos) {
                // Budget exhausted — skip remaining detectors (fail-safe: allow)
                break;
            }
            DetectionResult result = detector.check(rawMessage, normalized, stripped, playerId);
            results.add(result);
        }

        return results;
    }

    /**
     * Shuts down the pipeline and releases any owned resources.
     * Currently a no-op as the pipeline holds no closeable resources.
     */
    public void shutdown() {
        // No owned resources to release
    }
}
