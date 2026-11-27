package dev.honest.pause.mixin;

import dev.honest.pause.Watch;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A watcher inside an agent can only look: anything its client tries to do is dropped. (Sneaking to get out
 *  comes with the player-input packet, which goes through.) */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class WatcherActionsMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handlePlayerAction", "handleUseItemOn", "handleUseItem", "handleInteract", "handleContainerClick",
            "handlePickItemFromBlock", "handlePickItemFromEntity", "handleSetCarriedItem", "handlePlaceRecipe",
            "handleContainerButtonClick", "handleSelectTrade", "handleRenameItem", "handleEditBook", "handleSignUpdate",
            "handleContainerSlotStateChanged", "handleSetCreativeModeSlot", "handleTeleportToEntityPacket"},
            at = @At("HEAD"), cancellable = true)
    private void nakedEye$lookOnly(CallbackInfo ci) {
        if (player != null && Watch.locked(player)) ci.cancel();
    }

    /** watchers don't talk: the agents would hear it (commands like /watch still work) */
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void nakedEye$noCoaching(CallbackInfo ci) {
        if (player != null && Watch.isWatcher(player)) {
            ci.cancel();
            player.level().getServer().execute(() -> player.sendSystemMessage(Component.literal("Watchers can't chat: the agents would hear it.")));
        }
    }
}
