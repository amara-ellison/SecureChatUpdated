package dev.securechatupdated.mixin;

import dev.securechatupdated.SecureChatUpdatedClient;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void scu$decorateSecureChatPlayer(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(SecureChatUpdatedClient.decorateTabPlayerName(info.getProfile().name(), cir.getReturnValue()));
    }
}
