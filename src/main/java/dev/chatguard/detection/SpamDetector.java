package dev.chatguard.detection;

import dev.chatguard.util.BoundedCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects spam behaviour in chat messages using multiple heuristics:
 * message cooldown, rate limiting, duplicate detection, and excessive caps.
 * <p>
 * This detector is thread-safe — all access to shared player history is
 * synchronized through a {@link Collections#synchronizedMap} wrapper.
 */
public class SpamDetector implements ChatDetector {

    private record TimestampedMessage(String normalizedText, long timestampMs) {
    }

    private final int duplicateThreshold;
    private final int duplicateWindowSeconds;
    private final double cooldownSeconds;
    private final int rateLimitCount;
    private final int rateLimitWindowSeconds;
    private final double similarityThreshold;
    private final int capsPercentage;
    private final int capsMinLength;

    private final Map<UUID, Deque<TimestampedMessage>> messageHistory;

    /**
     * Creates a new spam detector with the specified parameters.
     *
     * @param duplicateThreshold     number of similar messages in the window to trigger duplicate detection
     * @param duplicateWindowSeconds time window (seconds) for duplicate checking
     * @param cooldownSeconds        minimum seconds between messages before cooldown triggers
     * @param rateLimitCount         maximum messages allowed within the rate limit window
     * @param rateLimitWindowSeconds time window (seconds) for rate limiting
     * @param similarityThreshold   minimum similarity ratio (0.0–1.0) for messages to be considered duplicates
     * @param capsPercentage         uppercase percentage threshold to trigger caps detection
     * @param capsMinLength          minimum message length for caps detection to apply
     */
    public SpamDetector(int duplicateThreshold, int duplicateWindowSeconds, double cooldownSeconds,
                        int rateLimitCount, int rateLimitWindowSeconds, double similarityThreshold,
                        int capsPercentage, int capsMinLength) {
        this.duplicateThreshold = duplicateThreshold;
        this.duplicateWindowSeconds = duplicateWindowSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.rateLimitCount = rateLimitCount;
        this.rateLimitWindowSeconds = rateLimitWindowSeconds;
        this.similarityThreshold = similarityThreshold;
        this.capsPercentage = capsPercentage;
        this.capsMinLength = capsMinLength;

        this.messageHistory = Collections.synchronizedMap(new BoundedCache<>(1000));
    }

    /**
     * Checks a message for spam indicators including cooldown violations,
     * rate limit breaches, duplicate messages, and excessive capitalization.
     *
     * @param rawMessage        the original unmodified message
     * @param normalizedMessage the message after normalization
     * @param strippedMessage   the message with symbols and spaces removed
     * @param playerId          the UUID of the sending player
     * @return a {@link DetectionResult} indicating whether spam was detected
     */
    @Override
    public DetectionResult check(String rawMessage, String normalizedMessage, String strippedMessage, UUID playerId) {
        long now = System.currentTimeMillis();
        double score = 0.0;
        List<String> details = new ArrayList<>(4);

        synchronized (messageHistory) {
            Deque<TimestampedMessage> history = messageHistory.computeIfAbsent(playerId, k -> new ArrayDeque<>());

            // 1. Cooldown check
            if (!history.isEmpty()) {
                TimestampedMessage last = history.peekLast();
                long elapsed = now - last.timestampMs();
                if (elapsed < (long) (cooldownSeconds * 1000)) {
                    score += 0.4;
                    details.add("message cooldown");
                }
            }

            // 2. Rate limit check
            long rateLimitCutoff = now - (rateLimitWindowSeconds * 1000L);
            int rateCount = 0;
            for (TimestampedMessage msg : history) {
                if (msg.timestampMs() >= rateLimitCutoff) {
                    rateCount++;
                }
            }
            if (rateCount >= rateLimitCount) {
                score += 0.5;
                details.add("rate limit exceeded");
            }

            // 3. Duplicate check
            long duplicateCutoff = now - (duplicateWindowSeconds * 1000L);
            int dupeCount = 0;
            for (TimestampedMessage msg : history) {
                if (msg.timestampMs() >= duplicateCutoff) {
                    if (similarity(msg.normalizedText(), normalizedMessage) >= similarityThreshold) {
                        dupeCount++;
                    }
                }
            }
            if (dupeCount + 1 >= duplicateThreshold) {
                score += 0.6;
                details.add("duplicate messages");
            }

            // 4. Caps check
            if (rawMessage != null && rawMessage.length() >= capsMinLength) {
                int upperCount = 0;
                int letterCount = 0;
                for (int i = 0; i < rawMessage.length(); i++) {
                    char c = rawMessage.charAt(i);
                    if (Character.isLetter(c)) {
                        letterCount++;
                        if (Character.isUpperCase(c)) {
                            upperCount++;
                        }
                    }
                }
                if (letterCount > 0) {
                    int pct = (int) ((upperCount * 100L) / letterCount);
                    if (pct >= capsPercentage) {
                        score += 0.3;
                        details.add("excessive caps");
                    }
                }
            }

            // 5. Add current message to history
            history.addLast(new TimestampedMessage(normalizedMessage, now));

            // 6. Clean old entries
            long maxWindowMs = Math.max(duplicateWindowSeconds, rateLimitWindowSeconds) * 1000L;
            long cleanCutoff = now - maxWindowMs;
            while (!history.isEmpty() && history.peekFirst().timestampMs() < cleanCutoff) {
                history.pollFirst();
            }
        }

        // 7. Return result
        double clampedScore = Math.min(score, 1.0);
        boolean violated = clampedScore >= 0.5;
        String detailsStr = details.isEmpty() ? null : String.join(", ", details);
        return new DetectionResult(ViolationType.SPAM, clampedScore, detailsStr, violated);
    }

    /**
     * Computes the similarity between two strings using bounded Levenshtein distance.
     * Uses O(min(m, n)) space with a two-array DP approach.
     *
     * @param a first string
     * @param b second string
     * @return similarity ratio between 0.0 (completely different) and 1.0 (identical)
     */
    private double similarity(String a, String b) {
        if (a == null && b == null) return 1.0;
        if (a == null || b == null) return 0.0;
        if (a.equals(b)) return 1.0;

        int m = a.length();
        int n = b.length();

        if (m == 0 || n == 0) {
            return 0.0;
        }

        // Ensure a is the shorter string for O(min(m,n)) space
        if (m > n) {
            String tmp = a;
            a = b;
            b = tmp;
            int t = m;
            m = n;
            n = t;
        }

        // Two-row DP — prev and curr are length m+1
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];

        // Initialize base case
        for (int i = 0; i <= m; i++) {
            prev[i] = i;
        }

        for (int j = 1; j <= n; j++) {
            curr[0] = j;
            for (int i = 1; i <= m; i++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[i] = Math.min(
                        Math.min(curr[i - 1] + 1, prev[i] + 1),
                        prev[i - 1] + cost
                );
            }
            // Swap arrays
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        int distance = prev[m];
        int maxLen = Math.max(a.length(), b.length());
        return 1.0 - ((double) distance / maxLen);
    }
}
