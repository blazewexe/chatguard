package dev.chatguard.punishment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe manager for tracking temporarily muted players.
 * <p>
 * Mutes are stored in-memory with an expiry timestamp. Expired mutes are lazily evicted
 * when checked via {@link #isMuted(UUID)} or {@link #getMutedCount()}.
 */
public class MuteManager {

    private final ConcurrentHashMap<UUID, Instant> mutedPlayers = new ConcurrentHashMap<>();

    /**
     * Mutes a player for the specified duration.
     *
     * @param playerId        the UUID of the player to mute
     * @param durationMinutes the mute duration in minutes; if ≤ 0 the mute is effectively
     *                        instant and will expire on the next check
     */
    public void mute(UUID playerId, int durationMinutes) {
        Instant expiry = Instant.now().plus(durationMinutes, ChronoUnit.MINUTES);
        mutedPlayers.put(playerId, expiry);
    }

    /**
     * Removes a mute for the specified player, regardless of whether it has expired.
     *
     * @param playerId the UUID of the player to unmute
     */
    public void unmute(UUID playerId) {
        mutedPlayers.remove(playerId);
    }

    /**
     * Checks whether a player is currently muted.
     * <p>
     * If the mute has expired, the entry is removed and {@code false} is returned.
     *
     * @param playerId the UUID of the player
     * @return {@code true} if the player is muted and the mute has not expired
     */
    public boolean isMuted(UUID playerId) {
        Instant expiry = mutedPlayers.get(playerId);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            mutedPlayers.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * Returns the mute expiry instant for a player, or {@code null} if the player is not muted.
     * <p>
     * Note: this does <b>not</b> lazily evict expired entries — it returns the raw expiry
     * even if it is in the past. Use {@link #isMuted(UUID)} for an up-to-date check.
     *
     * @param playerId the UUID of the player
     * @return the expiry {@link Instant}, or {@code null} if not muted
     */
    public Instant getMuteExpiry(UUID playerId) {
        return mutedPlayers.get(playerId);
    }

    /**
     * Returns the number of currently muted (non-expired) players.
     * <p>
     * This method iterates all entries and lazily evicts any that have expired.
     *
     * @return the count of actively muted players
     */
    public int getMutedCount() {
        Instant now = Instant.now();
        mutedPlayers.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
        return mutedPlayers.size();
    }
}
