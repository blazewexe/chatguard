package dev.chatguard.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import dev.chatguard.punishment.PunishmentAction;
import dev.chatguard.punishment.PunishmentAction.ActionType;
import dev.chatguard.detection.ViolationType;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manages loading and reloading of plugin configuration from config.yml.
 * <p>
 * Provides thread-safe access to the current {@link ChatGuardConfig} via a volatile field,
 * allowing hot-reloading without restarting the plugin.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private volatile ChatGuardConfig config;

    /**
     * Constructs a new ConfigManager.
     *
     * @param plugin the owning JavaPlugin instance
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the current immutable configuration snapshot.
     *
     * @return the current {@link ChatGuardConfig}
     */
    public ChatGuardConfig getConfig() {
        return config;
    }

    /**
     * Loads (or reloads) the configuration from disk.
     * <p>
     * Saves the default config.yml if it does not exist, reloads the YAML from disk,
     * parses every section, and publishes a new {@link ChatGuardConfig} instance.
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration yaml = plugin.getConfig();
        this.config = parseConfig(yaml);
    }

    /**
     * Alias for {@link #loadConfig()} — reloads configuration from disk.
     */
    public void reloadConfig() {
        loadConfig();
    }

    // -------------------------------------------------------------------------
    // Internal parsing
    // -------------------------------------------------------------------------

    private ChatGuardConfig parseConfig(FileConfiguration yaml) {
        ChatGuardConfig.GeneralConfig general = parseGeneral(yaml.getConfigurationSection("general"));
        ChatGuardConfig.AdvertisingConfig advertising = parseAdvertising(yaml.getConfigurationSection("advertising"));
        ChatGuardConfig.SpamConfig spam = parseSpam(yaml.getConfigurationSection("spam"));
        ChatGuardConfig.ToxicityConfig toxicity = parseToxicity(yaml.getConfigurationSection("toxicity"));
        Map<ViolationType, List<PunishmentAction>> punishments = parsePunishments(yaml.getConfigurationSection("punishments"));
        ChatGuardConfig.StaffConfig staff = parseStaff(yaml.getConfigurationSection("staff"));
        ChatGuardConfig.DatabaseConfig database = parseDatabase(yaml.getConfigurationSection("database"));

        return new ChatGuardConfig(general, advertising, spam, toxicity, punishments, staff, database);
    }

    private ChatGuardConfig.GeneralConfig parseGeneral(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.GeneralConfig("<gradient:#ff6b6b:#ee5a24>[ChatGuard]</gradient> ", 50L, false);
        }
        String prefix = section.getString("prefix", "<gradient:#ff6b6b:#ee5a24>[ChatGuard]</gradient> ");
        long processingBudgetMs = section.getLong("processing-budget-ms", 50L);
        boolean debug = section.getBoolean("debug", false);
        return new ChatGuardConfig.GeneralConfig(prefix, processingBudgetMs, debug);
    }

    private ChatGuardConfig.AdvertisingConfig parseAdvertising(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.AdvertisingConfig(true, 0.7, 0.6, 0.8,
                    Collections.emptySet(), Collections.emptySet());
        }
        boolean enabled = section.getBoolean("enabled", true);
        double ipThreshold = section.getDouble("ip-threshold", 0.7);
        double domainThreshold = section.getDouble("domain-threshold", 0.6);
        double discordThreshold = section.getDouble("discord-threshold", 0.8);

        Set<String> whitelistedDomains = section.getStringList("whitelisted-domains")
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> whitelistedDiscordInvites = section.getStringList("whitelisted-discord-invites")
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ChatGuardConfig.AdvertisingConfig(enabled, ipThreshold, domainThreshold, discordThreshold,
                whitelistedDomains, whitelistedDiscordInvites);
    }

    private ChatGuardConfig.SpamConfig parseSpam(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.SpamConfig(true, 3, 30, 1.5, 5, 10, 0.85, 70, 6);
        }
        boolean enabled = section.getBoolean("enabled", true);
        int duplicateThreshold = section.getInt("duplicate-threshold", 3);
        int duplicateWindowSeconds = section.getInt("duplicate-window-seconds", 30);
        double cooldownSeconds = section.getDouble("cooldown-seconds", 1.5);
        int rateLimitCount = section.getInt("rate-limit-count", 5);
        int rateLimitWindowSeconds = section.getInt("rate-limit-window-seconds", 10);
        double similarityThreshold = section.getDouble("similarity-threshold", 0.85);
        int capsPercentage = section.getInt("caps-percentage", 70);
        int capsMinLength = section.getInt("caps-min-length", 6);
        return new ChatGuardConfig.SpamConfig(enabled, duplicateThreshold, duplicateWindowSeconds,
                cooldownSeconds, rateLimitCount, rateLimitWindowSeconds, similarityThreshold,
                capsPercentage, capsMinLength);
    }

    private ChatGuardConfig.ToxicityConfig parseToxicity(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.ToxicityConfig(true, Collections.emptySet(),
                    Collections.emptyList(), 0.6);
        }
        boolean enabled = section.getBoolean("enabled", true);

        Set<String> blockedWords = section.getStringList("blocked-words")
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Pattern> blockedPatterns = section.getStringList("blocked-patterns")
                .stream()
                .map(patternStr -> Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());

        double threshold = section.getDouble("threshold", 0.6);
        return new ChatGuardConfig.ToxicityConfig(enabled, blockedWords, blockedPatterns, threshold);
    }

    private Map<ViolationType, List<PunishmentAction>> parsePunishments(ConfigurationSection section) {
        Map<ViolationType, List<PunishmentAction>> punishments = new EnumMap<>(ViolationType.class);

        if (section == null) {
            // Provide sensible defaults for each violation type
            for (ViolationType type : ViolationType.values()) {
                punishments.put(type, List.of(
                        new PunishmentAction(ActionType.WARN, "<red>Please do not violate chat rules.", 0)
                ));
            }
            return punishments;
        }

        for (ViolationType type : ViolationType.values()) {
            String key = type.name().toLowerCase();
            List<?> entries = section.getList(key);
            if (entries == null || entries.isEmpty()) {
                punishments.put(type, List.of(
                        new PunishmentAction(ActionType.WARN, "<red>Please do not violate chat rules.", 0)
                ));
                continue;
            }

            List<PunishmentAction> actions = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map) {
                    Object actionObj = map.get("action");
                    String actionStr = actionObj != null ? actionObj.toString().toUpperCase() : "WARN";
                    ActionType actionType;
                    try {
                        actionType = ActionType.valueOf(actionStr);
                    } catch (IllegalArgumentException e) {
                        actionType = ActionType.WARN;
                    }
                    Object messageObj = map.get("message");
                    String message = messageObj != null ? messageObj.toString() : "<red>Chat rule violated.";
                    int durationMinutes = 0;
                    Object durationObj = map.get("duration-minutes");
                    if (durationObj instanceof Number num) {
                        durationMinutes = num.intValue();
                    }
                    actions.add(new PunishmentAction(actionType, message, durationMinutes));
                }
            }

            if (actions.isEmpty()) {
                actions.add(new PunishmentAction(ActionType.WARN, "<red>Please do not violate chat rules.", 0));
            }
            punishments.put(type, Collections.unmodifiableList(actions));
        }

        return Collections.unmodifiableMap(punishments);
    }

    private ChatGuardConfig.StaffConfig parseStaff(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.StaffConfig("chatguard.staff.alerts",
                    "<dark_gray>[<red>ChatGuard</red>]</dark_gray> <gray><player></gray> triggered <red><type></red> (<score>)");
        }
        String alertPermission = section.getString("alert-permission", "chatguard.staff.alerts");
        String alertFormat = section.getString("alert-format",
                "<dark_gray>[<red>ChatGuard</red>]</dark_gray> <gray><player></gray> triggered <red><type></red> (<score>)");
        return new ChatGuardConfig.StaffConfig(alertPermission, alertFormat);
    }

    private ChatGuardConfig.DatabaseConfig parseDatabase(ConfigurationSection section) {
        if (section == null) {
            return new ChatGuardConfig.DatabaseConfig("chatguard.db", 1000);
        }
        String file = section.getString("file", "chatguard.db");
        int maxQueueSize = section.getInt("max-queue-size", 1000);
        return new ChatGuardConfig.DatabaseConfig(file, maxQueueSize);
    }
}
