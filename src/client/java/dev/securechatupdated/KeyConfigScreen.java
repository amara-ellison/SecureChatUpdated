package dev.securechatupdated;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.security.SecureRandom;
import java.util.Base64;

public class KeyConfigScreen extends Screen {

    private final ChatScreen returnScreen;
    private EditBox passphraseField;

    public KeyConfigScreen(ChatScreen returnScreen) {
        super(Component.literal("Secure Chat Updated Key Configuration"));
        this.returnScreen = returnScreen;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        this.addRenderableWidget(new StringWidget(
            cx - this.font.width("Secure Chat Updated Key Configuration") / 2, cy - 30, 320, 14,
            Component.literal("Secure Chat Updated Key Configuration").withStyle(s -> s.withColor(0x55FF55)),
            this.font));

        int labelWidth = this.font.width("Key: ");
        int fieldWidth = 300;
        int totalWidth = labelWidth + fieldWidth;
        int rowX = cx - totalWidth / 2;

        this.addRenderableWidget(new StringWidget(
            rowX, cy - 4, labelWidth, 12,
            Component.literal("Key:").withStyle(s -> s.withColor(0xAAAAAA)),
            this.font));

        passphraseField = new EditBox(this.font, rowX + labelWidth, cy - 10, fieldWidth, 20,
            Component.literal("passphrase"));
            
        passphraseField.setMaxLength(200);
        passphraseField.setValue(new String(SecureChatUpdatedConfig.getPassphrase()));
        passphraseField.setHint(Component.literal("Enter key...").withStyle(s -> s.withColor(0x666666)));
        this.addRenderableWidget(passphraseField);
        this.setInitialFocus(passphraseField);

        this.addRenderableWidget(Button.builder(
            Component.literal("Save").withStyle(s -> s.withColor(0x55FF55)),
            btn -> saveAndReturn()
        ).bounds(cx - 91, cy + 16, 50, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            btn -> this.minecraft.setScreen(returnScreen)
        ).bounds(cx - 37, cy + 16, 50, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Generate Key").withStyle(s -> s.withColor(0xAAAAAA)),
            btn -> {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                passphraseField.setValue(Base64.getEncoder().encodeToString(key));
            }
        ).bounds(cx + 17, cy + 16, 75, 20).build());
    }

    private void saveAndReturn() {
        String raw = passphraseField.getValue().trim();
        if (raw.isEmpty()) {
          SecureChatUpdatedClient.notice("Warning: No key set!");
        }
        SecureChatUpdatedConfig.setPassphrase(raw);
        SecureChatUpdatedClient.notice("Key updated (" + raw.length() + " characters)");
        this.minecraft.setScreen(returnScreen);
    }

    @Override public boolean isPauseScreen() { return false; }
}
