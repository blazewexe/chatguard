package dev.chatguard.detection;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects advertising violations in chat messages, including server IPs,
 * domain names, and Discord invite links.
 * <p>
 * Supports whitelisting of specific domains and Discord invite codes.
 * Matches are attempted against raw, normalized, and stripped forms of the
 * message with decreasing confidence scores.
 */
public class AdvertisingDetector implements ChatDetector {

    private static final Pattern IP_PATTERN = Pattern.compile(
            "(\\d{1,3}[.,]\\d{1,3}[.,]\\d{1,3}[.,]\\d{1,3})(:\\d{2,5})?"
    );

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "([a-z0-9]([a-z0-9\\-]*[a-z0-9])?\\.)"
                    + "(com|net|org|gg|me|io|xyz|tk|ml|ga|cf|co|us|uk|de|ru"
                    + "|info|biz|tv|cc|pw|top|click|link|pro|site|online|store|fun|host|server|play|mc)"
                    + "(:\\d{2,5})?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DISCORD_PATTERN = Pattern.compile(
            "(?:discord(?:\\.gg|\\.com/invite|app\\.com/invite)/|dsc\\.gg/)([a-zA-Z0-9\\-]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "\\b\\d+\\.\\d+(\\.\\d+)?\\b"
    );

    private final double ipThreshold;
    private final double domainThreshold;
    private final double discordThreshold;
    private final Set<String> whitelistedDomains;
    private final Set<String> whitelistedDiscordInvites;

    /**
     * Creates a new advertising detector with the specified thresholds and whitelists.
     *
     * @param ipThreshold              score threshold for IP address violations
     * @param domainThreshold          score threshold for domain name violations
     * @param discordThreshold         score threshold for Discord invite violations
     * @param whitelistedDomains       set of domains that should not trigger violations
     * @param whitelistedDiscordInvites set of Discord invite codes that should not trigger violations
     */
    public AdvertisingDetector(double ipThreshold, double domainThreshold, double discordThreshold,
                               Set<String> whitelistedDomains, Set<String> whitelistedDiscordInvites) {
        this.ipThreshold = ipThreshold;
        this.domainThreshold = domainThreshold;
        this.discordThreshold = discordThreshold;
        this.whitelistedDomains = whitelistedDomains;
        this.whitelistedDiscordInvites = whitelistedDiscordInvites;
    }

    /**
     * Checks a message for advertising content (IPs, domains, Discord invites).
     *
     * @param rawMessage        the original unmodified message
     * @param normalizedMessage the message after homoglyph normalization
     * @param strippedMessage   the message with all symbols and spaces removed
     * @param playerId          the UUID of the sending player
     * @return a {@link DetectionResult} indicating whether advertising was detected
     */
    @Override
    public DetectionResult check(String rawMessage, String normalizedMessage, String strippedMessage, UUID playerId) {
        // Try each message form with decreasing confidence
        DetectionResult result;

        result = scanMessage(rawMessage, 0.95);
        if (result.violated()) {
            return result;
        }

        result = scanMessage(normalizedMessage, 0.8);
        if (result.violated()) {
            return result;
        }

        // Remove separators commonly inserted between characters while preserving
        // dots, slashes, and colons that carry address structure.
        result = scanMessage(removeObfuscatingSeparators(normalizedMessage), 0.8);
        if (result.violated()) {
            return result;
        }

        result = scanMessage(strippedMessage, 0.65);
        if (result.violated()) {
            return result;
        }

        return DetectionResult.clean(ViolationType.ADVERTISING);
    }

    private String removeObfuscatingSeparators(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return message.replaceAll("[\\s-]+", "");
    }

    /**
     * Scans a single message form for all advertising patterns and returns the
     * highest-scoring violation found, or a clean result.
     */
    private DetectionResult scanMessage(String message, double confidence) {
        if (message == null || message.isEmpty()) {
            return DetectionResult.clean(ViolationType.ADVERTISING);
        }

        double bestScore = 0.0;
        String bestDetails = null;
        double bestThreshold = 0.0;

        // Check Discord invites
        Matcher discordMatcher = DISCORD_PATTERN.matcher(message);
        while (discordMatcher.find()) {
            String inviteCode = discordMatcher.group(1);
            if (whitelistedDiscordInvites != null && whitelistedDiscordInvites.contains(inviteCode.toLowerCase())) {
                continue;
            }
            double score = confidence;
            if (score > bestScore) {
                bestScore = score;
                bestDetails = "Discord invite detected: " + discordMatcher.group();
                bestThreshold = discordThreshold;
            }
        }

        // Check domain names
        Matcher domainMatcher = DOMAIN_PATTERN.matcher(message);
        while (domainMatcher.find()) {
            String domain = domainMatcher.group().toLowerCase();
            // Strip port if present
            String domainWithoutPort = domain.replaceAll(":\\d{2,5}$", "");
            if (isWhitelistedDomain(domainWithoutPort)) {
                continue;
            }
            double score = confidence;
            if (score > bestScore) {
                bestScore = score;
                bestDetails = "Domain detected: " + domainMatcher.group();
                bestThreshold = domainThreshold;
            }
        }

        // Check IP addresses
        Matcher ipMatcher = IP_PATTERN.matcher(message);
        while (ipMatcher.find()) {
            String ipMatch = ipMatcher.group(1);

            // Check if this is actually a Minecraft version number
            if (isVersionNumber(message, ipMatcher.start(), ipMatcher.end())) {
                continue;
            }

            // Validate octets — must have exactly 4 dot-separated parts for a real IP
            String[] parts = ipMatch.split("[.,]");
            if (parts.length != 4) {
                continue;
            }
            boolean validOctets = true;
            for (String part : parts) {
                try {
                    int octet = Integer.parseInt(part);
                    if (octet < 0 || octet > 255) {
                        validOctets = false;
                        break;
                    }
                } catch (NumberFormatException e) {
                    validOctets = false;
                    break;
                }
            }
            if (!validOctets) {
                continue;
            }

            double score = confidence;
            if (score > bestScore) {
                bestScore = score;
                bestDetails = "Server IP detected: " + ipMatcher.group();
                bestThreshold = ipThreshold;
            }
        }

        if (bestScore > 0.0 && bestDetails != null) {
            boolean violated = bestScore >= bestThreshold;
            return violated
                    ? DetectionResult.violation(ViolationType.ADVERTISING, bestScore, bestDetails)
                    : DetectionResult.clean(ViolationType.ADVERTISING);
        }

        return DetectionResult.clean(ViolationType.ADVERTISING);
    }

    private boolean isWhitelistedDomain(String domain) {
        if (whitelistedDomains == null) {
            return false;
        }
        return whitelistedDomains.stream()
                .map(String::toLowerCase)
                .anyMatch(allowed -> domain.equals(allowed) || domain.endsWith(allowed));
    }

    /**
     * Determines whether the matched text at the given position in the message
     * is actually a Minecraft version number (e.g., "1.21.4") rather than an IP address.
     */
    private boolean isVersionNumber(String message, int matchStart, int matchEnd) {
        // Extract the portion around the match and check against VERSION_PATTERN
        // We look at a broader window to catch context like "version 1.21.4"
        int windowStart = Math.max(0, matchStart - 20);
        int windowEnd = Math.min(message.length(), matchEnd + 10);
        String window = message.substring(windowStart, windowEnd);

        Matcher versionMatcher = VERSION_PATTERN.matcher(window);
        while (versionMatcher.find()) {
            // Calculate the absolute positions of the version match
            int absStart = windowStart + versionMatcher.start();
            int absEnd = windowStart + versionMatcher.end();

            // If the version pattern overlaps with our IP match, it's likely a version
            if (absStart <= matchStart && absEnd >= matchEnd) {
                return true;
            }
            // Also flag if the IP match is entirely within or covering the version match
            if (matchStart <= absStart && matchEnd >= absEnd) {
                // Check if the matched text has only 2-3 number groups (version) vs 4 (IP)
                String matchedText = message.substring(matchStart, matchEnd);
                long dotCount = matchedText.chars().filter(c -> c == '.' || c == ',').count();
                if (dotCount < 3) {
                    return true;
                }
            }
        }
        return false;
    }
}
