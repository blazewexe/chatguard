package dev.chatguard.config;

import dev.chatguard.detection.ViolationType;
import dev.chatguard.punishment.PunishmentAction;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable configuration snapshot for ChatGuard.
 * Built once by ConfigManager and swapped atomically on reload.
 */
public record ChatGuardConfig(
        GeneralConfig general,
        AdvertisingConfig advertising,
        SpamConfig spam,
        ToxicityConfig toxicity,
        Map<ViolationType, List<PunishmentAction>> punishments,
        StaffConfig staff,
        DatabaseConfig database
) {

    public record GeneralConfig(
            String prefix,
            long processingBudgetMs,
            boolean debug
    ) {
    }

    public record AdvertisingConfig(
            boolean enabled,
            double ipThreshold,
            double domainThreshold,
            double discordThreshold,
            Set<String> whitelistedDomains,
            Set<String> whitelistedDiscordInvites
    ) {
    }

    public record SpamConfig(
            boolean enabled,
            int duplicateThreshold,
            int duplicateWindowSeconds,
            double cooldownSeconds,
            int rateLimitCount,
            int rateLimitWindowSeconds,
            double similarityThreshold,
            int capsPercentage,
            int capsMinLength
    ) {
    }

    public record ToxicityConfig(
            boolean enabled,
            Set<String> blockedWords,
            List<Pattern> blockedPatterns,
            double threshold
    ) {
    }

    public record StaffConfig(
            String alertPermission,
            String alertFormat
    ) {
    }

    public record DatabaseConfig(
            String file,
            int maxQueueSize
    ) {
    }

    /**
     * Get the punishment action for the given violation type at the given escalation level.
     * Clamps to the last action if the level exceeds the chain length.
     */
    public PunishmentAction getPunishment(ViolationType type, int level) {
        List<PunishmentAction> chain = punishments.get(type);
        if (chain == null || chain.isEmpty()) {
            return new PunishmentAction(PunishmentAction.ActionType.WARN, "<red>Violation detected.", 0);
        }
        int index = Math.min(level, chain.size() - 1);
        return chain.get(index);
    }
}

