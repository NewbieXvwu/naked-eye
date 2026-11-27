package dev.honest.mixin;

import dev.honest.HonestClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.x waits until the render section around the player has been *compiled into GPU meshes*
 * before closing the loading screen (with a timeout fallback). Headless, meshes are never built,
 * so we'd always hit the timeout. The chunk data itself has arrived — that's all we need.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public class LoadTrackerMixin {
    @Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
    private void honest$readyWithoutMeshes(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (HonestClient.headless && mc.player != null && mc.level != null
                && mc.level.hasChunkAt(mc.player.blockPosition())) cir.setReturnValue(true);
    }
}
