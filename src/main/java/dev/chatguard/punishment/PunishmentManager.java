package dev.chatguard.punishment;

import dev.chatguard.config.ChatGuardConfig;
import dev.chatguard.detection.DetectionResult;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

/**
 * Executes punishments based on a player's escalation level for a given violation type.
 * <p>
 * Uses {@link ViolationTracker} to determine the current offense count, looks up the
 * corresponding {@link PunishmentAction} from the config's escalation chain, and dispatches
 * the appropriate action (warn, mute, kick, or ban).
 * <p>
 * KICK and BAN actions are always dispatched to the main server thread since chat event
 * handlers may fire off the main thread.
 */
public class PunishmentManager {

    private final JavaPlugin plugin;
    private final ViolationTracker violationTracker;
    private final MuteManager muteManager;
    private final Supplier<ChatGuardConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Constructs a new PunishmentManager.
     *
     * @param plugin           the owning JavaPlugin instance
     * @param violationTracker the tracker for per-player violation counts
     * @param muteManager      the mute manager for applying temporary mutes
     * @param configSupplier   supplier for the current config (supports hot-reload)
     */
    public PunishmentManager(JavaPlugin plugin, ViolationTracker violationTracker,
                             MuteManager muteManager, Supplier<ChatGuardConfig> configSupplier) {
        this.plugin = plugin;
        this.violationTracker = violationTracker;
        this.muteManager = muteManager;
        this.configSupplier = configSupplier;
    }

    /**
     * Handles a detected violation for a player by escalating through the punishment chain.
     * <p>
     * The escalation level is determined by the player's cumulative offense count for the
     * given violation type. The config's {@code getPunishment(type, level)} method clamps
     * to the last action in the chain if the level exceeds the configured list size.
     *
     * @param player the player who committed the violation
     * @param result the detection result containing the violation type and details
     */
    public void handleViolation(Player player, DetectionResult result) {
        // Increment and convert to 0-indexed escalation level
        int level = violationTracker.incrementAndGet(player.getUniqueId(), result.type()) - 1;

        ChatGuardConfig config = configSupplier.get();
        PunishmentAction action = config.getPunishment(result.type(), level);

        if (action == null) {
            return;
        }

        switch (action.type()) {
            case WARN -> player.sendMessage(miniMessage.deserialize(action.message()));

            case MUTE -> {
                muteManager.mute(player.getUniqueId(), action.durationMinutes());
                player.sendMessage(miniMessage.deserialize(action.message()));
            }

            case KICK -> {
                // Must dispatch kick to the main thread
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.kick(miniMessage.deserialize(action.message()))
                );
            }

            case BAN -> {
                // Must dispatch ban to the main thread
                String banMessage = action.message();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.banPlayer(banMessage)
                );
            }
        }
    }

    /**
     * Returns the underlying violation tracker.
     *
     * @return the {@link ViolationTracker} instance
     */
    public ViolationTracker getViolationTracker() {
        return violationTracker;
    }

    /**
     * Returns the underlying mute manager.
     *
     * @return the {@link MuteManager} instance
     */
    public MuteManager getMuteManager() {
        return muteManager;
    }
}
