package dev.securechatupdated.mixin;

import dev.securechatupdated.SecureChatUpdatedConfig;
import dev.securechatupdated.SecureChatUpdatedClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.components.Tooltip;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.input.MouseButtonEvent;

@Mixin(ChatScreen.class)
public class ChatScreenMixin extends Screen {
    protected ChatScreenMixin(Component title) { super(title); }

    private Button toggleButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void scu$addButtons(CallbackInfo ci) {
        Component hoverText = Component.literal("Secure Chat Updated\n").withStyle(s -> s.withColor(0x55FF55))
                              .append(Component.literal("Left Click").withStyle(s -> s.withColor(0x55FF55)))
                              .append(Component.literal(" to toggle secure MQTT sending").withStyle(s -> s.withColor(0xAAAAAA)));
        toggleButton = Button.builder(
            getToggleLabel(),
            btn -> {}
        ).bounds((this.width - 60) / 2, this.height - 32, 60, 14)
         .tooltip(Tooltip.create(hoverText))
         .build();
        this.addRenderableOnly(toggleButton);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void scu$handleClick(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() == 0 && toggleButton != null && toggleButton.isMouseOver(event.x(), event.y())) {
            toggleButton.playDownSound(Minecraft.getInstance().getSoundManager());
            SecureChatUpdatedClient.toggleEncryption();
            toggleButton.setMessage(getToggleLabel());
            Minecraft.getInstance().execute(() -> {
                EditBox input = ((ChatScreenAccessor)(Object)this).scu$getInput();
                this.setFocused(input);
                input.setFocused(true);
                input.moveCursorToEnd(false);
            });
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void scu$renderRecipientIndicator(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        String text = SecureChatUpdatedClient.recipientIndicatorText();
        if (text.isBlank()) {
            return;
        }

        int maxWidth = Math.max(40, this.width - 8);
        String fitted = fitText(text, maxWidth);
        int x = 4;
        int y = this.height - 25;
        graphics.fill(x - 2, y - 2, x + this.font.width(fitted) + 3, y + this.font.lineHeight + 1, 0x80000000);
        graphics.text(this.font, fitted, x, y, 0xFF55FF55, false);
    }
    
    private static Component getToggleLabel() {
        return SecureChatUpdatedConfig.isEnabled()
            ? Component.literal("[SCU] ON").withStyle(s -> s.withColor(0x55FF55))
            : Component.literal("[SCU] OFF").withStyle(s -> s.withColor(0xAAAAAA));
    }

    private String fitText(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }
}
