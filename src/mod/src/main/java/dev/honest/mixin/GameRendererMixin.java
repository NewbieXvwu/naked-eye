package dev.honest.mixin;

import dev.honest.HonestClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Truly headless mode. Since 26.x the frame is update -> extract -> render, and only the last
 * phase talks to the GPU. We skip just that: lighting, camera, GUI state and render-state
 * extraction all still run, so no other workaround is needed.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render()V", at = @At("HEAD"), cancellable = true)
    private void honest$skipDraw(CallbackInfo ci) {
        if (HonestClient.headless || net.minecraft.client.Minecraft.getInstance().getWindow().isIconified()) ci.cancel();
    }
}
