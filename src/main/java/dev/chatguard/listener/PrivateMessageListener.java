package dev.chatguard.listener;

import dev.chatguard.ChatGuardPlugin;
import dev.chatguard.config.ChatGuardConfig;
import dev.chatguard.detection.DetectionResult;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Moderates direct player-to-player private messaging channels (e.g. /msg, /tell, /w, /r)
 * to prevent advertising, disguised domains, Discord invites, and prohibited content from
 * reaching recipients via whispers.
 * <p>
 * Intercepts commands on {@link PlayerCommandPreprocessEvent} at {@link EventPriority#LOW}
 * before command dispatch, preventing downstream messaging plugins (like EssentialsX or
 * vanilla commands) from delivering the message if a violation is detected.
 */
public class PrivateMessageListener implements Listener {

    private final java.util.function.Supplier<ChatGuardConfig> configSupplier;
    private final dev.chatguard.detection.DetectionPipeline detectionPipeline;
    private final dev.chatguard.punishment.MuteManager muteManager;
    private final dev.chatguard.punishment.PunishmentManager punishmentManager;
    private final dev.chatguard.staff.StaffNotifier staffNotifier;
    private final dev.chatguard.database.DatabaseManager databaseManager;
    private final java.util.logging.Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /**
     * Production constructor using ChatGuardPlugin.
     */
    public PrivateMessageListener(ChatGuardPlugin plugin) {
        this(
                () -> plugin.getConfigManager().getConfig(),
                plugin.getDetectionPipeline(),
                plugin.getMuteManager(),
                plugin.getPunishmentManager(),
                plugin.getStaffNotifier(),
                plugin.getDatabaseManager(),
                plugin.getLogger()
        );
    }

    /**
     * Dependency-injected constructor for testing and modularity.
     */
    public PrivateMessageListener(
            java.util.function.Supplier<ChatGuardConfig> configSupplier,
            dev.chatguard.detection.DetectionPipeline detectionPipeline,
            dev.chatguard.punishment.MuteManager muteManager,
            dev.chatguard.punishment.PunishmentManager punishmentManager,
            dev.chatguard.staff.StaffNotifier staffNotifier,
            dev.chatguard.database.DatabaseManager databaseManager,
            java.util.logging.Logger logger
    ) {
        this.configSupplier = configSupplier;
        this.detectionPipeline = detectionPipeline;
        this.muteManager = muteManager;
        this.punishmentManager = punishmentManager;
        this.staffNotifier = staffNotifier;
        this.databaseManager = databaseManager;
        this.logger = logger;
    }

    /**
     * Represents the parsed direct communication context.
     *
     * @param commandToken the base command name (lowercased, namespace stripped)
     * @param target       the target recipient, or "Reply" if a reply command
     * @param messageBody  the extracted message text
     * @param isReply      whether this is a reply command (e.g. /r) without an explicit target arg
     */
    public record ParsedMessageCommand(
            String commandToken,
            String target,
            String messageBody,
            boolean isReply
    ) {
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        ChatGuardConfig config = configSupplier.get();
        ChatGuardConfig.PrivateMessagingConfig pmConfig = config.privateMessaging();

        if (!pmConfig.enabled()) {
            return;
        }

        Player player = event.getPlayer();

        // 1. Check bypass permission
        if (player.hasPermission("chatguard.bypass")) {
            return;
        }

        String rawCommand = event.getMessage();
        if (rawCommand == null || rawCommand.isBlank() || !rawCommand.startsWith("/")) {
            return;
        }

        // 2. Parse command and extract message payload
        ParsedMessageCommand parsed = parseCommand(rawCommand, pmConfig.directCommands(), pmConfig.replyCommands());
        if (parsed == null || parsed.messageBody().isBlank()) {
            return; // Not a recognized PM command or no message body provided
        }

        // 3. Mute check: muted players cannot bypass mutes via direct messaging
        if (muteManager.isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>You are muted and cannot send private messages.</red>"));
            return;
        }

        // 4. Run detection pipeline on the extracted private message body
        List<DetectionResult> results = detectionPipeline.analyze(parsed.messageBody(), player.getUniqueId());

        // 5. Check for any threshold-crossing violations
        Optional<DetectionResult> worstViolation = results.stream()
                .filter(DetectionResult::violated)
                .max(Comparator.comparingDouble(DetectionResult::score));

        if (worstViolation.isPresent()) {
            DetectionResult violation = worstViolation.get();

            // Cancel the command — prevents downstream dispatch to EssentialsX / Vanilla
            event.setCancelled(true);

            // Execute punishment escalation
            punishmentManager.handleViolation(player, violation);

            // Alert online staff with Private Message channel indicator
            String channelTag = "Private Message (/" + parsed.commandToken() + " -> " + parsed.target() + ")";
            staffNotifier.alertStaff(player, violation, channelTag);

            // Asynchronously log to SQLite database
            String logDetails = "[PM -> " + parsed.target() + "] " + violation.details();
            databaseManager.logViolation(
                    player.getUniqueId(),
                    player.getName(),
                    violation.type(),
                    violation.score(),
                    rawCommand,
                    logDetails
            );

            if (config.general().debug()) {
                logger.info("[DEBUG] Blocked private message from " + player.getName()
                        + " to " + parsed.target() + " (" + violation.type() + ", score="
                        + violation.score() + "): " + parsed.messageBody());
            }
        }
    }

    /**
     * Parses a raw slash command into a {@link ParsedMessageCommand} if it matches
     * a configured direct messaging or reply command alias.
     *
     * @param rawCommand     the full command line entered by the player (e.g. "/msg Notch hello")
     * @param directCommands set of configured direct message commands (e.g. "msg", "tell", "w")
     * @param replyCommands  set of configured reply commands (e.g. "r", "reply")
     * @return the parsed command info, or {@code null} if not a recognized messaging command
     */
    public static ParsedMessageCommand parseCommand(
            String rawCommand,
            Set<String> directCommands,
            Set<String> replyCommands
    ) {
        if (rawCommand == null || rawCommand.length() <= 1 || rawCommand.charAt(0) != '/') {
            return null;
        }

        // 1. Locate end of command token
        int len = rawCommand.length();
        int cmdStart = 1; // skip leading '/'
        while (cmdStart < len && rawCommand.charAt(cmdStart) == ' ') {
            cmdStart++;
        }
        if (cmdStart >= len) {
            return null;
        }

        int cmdEnd = cmdStart;
        while (cmdEnd < len && rawCommand.charAt(cmdEnd) != ' ') {
            cmdEnd++;
        }

        String fullCmd = rawCommand.substring(cmdStart, cmdEnd).toLowerCase(Locale.ROOT);

        // Strip namespace if present (e.g. "minecraft:tell" -> "tell", "essentials:msg" -> "msg")
        String bareCmd = fullCmd;
        int colonIdx = fullCmd.indexOf(':');
        if (colonIdx != -1 && colonIdx < fullCmd.length() - 1) {
            bareCmd = fullCmd.substring(colonIdx + 1);
        }

        boolean isReply = matchesCommand(bareCmd, fullCmd, replyCommands, true);
        boolean isDirect = !isReply && matchesCommand(bareCmd, fullCmd, directCommands, false);

        if (!isReply && !isDirect) {
            return null;
        }

        // 2. Parse arguments
        if (isReply) {
            // Format: /r <message...>
            int msgStart = cmdEnd;
            while (msgStart < len && rawCommand.charAt(msgStart) == ' ') {
                msgStart++;
            }
            if (msgStart >= len) {
                return null; // Empty reply
            }
            String message = rawCommand.substring(msgStart);
            return new ParsedMessageCommand(bareCmd, "Reply", message, true);
        } else {
            // Format: /msg <target> <message...>
            int targetStart = cmdEnd;
            while (targetStart < len && rawCommand.charAt(targetStart) == ' ') {
                targetStart++;
            }
            if (targetStart >= len) {
                return null; // No target provided
            }

            int targetEnd = targetStart;
            while (targetEnd < len && rawCommand.charAt(targetEnd) != ' ') {
                targetEnd++;
            }

            int msgStart = targetEnd;
            while (msgStart < len && rawCommand.charAt(msgStart) == ' ') {
                msgStart++;
            }
            if (msgStart >= len) {
                return null; // Only target provided, no message content
            }

            String target = rawCommand.substring(targetStart, targetEnd);
            String message = rawCommand.substring(msgStart);
            return new ParsedMessageCommand(bareCmd, target, message, false);
        }
    }

    /**
     * Checks if a command matches configured command sets directly or via Bukkit's command map.
     */
    private static boolean matchesCommand(String bareCmd, String fullCmd, Set<String> commandSet, boolean isReply) {
        if (commandSet == null || commandSet.isEmpty()) {
            return false;
        }
        if (commandSet.contains(bareCmd) || commandSet.contains(fullCmd)) {
            return true;
        }

        // Dynamic lookup in server command map for aliases registered by other plugins
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map != null) {
                Command registered = map.getCommand(bareCmd);
                if (registered != null) {
                    String primary = registered.getName().toLowerCase(Locale.ROOT);
                    if (commandSet.contains(primary)) {
                        return true;
                    }
                    for (String alias : registered.getAliases()) {
                        if (commandSet.contains(alias.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fallback for mocked unit-test environments without active Bukkit runtime
        }

        return false;
    }
}
