package dev.securechatupdated;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.HoverEvent;

public class SecureChatUpdatedClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SecureChatUpdatedConfig.load();

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith(".sc")) {
                handleCommand(message.substring(3).trim());
                return false;
            }
            return true;
        });

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.sendSystemMessage(message);
            return false;
        });

        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            String raw = message.getString();
            Component result = transformComponent(message);

            if (result != message) {
                return Component.empty()
                        .append(Component.literal("[Secure Chat] ").withStyle(s -> s.withColor(0x55FF55)))
                        .append(result);
            }
            if (raw.startsWith("[Secure Chat] ")) {
                return Component.literal("[Secure Chat Spoof Detected!] ").withStyle(s -> s.withColor(0xFF0000)).append(message);
            }
            return message;
        });
    }

    public static boolean shouldEncrypt(String message) {
        return SecureChatUpdatedConfig.isEnabled() && !message.startsWith(SecureChatUpdatedCryptoUtil.PREFIX);
    }

    public static String encryptMessage(String message) {
        try {
            String encrypted = SecureChatUpdatedCryptoUtil.PREFIX + SecureChatUpdatedCryptoUtil.encrypt(message, SecureChatUpdatedConfig.getPassphrase());
            if (encrypted.length() > 256) {
                notice("Message too long after encryption (" + encrypted.length() + "/256 chars)");
                return null;
            }
            return encrypted;
        } catch (Exception e) {
            notice("Encrypt failed: " + e.getMessage());
            return null;
        }
    }

    private static Component transformComponent(Component root) {
        if (root.getContents() instanceof PlainTextContents.LiteralContents lc) {
            String content = lc.text();
            int idx = content.indexOf(SecureChatUpdatedCryptoUtil.PREFIX);
            if (idx >= 0) {
                return decryptNode(content, idx, root.getStyle());
            }
            return root;
        }

        if (root.getContents() instanceof TranslatableContents tc) {
            boolean changed = false;
            Object[] oldArgs = tc.getArgs();
            Object[] newArgs = new Object[oldArgs.length];
            for (int i = 0; i < oldArgs.length; i++) {
                if (oldArgs[i] instanceof Component argComponent) {
                    Component transformed = transformComponent(argComponent);
                    newArgs[i] = transformed;
                    if (transformed != argComponent) changed = true;
                } else {
                    newArgs[i] = oldArgs[i];
                }
            }
            if (changed) {
                MutableComponent rebuilt = MutableComponent.create(
                    new TranslatableContents(tc.getKey(), tc.getFallback(), newArgs)
                ).withStyle(root.getStyle());
                for (Component s : root.getSiblings()) rebuilt.append(s);
                return rebuilt;
            }
        }

        List<Component> siblings = root.getSiblings();
        if (siblings.isEmpty()) return root;

        boolean changed = false;
        List<Component> newSiblings = new ArrayList<>(siblings.size());
        for (Component sibling : siblings) {
            Component transformed = transformComponent(sibling);
            newSiblings.add(transformed);
            if (transformed != sibling) changed = true;
        }
        if (!changed) return root;

        MutableComponent rebuilt = MutableComponent.create(root.getContents()).withStyle(root.getStyle());
        for (Component s : newSiblings) rebuilt.append(s);
        return rebuilt;
    }

    private static Component decryptNode(String content, int idx, net.minecraft.network.chat.Style style) {
        String payload = content.substring(idx + SecureChatUpdatedCryptoUtil.PREFIX.length());
        String prefix = content.substring(0, idx);
        MutableComponent result = Component.literal(prefix).withStyle(style);

        Component hoverText = Component.literal("Ciphertext:\n")
                .append(Component.literal(SecureChatUpdatedCryptoUtil.PREFIX + payload).withStyle(s -> s.withColor(0xAAAAAA)));

        SecureChatUpdatedCryptoUtil.DecryptResult dr = SecureChatUpdatedCryptoUtil.decrypt(payload, SecureChatUpdatedConfig.getPassphrase());
        switch (dr.error()) {
            case OK -> result.append(Component.literal(dr.plaintext()).withStyle(s -> s
                    .withColor(0x55FF55)
                    .withHoverEvent(new HoverEvent.ShowText(hoverText))));
            case REPLAY -> result.append(Component.literal("[SCU: Blocked Replay Attack!]").withStyle(s -> s
                    .withColor(0xFF5500)
                    .withHoverEvent(new HoverEvent.ShowText(hoverText))));
            case EXPIRED -> result.append(Component.literal("[SCU: Blocked Expired Text!]").withStyle(s -> s
                    .withColor(0xFFAA00)
                    .withHoverEvent(new HoverEvent.ShowText(hoverText))));
            case WRONG_KEY -> result.append(Component.literal("[SCU: Wrong Key]").withStyle(s -> s
                    .withColor(0xFF5555)
                    .withHoverEvent(new HoverEvent.ShowText(hoverText))));
        }
        return result;
    }

    private static void handleCommand(String args) {
        String[] parts = args.split(" ", 2);
        switch (parts[0]) {
            case "on"     -> { SecureChatUpdatedConfig.setEnabled(true);  notice("enabled"); }
            case "off"    -> { SecureChatUpdatedConfig.setEnabled(false); notice("disabled"); }
            case "key"    -> {
                if (parts.length < 2) { notice("usage: .sc key <passphrase>"); return; }
                SecureChatUpdatedConfig.setPassphrase(parts[1]);
                notice("passphrase updated (" + parts[1].length() + " chars)");
            }
            case "status" -> notice((SecureChatUpdatedConfig.isEnabled() ? "ON" : "OFF")
                    + " | key length: " + SecureChatUpdatedConfig.getPassphrase().length + " chars");
            default       -> notice("commands: .sc on|off|key <pass>|status");
        }
    }

    public static void notice(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[Secure Chat Updated] " + msg));
        }
    }
}
