package dev.chatguard.punishment;

/**
 * Represents a single punishment action in the escalation chain.
 *
 * @param type            the type of punishment to apply
 * @param message         the message to show the player (MiniMessage format)
 * @param durationMinutes duration in minutes (for MUTE and BAN), 0 for WARN and KICK
 */
public record PunishmentAction(
        ActionType type,
        String message,
        int durationMinutes
) {

    /**
     * Types of punishment actions, ordered by severity.
     */
    public enum ActionType {
        WARN,
        MUTE,
        KICK,
        BAN
    }
}

