package dev.chatguard.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.chatguard.ChatGuardPlugin;
import dev.chatguard.database.ViolationRecord;
import dev.chatguard.detection.DetectionResult;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Modern Brigadier command tree for /chatguard and its alias /cg.
 * Provides administration, debugging, inspection, and moderation utilities.
 */
@SuppressWarnings("UnstableApiUsage")
public class ChatGuardCommand {

    private final ChatGuardPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ChatGuardCommand(ChatGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the command tree with Paper's command registrar.
     *
     * @param commands the Paper command registrar
     */
    public void register(Commands commands) {
        commands.register(
                Commands.literal("chatguard")
                        .requires(src -> src.getSender().hasPermission("chatguard.admin")
                                || src.getSender().hasPermission("chatguard.admin.reload")
                                || src.getSender().hasPermission("chatguard.admin.test")
                                || src.getSender().hasPermission("chatguard.admin.logs")
                                || src.getSender().hasPermission("chatguard.admin.stats")
                                || src.getSender().hasPermission("chatguard.admin.mute")
                                || src.getSender().hasPermission("chatguard.admin.unmute")
                                || src.getSender().hasPermission("chatguard.admin.clear")
                                || src.getSender().hasPermission("chatguard.admin.exempt"))
                        // /chatguard reload
                        .then(Commands.literal("reload")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.reload"))
                                .executes(this::executeReload))
                        // /chatguard test <message>
                        .then(Commands.literal("test")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.test"))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(this::executeTest)))
                        // /chatguard logs <player> [count]
                        .then(Commands.literal("logs")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.logs"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> executeLogs(ctx, 10))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                                .executes(ctx -> executeLogs(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                        // /chatguard stats
                        .then(Commands.literal("stats")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.stats"))
                                .executes(this::executeStats))
                        // /chatguard mute <player> <minutes>
                        .then(Commands.literal("mute")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.mute"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                                .executes(this::executeMute))))
                        // /chatguard unmute <player>
                        .then(Commands.literal("unmute")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.unmute"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(this::executeUnmute)))
                        // /chatguard exempt <player>
                        .then(Commands.literal("exempt")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.exempt"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(this::executeExempt)))
                        // /chatguard clear <player>
                        .then(Commands.literal("clear")
                                .requires(src -> src.getSender().hasPermission("chatguard.admin.clear"))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(this::executeClear)))
                        .executes(this::executeHelp)
                        .build(),
                "ChatGuard moderation and configuration commands",
                List.of("cg")
        );
    }

    private String getPrefix() {
        return plugin.getConfigManager().getConfig().general().prefix() + " ";
    }

    private void sendMessage(CommandSender sender, String text) {
        sender.sendMessage(miniMessage.deserialize(getPrefix() + text));
    }

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sendMessage(sender, "<gold><bold>ChatGuard Commands:</bold></gold>");
        sendMessage(sender, "<yellow>/chatguard reload</yellow> <gray>- Reload configuration</gray>");
        sendMessage(sender, "<yellow>/chatguard test <message></yellow> <gray>- Dry-run detection</gray>");
        sendMessage(sender, "<yellow>/chatguard logs <player> [count]</yellow> <gray>- View recent violations</gray>");
        sendMessage(sender, "<yellow>/chatguard stats</yellow> <gray>- View engine and cache statistics</gray>");
        sendMessage(sender, "<yellow>/chatguard mute <player> <mins></yellow> <gray>- Temporarily mute a player</gray>");
        sendMessage(sender, "<yellow>/chatguard unmute <player></yellow> <gray>- Unmute a player</gray>");
        sendMessage(sender, "<yellow>/chatguard clear <player></yellow> <gray>- Reset player violation history</gray>");
        return Command.SINGLE_SUCCESS;
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        try {
            plugin.getConfigManager().reloadConfig();
            plugin.rebuildPipeline();
            sendMessage(sender, "<green>Configuration reloaded and detection pipeline rebuilt successfully!</green>");
        } catch (Exception e) {
            sendMessage(sender, "<red>Failed to reload configuration: " + e.getMessage() + "</red>");
            plugin.getLogger().severe("Error reloading config: " + e.getMessage());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeTest(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String message = StringArgumentType.getString(ctx, "message");
        UUID dummyUuid = UUID.randomUUID();

        long start = System.nanoTime();
        List<DetectionResult> results = plugin.getDetectionPipeline().analyze(message, dummyUuid);
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

        sendMessage(sender, "<gold><bold>Detection Analysis</bold> <gray>(" + String.format("%.2f", elapsedMs) + "ms)</gray>:</gold>");
        sendMessage(sender, "<gray>Input: <italic>\"" + message + "\"</italic></gray>");

        boolean anyViolation = false;
        for (DetectionResult result : results) {
            String color = result.violated() ? "<red>" : "<green>";
            String scorePercent = String.format("%.0f%%", result.score() * 100);
            sendMessage(sender, color + "• [" + result.type().displayName() + "] Score: "
                    + scorePercent + (result.violated() ? " (VIOLATION)" : " (Clean)")
                    + (result.details() != null ? " - " + result.details() : "") + "</color>");
            if (result.violated()) anyViolation = true;
        }

        if (!anyViolation) {
            sendMessage(sender, "<green>✔ Message passed all enabled moderation filters.</green>");
        } else {
            sendMessage(sender, "<red>✖ Message triggered one or more moderation filters.</red>");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeLogs(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = target.getUniqueId();

        sendMessage(sender, "<gray>Querying violations for <white>" + targetName + "</white>...</gray>");

        plugin.getDatabaseManager().getViolations(targetUuid, count).thenAccept(records -> {
            if (records.isEmpty()) {
                sendMessage(sender, "<green>No violations found for " + targetName + ".</green>");
                return;
            }

            sendMessage(sender, "<gold><bold>Violations for " + targetName + " (" + records.size() + "):</bold></gold>");
            for (ViolationRecord record : records) {
                String dateStr = DATE_FORMAT.format(new Date(record.timestamp()));
                String truncatedMsg = record.originalMessage().length() > 40
                        ? record.originalMessage().substring(0, 37) + "..."
                        : record.originalMessage();
                String score = String.format("%.0f%%", record.score() * 100);

                sendMessage(sender, "<dark_gray>[" + dateStr + "]</dark_gray> <red>" + record.type().displayName()
                        + "</red> <gray>(" + score + ")</gray>: <italic>\"" + truncatedMsg + "\"</italic>");
            }
        }).exceptionally(ex -> {
            sendMessage(sender, "<red>Database error querying logs: " + ex.getMessage() + "</red>");
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }

    private int executeStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        Duration uptime = plugin.getUptime();
        long hours = uptime.toHours();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();

        int trackedPlayers = plugin.getViolationTracker().getTrackedPlayerCount();
        int mutedPlayers = plugin.getMuteManager().getMutedCount();
        int pendingDb = plugin.getDatabaseManager().getPendingCount();

        plugin.getDatabaseManager().getViolationCount().thenAccept(totalViolations -> {
            sendMessage(sender, "<gold><bold>ChatGuard Engine Stats:</bold></gold>");
            sendMessage(sender, "<gray>Uptime: <white>" + hours + "h " + minutes + "m " + seconds + "s</white></gray>");
            sendMessage(sender, "<gray>Actively Tracked Players: <white>" + trackedPlayers + "</white></gray>");
            sendMessage(sender, "<gray>Currently Muted Players: <white>" + mutedPlayers + "</white></gray>");
            sendMessage(sender, "<gray>Pending DB Operations: <white>" + pendingDb + "</white></gray>");
            sendMessage(sender, "<gray>Total Logged Violations: <white>" + totalViolations + "</white></gray>");
        }).exceptionally(ex -> {
            sendMessage(sender, "<gold><bold>ChatGuard Engine Stats:</bold></gold>");
            sendMessage(sender, "<gray>Uptime: <white>" + hours + "h " + minutes + "m " + seconds + "s</white></gray>");
            sendMessage(sender, "<gray>Actively Tracked Players: <white>" + trackedPlayers + "</white></gray>");
            sendMessage(sender, "<gray>Currently Muted Players: <white>" + mutedPlayers + "</white></gray>");
            sendMessage(sender, "<gray>Pending DB Operations: <white>" + pendingDb + "</white></gray>");
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }

    private int executeMute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");

        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetUuid;
        if (target != null) {
            targetUuid = target.getUniqueId();
            target.sendMessage(miniMessage.deserialize("<red>You have been muted by staff for " + minutes + " minute(s).</red>"));
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            targetUuid = offline.getUniqueId();
        }

        plugin.getMuteManager().mute(targetUuid, minutes);
        sendMessage(sender, "<green>Muted <white>" + targetName + "</white> for " + minutes + " minute(s).</green>");
        return Command.SINGLE_SUCCESS;
    }

    private int executeUnmute(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetUuid;
        if (target != null) {
            targetUuid = target.getUniqueId();
            target.sendMessage(miniMessage.deserialize("<green>You have been unmuted.</green>"));
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            targetUuid = offline.getUniqueId();
        }

        if (!plugin.getMuteManager().isMuted(targetUuid)) {
            sendMessage(sender, "<yellow>Player <white>" + targetName + "</white> is not currently muted.</yellow>");
            return Command.SINGLE_SUCCESS;
        }

        plugin.getMuteManager().unmute(targetUuid);
        sendMessage(sender, "<green>Unmuted <white>" + targetName + "</white>.</green>");
        return Command.SINGLE_SUCCESS;
    }

    private int executeExempt(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        sendMessage(sender, "<yellow>To exempt <white>" + targetName + "</white> from ChatGuard detection, grant them the permission <white><underlined>chatguard.bypass</underlined></white> using your server permissions plugin (e.g. LuckPerms: /lp user " + targetName + " permission set chatguard.bypass true).</yellow>");
        return Command.SINGLE_SUCCESS;
    }

    private int executeClear(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = offline.getUniqueId();

        plugin.getViolationTracker().clearPlayer(targetUuid);
        plugin.getDatabaseManager().clearViolations(targetUuid).thenRun(() -> {
            sendMessage(sender, "<green>Cleared all violation history and escalation level for <white>" + targetName + "</white>.</green>");
        }).exceptionally(ex -> {
            sendMessage(sender, "<red>Failed to clear database logs: " + ex.getMessage() + "</red>");
            return null;
        });

        return Command.SINGLE_SUCCESS;
    }
}

