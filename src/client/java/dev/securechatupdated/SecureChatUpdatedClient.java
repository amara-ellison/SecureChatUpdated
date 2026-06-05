package dev.securechatupdated;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class SecureChatUpdatedClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SecureChat");
    private static final String PREFIX = "[Secure Chat] ";
    private static final int PREFIX_COLOR = 0x55FF55;
    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFF;
    private static final int TRUE_COLOR = 0x55FF55;
    private static final int FALSE_COLOR = 0xFF5555;

    private static final SecureChatUpdatedProtocol PROTOCOL = new SecureChatUpdatedProtocol();
    private static boolean protocolStartedForWorld;

    private static final SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> RECIPIENT_SUGGESTIONS =
            SecureChatUpdatedClient::suggestRecipients;
    private static final SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> FRIEND_SUGGESTIONS =
            SecureChatUpdatedClient::suggestFriends;

    @Override
    public void onInitializeClient() {
        String username = Minecraft.getInstance().getUser().getName();
        SecureChatUpdatedConfig.setUsername(username);
        SecureChatUpdatedConfig.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerCommands(dispatcher, "SecureChatUpdated");
            registerCommands(dispatcher, "scu");
            registerGlobalCommands(dispatcher);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (protocolStartedForWorld) {
                return;
            }
            SecureChatUpdatedConfig.setUsername(client.getUser().getName());
            PROTOCOL.start();
            protocolStartedForWorld = true;
            if (!PROTOCOL.startupProblem().isBlank()) {
                notice(PROTOCOL.startupProblem());
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (protocolStartedForWorld) {
                PROTOCOL.disconnect();
                protocolStartedForWorld = false;
                logLine("MQTT disconnected because no world is loaded.");
            }
        });
    }

    public static boolean shouldSendSecureMessage(String message) {
        return SecureChatUpdatedConfig.isEnabled() && !message.startsWith("/");
    }

    public static void sendSecureChatMessage(String message) {
        PROTOCOL.sendSecureMessage(message);
    }

    public static void toggleEncryption() {
        PROTOCOL.toggle();
        notice("Encryption enabled: " + SecureChatUpdatedConfig.isEnabled());
    }

    public static void reloadProtocol() {
        PROTOCOL.reload();
        if (PROTOCOL.startupProblem().isBlank()) {
            notice("SecureChat identity loaded.");
        } else {
            notice(PROTOCOL.startupProblem());
        }
    }

    public static void connectProtocol() {
        PROTOCOL.reload();
        if (PROTOCOL.startupProblem().isBlank()) {
            notice("MQTT connected: " + PROTOCOL.isReady());
        } else {
            notice(PROTOCOL.startupProblem());
        }
    }

    public static void notice(String msg) {
        logLine(msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.execute(() -> mc.player.sendSystemMessage(prefixedLine(msg)));
        }
    }

    public static void showLines(List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        mc.execute(() -> {
            for (String line : lines) {
                mc.player.sendSystemMessage(prefixedLine(line));
            }
        });
    }

    public static void logLines(List<String> lines) {
        for (String line : lines) {
            logLine(line);
        }
    }

    public static void logLine(String line) {
        LOGGER.info("{}{}", PREFIX, line);
    }

    public static void showSecureMessage(String sender, String recipients, String text, boolean trusted, String hover) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        mc.execute(() -> {
            int prefixColor = trusted ? 0x55FF55 : 0xFFAA00;
            MutableComponent message = Component.literal(PREFIX).withStyle(s -> s.withColor(prefixColor))
                    .append(Component.literal(sender + " -> " + recipients + ": ").withStyle(s -> s.withColor(0xAAAAAA)))
                    .append(Component.literal(text).withStyle(s -> s
                            .withColor(0x55FF55)
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))));
            mc.player.sendSystemMessage(message);
        });
    }

    public static Component decorateTabPlayerName(String playerName, Component original) {
        String trustLabel = PROTOCOL.activeTrustLabelForPlayer(playerName);
        if (trustLabel == null) {
            return original;
        }

        int trustColor = "Trusted".equals(trustLabel) ? 0x55FF55 : 0xFFAA00;
        return Component.empty()
                .append(Component.literal("[Secure Chat] ").withStyle(s -> s.withColor(PREFIX_COLOR)))
                .append(Component.literal("[" + trustLabel + "] ").withStyle(s -> s.withColor(trustColor)))
                .append(original);
    }

    public static String recipientIndicatorText() {
        if (!SecureChatUpdatedConfig.isEnabled()) {
            return "";
        }
        return "Send to: " + PROTOCOL.recipientDisplayLabel();
    }

    private static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher,
            String root
    ) {
        dispatcher.register(ClientCommands.literal(root)
                .executes(ctx -> {
                    openHelpScreen();
                    return 1;
                })
                .then(ClientCommands.literal("help").executes(ctx -> {
                    openHelpScreen();
                    return 1;
                }))
                .then(ClientCommands.literal("list_online_players").executes(ctx -> {
                    openListOnlinePlayersScreen();
                    return 1;
                }))
                .then(ClientCommands.literal("fingerprints").executes(ctx -> {
                    openFingerprintsScreen();
                    return 1;
                }))
                .then(ClientCommands.literal("trust")
                        .then(ClientCommands.argument("friend", StringArgumentType.word())
                                .suggests(FRIEND_SUGGESTIONS)
                                .executes(ctx -> {
                                    notice(PROTOCOL.trustFriend(StringArgumentType.getString(ctx, "friend")));
                                    return 1;
                                })))
                .then(ClientCommands.literal("untrust")
                        .then(ClientCommands.argument("friend", StringArgumentType.word())
                                .suggests(FRIEND_SUGGESTIONS)
                                .executes(ctx -> {
                                    notice(PROTOCOL.untrustFriend(StringArgumentType.getString(ctx, "friend")));
                                    return 1;
                                })))
                .then(ClientCommands.literal("to")
                        .executes(ctx -> {
                            notice(PROTOCOL.setRecipients(""));
                            return 1;
                        })
                        .then(ClientCommands.argument("recipients", StringArgumentType.greedyString())
                                .suggests(RECIPIENT_SUGGESTIONS)
                                .executes(ctx -> {
                                    notice(PROTOCOL.setRecipients(StringArgumentType.getString(ctx, "recipients")));
                                    return 1;
                                })))
                .then(ClientCommands.literal("send")
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    PROTOCOL.sendSecureMessage(StringArgumentType.getString(ctx, "message"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("on").executes(ctx -> {
                    SecureChatUpdatedConfig.setEnabled(true);
                    notice("Encryption enabled: true");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    SecureChatUpdatedConfig.setEnabled(false);
                    notice("Encryption enabled: false");
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    openStatusScreen();
                    logLines(PROTOCOL.statusLines());
                    return 1;
                }))
                .then(ClientCommands.literal("reload_config").executes(ctx -> {
                    reloadProtocol();
                    return 1;
                }))
                .then(ClientCommands.literal("connect").executes(ctx -> {
                    connectProtocol();
                    return 1;
                }))
                .then(ClientCommands.literal("export_public_identity").executes(ctx -> {
                    exportPublicIdentity();
                    return 1;
                }))
                .then(ClientCommands.literal("disconnect").executes(ctx -> {
                    PROTOCOL.disconnect();
                    notice("MQTT disconnected.");
                    return 1;
                })));
    }

    private static void registerGlobalCommands(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher
    ) {
        dispatcher.register(ClientCommands.literal("connect").executes(ctx -> {
            connectProtocol();
            return 1;
        }));
        dispatcher.register(ClientCommands.literal("reload_config").executes(ctx -> {
            reloadProtocol();
            return 1;
        }));
        dispatcher.register(ClientCommands.literal("disconnect").executes(ctx -> {
            PROTOCOL.disconnect();
            notice("MQTT disconnected.");
            return 1;
        }));
    }

    private static void openHelpScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new SecureChatHelpScreen(mc.screen, PROTOCOL.helpLines())));
        }
    }

    private static void openListOnlinePlayersScreen() {
        List<String> lines = PROTOCOL.listFriendsLines();
        List<List<SecureChatTableScreen.Cell>> rows = new ArrayList<>();
        List<String> headers = List.of("Player", "Fingerprint", "Trust", "Last Seen");

        for (String line : lines) {
            if (!line.startsWith("- ")) {
                openMessageTable("Online SecureChat Players", line);
                return;
            }
            String[] parts = line.substring(2).split("\\|");
            if (parts.length >= 4) {
                String trust = parts[2].trim();
                rows.add(SecureChatTableScreen.row(
                        new SecureChatTableScreen.Cell(parts[0].trim(), SecureChatTableScreen.WHITE),
                        new SecureChatTableScreen.Cell(parts[1].trim(), SecureChatTableScreen.MUTED),
                        new SecureChatTableScreen.Cell(trust, colorForValue(trust)),
                        new SecureChatTableScreen.Cell(parts[3].trim(), SecureChatTableScreen.GREEN)
                ));
            }
        }

        openTable("Online SecureChat Players", headers, rows);
    }

    private static void openFingerprintsScreen() {
        List<String> lines = PROTOCOL.fingerprintLines();
        List<List<SecureChatTableScreen.Cell>> rows = new ArrayList<>();
        List<String> headers = List.of("Name", "Fingerprint", "Trust");

        for (String line : lines) {
            if (line.startsWith("You: ")) {
                String[] parts = line.substring(5).split("\\|");
                if (parts.length >= 2) {
                    rows.add(SecureChatTableScreen.row(
                            new SecureChatTableScreen.Cell(parts[0].trim() + " (you)", SecureChatTableScreen.GREEN),
                            new SecureChatTableScreen.Cell(parts[1].trim(), SecureChatTableScreen.MUTED),
                            new SecureChatTableScreen.Cell("local", SecureChatTableScreen.GREEN)
                    ));
                }
            } else if (line.startsWith("- ")) {
                String[] parts = line.substring(2).split("\\|");
                if (parts.length >= 3) {
                    String trust = parts[2].trim();
                    rows.add(SecureChatTableScreen.row(
                            new SecureChatTableScreen.Cell(parts[0].trim(), SecureChatTableScreen.WHITE),
                            new SecureChatTableScreen.Cell(parts[1].trim(), SecureChatTableScreen.MUTED),
                            new SecureChatTableScreen.Cell(trust, colorForValue(trust))
                    ));
                }
            } else if (!line.isBlank()) {
                openMessageTable("Known Fingerprints", line);
                return;
            }
        }

        openTable("Known Fingerprints", headers, rows);
    }

    private static void openStatusScreen() {
        List<List<SecureChatTableScreen.Cell>> rows = new ArrayList<>();
        for (String line : PROTOCOL.statusLines()) {
            int split = line.indexOf(": ");
            if (split < 0) {
                rows.add(SecureChatTableScreen.row(new SecureChatTableScreen.Cell(line, SecureChatTableScreen.WHITE)));
            } else {
                String key = line.substring(0, split);
                String value = line.substring(split + 2);
                rows.add(SecureChatTableScreen.row(
                        new SecureChatTableScreen.Cell(key, SecureChatTableScreen.MUTED),
                        new SecureChatTableScreen.Cell(value, colorForValue(value))
                ));
            }
        }
        openTable("Secure Chat Status", List.of("Setting", "Value"), rows);
    }

    private static void openMessageTable(String title, String message) {
        openTable(title, List.of("Message"), List.of(SecureChatTableScreen.row(
                new SecureChatTableScreen.Cell(message, colorForValue(message))
        )));
    }

    private static void openTable(String title, List<String> headers, List<List<SecureChatTableScreen.Cell>> rows) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new SecureChatTableScreen(mc.screen, title, headers, rows)));
        }
    }

    private static int colorForValue(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.contains("connected: true") || value.equals("trusted") || value.equals("local")) {
            return SecureChatTableScreen.GREEN;
        }
        if (value.equals("false") || value.contains("connected: false") || value.contains("not connected") || value.contains("failed")) {
            return SecureChatTableScreen.RED;
        }
        if (value.equals("untrusted") || value.contains("rejected") || value.contains("wrong") || value.contains("expired")) {
            return SecureChatTableScreen.ORANGE;
        }
        if (value.equals("none") || value.equals("never") || value.equals("0")) {
            return SecureChatTableScreen.GRAY;
        }
        return SecureChatTableScreen.WHITE;
    }

    private static void exportPublicIdentity() {
        List<String> lines = PROTOCOL.exportBundleLines();
        String bundle = String.join(System.lineSeparator(), lines);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.keyboardHandler.setClipboard(bundle));
        }
        notice("Public identity copied to clipboard.");
        logLine("Public identity bundle copied to clipboard:");
        for (String line : lines) {
            logLine(line);
        }
    }

    private static CompletableFuture<Suggestions> suggestRecipients(
            CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            SuggestionsBuilder builder
    ) {
        List<String> names = new ArrayList<>(PROTOCOL.recipientSuggestions());
        names.addAll(minecraftPlayerNames());
        return suggestCommaSeparated(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestFriends(
            CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(PROTOCOL.friendSuggestions(), builder);
    }

    private static CompletableFuture<Suggestions> suggestCommaSeparated(List<String> values, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int comma = remaining.lastIndexOf(',');
        String prefix = comma >= 0 ? remaining.substring(0, comma + 1) : "";
        String token = comma >= 0 ? remaining.substring(comma + 1).trim() : remaining.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                builder.suggest(prefix + value);
            }
        }
        return builder.buildFuture();
    }

    private static List<String> minecraftPlayerNames() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        String self = Minecraft.getInstance().getUser().getName();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (!name.equalsIgnoreCase(self) && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Component prefixedLine(String line) {
        MutableComponent component = Component.literal(PREFIX).withStyle(s -> s.withColor(PREFIX_COLOR));
        appendColoredValue(component, line);
        return component;
    }

    private static void appendColoredValue(MutableComponent component, String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.endsWith(": true") || lower.endsWith(" true")) {
            int idx = line.lastIndexOf("true");
            component.append(Component.literal(line.substring(0, idx)).withStyle(s -> s.withColor(DEFAULT_TEXT_COLOR)));
            component.append(Component.literal(line.substring(idx)).withStyle(s -> s.withColor(TRUE_COLOR)));
            return;
        }
        if (lower.endsWith(": false") || lower.endsWith(" false")) {
            int idx = line.lastIndexOf("false");
            component.append(Component.literal(line.substring(0, idx)).withStyle(s -> s.withColor(DEFAULT_TEXT_COLOR)));
            component.append(Component.literal(line.substring(idx)).withStyle(s -> s.withColor(FALSE_COLOR)));
            return;
        }
        component.append(Component.literal(line).withStyle(s -> s.withColor(DEFAULT_TEXT_COLOR)));
    }
}
