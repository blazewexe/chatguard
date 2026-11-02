package dev.chatguard.detection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects toxic language in chat messages using a combination of word-list
 * matching and configurable regex patterns.
 * <p>
 * Scoring accounts for word length to reduce false positives from ambiguous
 * short tokens, and aggregates across multiple matches with a clamped ceiling.
 */
public class ToxicityDetector implements ChatDetector {

    private final Set<String> blockedWords;
    private final List<Pattern> blockedPatterns;
    private final double threshold;

    /**
     * Creates a new toxicity detector.
     *
     * @param blockedWords   set of blocked words (will be stored as lowercase)
     * @param blockedPatterns list of compiled regex patterns to match against messages
     * @param threshold      score threshold (0.0–1.0) above which a violation is triggered
     */
    public ToxicityDetector(Set<String> blockedWords, List<Pattern> blockedPatterns, double threshold) {
        this.blockedWords = new HashSet<>();
        if (blockedWords != null) {
            for (String word : blockedWords) {
                this.blockedWords.add(word.toLowerCase());
            }
        }
        this.blockedPatterns = blockedPatterns != null ? blockedPatterns : List.of();
        this.threshold = threshold;
    }

    /**
     * Checks a message for toxic content by matching individual tokens against
     * the blocked word list and running regex patterns over the normalized message.
     *
     * @param rawMessage        the original unmodified message
     * @param normalizedMessage the message after normalization
     * @param strippedMessage   the message with symbols and spaces removed
     * @param playerId          the UUID of the sending player
     * @return a {@link DetectionResult} indicating whether toxicity was detected
     */
    @Override
    public DetectionResult check(String rawMessage, String normalizedMessage, String strippedMessage, UUID playerId) {
        if (normalizedMessage == null || normalizedMessage.isEmpty()) {
            return DetectionResult.clean(ViolationType.TOXICITY);
        }

        double score = 0.0;
        List<String> matchedItems = new ArrayList<>();

        // 1. Word matching — split normalized message into tokens
        String[] tokens = normalizedMessage.split("[^a-zA-Z0-9]+");
        int wordMatchCount = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase();
            if (blockedWords.contains(lower)) {
                wordMatchCount++;
                if (wordMatchCount == 1) {
                    // First match — score depends on word length
                    if (lower.length() >= 4) {
                        score += 0.4;
                    } else {
                        score += 0.2;
                    }
                } else {
                    // Additional matches
                    score += 0.3;
                }
                matchedItems.add("word: " + lower);
            }
        }

        // 2. Pattern matching
        for (Pattern pattern : blockedPatterns) {
            Matcher matcher = pattern.matcher(normalizedMessage);
            if (matcher.find()) {
                score += 0.65;
                matchedItems.add("pattern: " + pattern.pattern());
            }
        }

        // 3. Clamp score to 1.0
        double clampedScore = Math.min(score, 1.0);

        // 4. Return result
        boolean violated = clampedScore >= threshold && !matchedItems.isEmpty();
        String details = matchedItems.isEmpty() ? null : "Matched: " + String.join(", ", matchedItems);
        return new DetectionResult(ViolationType.TOXICITY, clampedScore, details, violated);
    }
}
