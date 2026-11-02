package dev.chatguard.listener;

import dev.chatguard.ChatGuardPlugin;
import dev.chatguard.config.ChatGuardConfig;
import dev.chatguard.config.ConfigManager;
import dev.chatguard.database.DatabaseManager;
import dev.chatguard.detection.AdvertisingDetector;
import dev.chatguard.detection.DetectionPipeline;
import dev.chatguard.punishment.MuteManager;
import dev.chatguard.punishment.PunishmentManager;
import dev.chatguard.staff.StaffNotifier;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateMessageListenerTest {

    private final Set<String> directCommands = Set.of(
            "msg", "tell", "w", "whisper", "m", "t", "pm",
            "emsg", "etell", "ewhisper", "epm"
    );
    private final Set<String> replyCommands = Set.of(
            "r", "reply", "er", "ereply"
    );

    private ChatGuardPlugin plugin;
    private DetectionPipeline detectionPipeline;
    private MuteManager muteManager;
    private PunishmentManager punishmentManager;
    private StaffNotifier staffNotifier;
    private DatabaseManager databaseManager;
    private PrivateMessageListener listener;

    private Player player;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        plugin = mock(ChatGuardPlugin.class);
        ConfigManager configManager = mock(ConfigManager.class);
        ChatGuardConfig config = mock(ChatGuardConfig.class);
        ChatGuardConfig.GeneralConfig generalConfig = new ChatGuardConfig.GeneralConfig("<prefix>", 50L, false);
        ChatGuardConfig.PrivateMessagingConfig pmConfig = new ChatGuardConfig.PrivateMessagingConfig(
                true, directCommands, replyCommands
        );

        when(config.general()).thenReturn(generalConfig);
        when(config.privateMessaging()).thenReturn(pmConfig);
        when(configManager.getConfig()).thenReturn(config);
        when(plugin.getConfigManager()).thenReturn(configManager);

        // Setup real AdvertisingDetector to test actual detection logic
        AdvertisingDetector advertisingDetector = new AdvertisingDetector(
                0.7, 0.6, 0.8,
                Set.of("minecraft.net", "youtube.com"),
                Collections.emptySet()
        );
        detectionPipeline = new DetectionPipeline(50L, advertisingDetector);
        when(plugin.getDetectionPipeline()).thenReturn(detectionPipeline);

        muteManager = new MuteManager();
        when(plugin.getMuteManager()).thenReturn(muteManager);

        punishmentManager = mock(PunishmentManager.class);
        when(plugin.getPunishmentManager()).thenReturn(punishmentManager);

        staffNotifier = mock(StaffNotifier.class);
        when(plugin.getStaffNotifier()).thenReturn(staffNotifier);

        databaseManager = mock(DatabaseManager.class);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);

        player = mock(Player.class);
        Server server = mock(Server.class);
        when(player.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(Collections.emptyList());
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.hasPermission("chatguard.bypass")).thenReturn(false);

        listener = new PrivateMessageListener(plugin);
    }

    // ── Command Parsing Tests ──────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg Notch hey there",
            "/tell Notch hey there",
            "/w Notch hey there",
            "/whisper Notch hey there",
            "/m Notch hey there",
            "/t Notch hey there",
            "/emsg Notch hey there",
            "/etell Notch hey there",
            "/ewhisper Notch hey there",
            "/minecraft:msg Notch hey there",
            "/minecraft:tell Notch hey there",
            "/minecraft:w Notch hey there",
            "/essentials:msg Notch hey there"
    })
    @DisplayName("Direct messaging commands and namespaced aliases parse target and message correctly")
    void testDirectCommandsParsing(String rawCommand) {
        PrivateMessageListener.ParsedMessageCommand parsed =
                PrivateMessageListener.parseCommand(rawCommand, directCommands, replyCommands);

        assertNotNull(parsed, "Failed to parse: " + rawCommand);
        assertFalse(parsed.isReply());
        assertEquals("Notch", parsed.target());
        assertEquals("hey there", parsed.messageBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/r hey there reply",
            "/reply hey there reply",
            "/er hey there reply",
            "/ereply hey there reply",
            "/essentials:r hey there reply",
            "/essentials:reply hey there reply"
    })
    @DisplayName("Reply commands and namespaced reply aliases parse message body correctly")
    void testReplyCommandsParsing(String rawCommand) {
        PrivateMessageListener.ParsedMessageCommand parsed =
                PrivateMessageListener.parseCommand(rawCommand, directCommands, replyCommands);

        assertNotNull(parsed, "Failed to parse: " + rawCommand);
        assertTrue(parsed.isReply());
        assertEquals("Reply", parsed.target());
        assertEquals("hey there reply", parsed.messageBody());
    }

    @Test
    @DisplayName("Multiple consecutive spaces between arguments are handled properly")
    void testMultipleConsecutiveSpaces() {
        PrivateMessageListener.ParsedMessageCommand parsed =
                PrivateMessageListener.parseCommand("/msg    TargetPlayer     hello   world   ", directCommands, replyCommands);

        assertNotNull(parsed);
        assertEquals("TargetPlayer", parsed.target());
        assertEquals("hello   world   ", parsed.messageBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg",
            "/msg TargetPlayer",
            "/tell",
            "/tell TargetPlayer",
            "/r",
            "/reply",
            "/help",
            "/spawn",
            "/give Notch diamond"
    })
    @DisplayName("Incomplete messaging commands and non-messaging commands return null")
    void testIncompleteOrNonMessagingCommands(String rawCommand) {
        PrivateMessageListener.ParsedMessageCommand parsed =
                PrivateMessageListener.parseCommand(rawCommand, directCommands, replyCommands);

        assertNull(parsed, "Should not parse non-messaging or incomplete command: " + rawCommand);
    }

    // ── Interception & Detection Tests ─────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg Target join my server at 192.168.1.1:25565",
            "/tell Target check out mc.supercraft.net for free rank",
            "/w Target join our community discord.gg/bestserver",
            "/whisper Target play on play.mysmp.gg",
            "/m Target join 10.0.0.1:25565 right now",
            "/t Target new server: mc.epiccraft.org",
            "/minecraft:msg Target join discord.gg/hypersmp",
            "/minecraft:tell Target server IP is 172.16.0.1:25565",
            "/minecraft:w Target join at play.server.com",
            "/r join my server at 192.168.1.50:25565",
            "/reply join discord.gg/superinvite",
            "/emsg Target check out mc.legendcraft.net",
            "/er join at 192.168.0.1:25565"
    })
    @DisplayName("Advertising is intercepted and blocked across all supported messaging channels")
    void testAdvertisingBlockedAcrossChannels(String rawCommand) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, rawCommand);

        listener.onCommandPreprocess(event);

        assertTrue(event.isCancelled(), "Command should have been blocked: " + rawCommand);
        verify(punishmentManager, times(1)).handleViolation(eq(player), any());
        verify(staffNotifier, times(1)).alertStaff(eq(player), any(), anyString());
        verify(databaseManager, times(1)).logViolation(eq(playerId), eq("TestPlayer"), any(), anyDouble(), eq(rawCommand), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg Target p l a y . m c - s e r v e r . n e t",
            "/tell Target d 1 s c 0 r d . g g / i n v 1 t e",
            "/w Target 1 9 2 . 1 6 8 . 1 . 1 0 0 : 2 5 5 6 5",
            "/r p-l-a-y . n-e-t-w-o-r-k . g-g"
    })
    @DisplayName("Obfuscated advertising using spaces and symbols in private messages is caught")
    void testObfuscatedAdvertisingBlocked(String rawCommand) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, rawCommand);

        listener.onCommandPreprocess(event);

        assertTrue(event.isCancelled(), "Obfuscated ad should have been blocked: " + rawCommand);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg Friend hey meet me at coordinates 100 64 200",
            "/tell Friend want to trade 5 diamonds for an elytra?",
            "/w Friend what Minecraft version are you on? I am on 1.21.4",
            "/whisper Friend check out this cool youtube.com tutorial",
            "/r yes that sounds awesome, let's meet at spawn!",
            "/reply okay see you there in 5 minutes",
            "/minecraft:tell Friend I finished building the redstone door",
            "/emsg Friend good game, nicely played"
    })
    @DisplayName("Harmless private messages are never blocked")
    void testHarmlessMessagesNotBlocked(String rawCommand) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, rawCommand);

        listener.onCommandPreprocess(event);

        assertFalse(event.isCancelled(), "Harmless message was unexpectedly blocked: " + rawCommand);
        verify(punishmentManager, never()).handleViolation(any(), any());
        verify(databaseManager, never()).logViolation(any(), any(), any(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("Bypass permission allows advertising in private messages")
    void testBypassPermissionAllowsMessage() {
        when(player.hasPermission("chatguard.bypass")).thenReturn(true);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/msg Target join 192.168.1.1:25565");
        listener.onCommandPreprocess(event);

        assertFalse(event.isCancelled(), "Player with bypass should not be blocked");
        verify(punishmentManager, never()).handleViolation(any(), any());
    }

    @Test
    @DisplayName("Muted players cannot send private messages")
    void testMutedPlayerCannotSendPrivateMessage() {
        muteManager.mute(playerId, 10);

        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, "/msg Target hello how are you");
        listener.onCommandPreprocess(event);

        assertTrue(event.isCancelled(), "Muted player should be prevented from sending private messages");
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }
}
