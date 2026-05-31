package dev.securechatupdated.mixin;

import dev.securechatupdated.SecureChatUpdatedClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class SendChatMixin {
    @Inject(
        method = "sendChat(Ljava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V",
            ordinal = 0
        ),
        cancellable = true
    )
    private void onSendChatPacket(String message, CallbackInfo ci) {
        if (!SecureChatUpdatedClient.shouldEncrypt(message)) return;
        ci.cancel();
        String encrypted = SecureChatUpdatedClient.encryptMessage(message);
        if (encrypted != null) {
            ((ClientPacketListener)(Object)this).sendChat(encrypted);
        }
    }
}
