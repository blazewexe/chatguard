package dev.chatguard.listener;

import dev.chatguard.ChatGuardPlugin;
import dev.chatguard.detection.DetectionResult;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Listener for Paper's {@link AsyncChatEvent}.
 * <p>
 * Handles chat moderation asynchronously off the main thread:
 * checks mutes, runs detection analysis against the message, applies punishments,
 * alerts staff, and logs violations to SQLite.
 */
public class ChatListener implements Listener {

    private final ChatGuardPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChatListener(ChatGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // 1. Check bypass permission
        if (player.hasPermission("chatguard.bypass")) {
            return;
        }

        // 2. Check if player is actively muted
        if (plugin.getMuteManager().isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            Instant expiry = plugin.getMuteManager().getMuteExpiry(player.getUniqueId());
            if (expiry != null) {
                long minutesLeft = Math.max(1, Duration.between(Instant.now(), expiry).toMinutes());
                player.sendMessage(miniMessage.deserialize("<red>You are muted. Expires in " + minutesLeft + " minute(s)."));
            } else {
                player.sendMessage(miniMessage.deserialize("<red>You are muted and cannot send messages."));
            }
            return;
        }

        // 3. Serialize message to plain text
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (rawMessage.isBlank()) {
            return;
        }

        // 4. Run detection pipeline (budget-bounded)
        List<DetectionResult> results = plugin.getDetectionPipeline().analyze(rawMessage, player.getUniqueId());

        // 5. Check for any violations
        Optional<DetectionResult> worstViolation = results.stream()
                .filter(DetectionResult::violated)
                .max(Comparator.comparingDouble(DetectionResult::score));

        if (worstViolation.isPresent()) {
            DetectionResult violation = worstViolation.get();
            event.setCancelled(true);

            // Execute punishment escalation
            plugin.getPunishmentManager().handleViolation(player, violation);

            // Send in-game alert to online staff members
            plugin.getStaffNotifier().alertStaff(player, violation);

            // Asynchronously log to SQLite database
            plugin.getDatabaseManager().logViolation(
                    player.getUniqueId(),
                    player.getName(),
                    violation.type(),
                    violation.score(),
                    rawMessage,
                    violation.details()
            );

            if (plugin.getConfigManager().getConfig().general().debug()) {
                plugin.getLogger().info("[DEBUG] " + player.getName() + " triggered "
                        + violation.type() + " (score=" + violation.score() + ", details=" + violation.details() + ")");
            }
        }
    }
}
