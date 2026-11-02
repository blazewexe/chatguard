package dev.chatguard;

import dev.chatguard.command.ChatGuardCommand;
import dev.chatguard.config.ChatGuardConfig;
import dev.chatguard.config.ConfigManager;
import dev.chatguard.database.DatabaseManager;
import dev.chatguard.detection.AdvertisingDetector;
import dev.chatguard.detection.ChatDetector;
import dev.chatguard.detection.DetectionPipeline;
import dev.chatguard.detection.SpamDetector;
import dev.chatguard.detection.ToxicityDetector;
import dev.chatguard.listener.ChatListener;
import dev.chatguard.listener.PrivateMessageListener;
import dev.chatguard.punishment.MuteManager;
import dev.chatguard.punishment.PunishmentManager;
import dev.chatguard.punishment.ViolationTracker;
import dev.chatguard.staff.StaffNotifier;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Main plugin class for ChatGuard — a comprehensive chat moderation plugin
 * for Paper-based Minecraft servers.
 *
 * <p>Manages the full lifecycle of chat detection, punishment, staff alerts,
 * and violation logging subsystems.</p>
 */
public final class ChatGuardPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private DetectionPipeline detectionPipeline;
    private PunishmentManager punishmentManager;
    private StaffNotifier staffNotifier;
    private ViolationTracker violationTracker;
    private MuteManager muteManager;
    private long startTime;

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public void onEnable() {
        startTime = System.currentTimeMillis();

        // 1. Configuration
        configManager = new ConfigManager(this);
        configManager.loadConfig();
        ChatGuardConfig config = configManager.getConfig();

        // 2. Database
        databaseManager = new DatabaseManager(
                new File(getDataFolder(), config.database().file()),
                config.database().maxQueueSize(),
                getLogger()
        );
        databaseManager.initialize();

        // 3. Punishment subsystem
        violationTracker = new ViolationTracker();
        muteManager = new MuteManager();
        staffNotifier = new StaffNotifier(this, () -> configManager.getConfig());
        punishmentManager = new PunishmentManager(this, violationTracker, muteManager, () -> configManager.getConfig());

        // 4. Detection pipeline
        detectionPipeline = buildPipeline(config);

        // 5. Listeners
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PrivateMessageListener(this), this);

        // 6. Commands (Paper lifecycle API)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            new ChatGuardCommand(this).register(event.registrar());
        });

        getLogger().info("ChatGuard v" + getPluginMeta().getVersion() + " enabled — protecting your chat.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        if (detectionPipeline != null) {
            detectionPipeline.shutdown();
        }
        if (violationTracker != null) {
            violationTracker.clear();
        }
        getLogger().info("ChatGuard disabled.");
    }

    /**
     * Rebuilds the detection pipeline from the current configuration.
     * Should be called after {@link ConfigManager#reloadConfig()}.
     */
    public void rebuildPipeline() {
        if (detectionPipeline != null) {
            detectionPipeline.shutdown();
        }
        detectionPipeline = buildPipeline(configManager.getConfig());
    }

    /**
     * Constructs a new {@link DetectionPipeline} based on the given configuration,
     * adding only the detectors whose corresponding modules are enabled.
     *
     * @param config the current plugin configuration
     * @return a fully wired detection pipeline
     */
    private DetectionPipeline buildPipeline(ChatGuardConfig config) {
        List<ChatDetector> detectors = new ArrayList<>();

        if (config.advertising().enabled()) {
            detectors.add(new AdvertisingDetector(
                    config.advertising().ipThreshold(),
                    config.advertising().domainThreshold(),
                    config.advertising().discordThreshold(),
                    config.advertising().whitelistedDomains(),
                    config.advertising().whitelistedDiscordInvites()
            ));
        }

        if (config.spam().enabled()) {
            detectors.add(new SpamDetector(
                    config.spam().duplicateThreshold(),
                    config.spam().duplicateWindowSeconds(),
                    config.spam().cooldownSeconds(),
                    config.spam().rateLimitCount(),
                    config.spam().rateLimitWindowSeconds(),
                    config.spam().similarityThreshold(),
                    config.spam().capsPercentage(),
                    config.spam().capsMinLength()
            ));
        }

        if (config.toxicity().enabled()) {
            detectors.add(new ToxicityDetector(
                    config.toxicity().blockedWords(),
                    config.toxicity().blockedPatterns(),
                    config.toxicity().threshold()
            ));
        }

        return new DetectionPipeline(
                config.general().processingBudgetMs(),
                detectors.toArray(new ChatDetector[0])
        );
    }

    // ── Getters ────────────────────────────────────────────────────────────

    /** Returns the configuration manager. */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** Returns the database manager. */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /** Returns the detection pipeline. */
    public DetectionPipeline getDetectionPipeline() {
        return detectionPipeline;
    }

    /** Returns the punishment manager. */
    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    /** Returns the staff notifier. */
    public StaffNotifier getStaffNotifier() {
        return staffNotifier;
    }

    /** Returns the violation tracker. */
    public ViolationTracker getViolationTracker() {
        return violationTracker;
    }

    /** Returns the mute manager. */
    public MuteManager getMuteManager() {
        return muteManager;
    }

    /**
     * Returns the duration since the plugin was enabled.
     *
     * @return uptime as a {@link Duration}
     */
    public Duration getUptime() {
        return Duration.ofMillis(System.currentTimeMillis() - startTime);
    }
}
