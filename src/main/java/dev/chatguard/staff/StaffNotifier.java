package dev.chatguard.staff;

import dev.chatguard.config.ChatGuardConfig;
import dev.chatguard.detection.DetectionResult;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

/**
 * Sends real-time violation alert messages to online staff members.
 * <p>
 * Staff members are identified by the permission node configured in the staff section
 * of the plugin config. Alerts are formatted using MiniMessage with placeholder resolution
 * for player name, violation type, and confidence score.
 */
public class StaffNotifier {

    private final JavaPlugin plugin;
    private final Supplier<ChatGuardConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Constructs a new StaffNotifier.
     *
     * @param plugin         the owning JavaPlugin instance
     * @param configSupplier supplier for the current config (supports hot-reload)
     */
    public StaffNotifier(JavaPlugin plugin, Supplier<ChatGuardConfig> configSupplier) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
    }

    /**
     * Sends a violation alert to all online staff members who have the configured
     * alert permission.
     * <p>
     * The alert message is built from the configured {@code alert-format} string using
     * MiniMessage deserialization with the following placeholders:
     * <ul>
     *   <li>{@code <player>} — the violator's name</li>
     *   <li>{@code <type>} — the violation type's display name</li>
     *   <li>{@code <score>} — the detection confidence formatted as a percentage (e.g. "85%")</li>
     * </ul>
     *
     * @param violator the player who triggered the violation
     * @param result   the detection result containing type, score, and details
     */
    public void alertStaff(Player violator, DetectionResult result) {
        ChatGuardConfig config = configSupplier.get();
        ChatGuardConfig.StaffConfig staffConfig = config.staff();

        String alertPermission = staffConfig.alertPermission();
        String alertFormat = staffConfig.alertFormat();

        // Format score as percentage (e.g. 0.85 → "85%")
        String scoreFormatted = Math.round(result.score() * 100) + "%";

        TagResolver resolvers = TagResolver.resolver(
                Placeholder.unparsed("player", violator.getName()),
                Placeholder.unparsed("type", result.type().displayName()),
                Placeholder.unparsed("score", scoreFormatted)
        );

        Component alertComponent = miniMessage.deserialize(alertFormat, resolvers);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission(alertPermission)) {
                onlinePlayer.sendMessage(alertComponent);
            }
        }
    }
}
