package dev.chatguard.punishment;

import dev.chatguard.detection.ViolationType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe tracker for per-player, per-violation-type offense counts.
 * <p>
 * Used by the punishment system to determine the current escalation level for each player.
 * Counts are maintained in memory and reset on server restart or manual clear.
 */
public class ViolationTracker {

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<ViolationType, AtomicInteger>> playerViolations =
            new ConcurrentHashMap<>();

    /**
     * Increments the violation count for the given player and violation type, and returns
     * the new count.
     * <p>
     * The returned value is 1-indexed (first offense returns 1). Callers that need a
     * 0-indexed escalation level should subtract 1.
     *
     * @param playerId the UUID of the player
     * @param type     the type of violation
     * @return the new violation count after incrementing (1-indexed)
     */
    public int incrementAndGet(UUID playerId, ViolationType type) {
        ConcurrentHashMap<ViolationType, AtomicInteger> typeMap =
                playerViolations.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        AtomicInteger counter = typeMap.computeIfAbsent(type, k -> new AtomicInteger(0));
        return counter.incrementAndGet();
    }

    /**
     * Returns the current violation count for the given player and violation type
     * without incrementing.
     *
     * @param playerId the UUID of the player
     * @param type     the type of violation
     * @return the current count (0 if no violations recorded)
     */
    public int getLevel(UUID playerId, ViolationType type) {
        ConcurrentHashMap<ViolationType, AtomicInteger> typeMap = playerViolations.get(playerId);
        if (typeMap == null) {
            return 0;
        }
        AtomicInteger counter = typeMap.get(type);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Removes all tracked violation data for a specific player.
     *
     * @param playerId the UUID of the player to clear
     */
    public void clearPlayer(UUID playerId) {
        playerViolations.remove(playerId);
    }

    /**
     * Clears all tracked violation data for every player.
     */
    public void clear() {
        playerViolations.clear();
    }

    /**
     * Returns the number of distinct players currently being tracked.
     *
     * @return the count of tracked players
     */
    public int getTrackedPlayerCount() {
        return playerViolations.size();
    }
}
