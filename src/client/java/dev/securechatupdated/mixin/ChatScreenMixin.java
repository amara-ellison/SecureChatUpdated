package dev.securechatupdated.mixin;

import dev.securechatupdated.SecureChatUpdatedConfig;
import dev.securechatupdated.KeyConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
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
                              .append(Component.literal("Ctrl + Left Click").withStyle(s -> s.withColor(0x55FF55)))
                              .append(Component.literal(" to configure key\n").withStyle(s -> s.withColor(0xAAAAAA)))
                              .append(Component.literal("Left Click").withStyle(s -> s.withColor(0x55FF55)))
                              .append(Component.literal(" to toggle encryption").withStyle(s -> s.withColor(0xAAAAAA)));
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
            boolean ctrl = InputConstants.isKeyDown(
                Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL
            ) || InputConstants.isKeyDown(
                Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL
            );
            if (ctrl) {
                Minecraft.getInstance().setScreen(new KeyConfigScreen((ChatScreen)(Object)this));
            } else {
                SecureChatUpdatedConfig.setEnabled(!SecureChatUpdatedConfig.isEnabled());
                toggleButton.setMessage(getToggleLabel());
                Minecraft.getInstance().execute(() -> {
                    EditBox input = ((ChatScreenAccessor)(Object)this).scu$getInput();
                    this.setFocused(input);
                    input.setFocused(true);
                    input.moveCursorToEnd(false);
                });
            }
            cir.setReturnValue(true);
            cir.cancel();
        }
    }
    
    private static Component getToggleLabel() {
        return SecureChatUpdatedConfig.isEnabled()
            ? Component.literal("[SCU] ON").withStyle(s -> s.withColor(0x55FF55))
            : Component.literal("[SCU] OFF").withStyle(s -> s.withColor(0xAAAAAA));
    }
}
