package dev.honest.mixin;

import dev.honest.HonestClient;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animated textures (water, lava, fire…) upload a new frame to the GPU every tick: pointless when nothing is drawn. */
@Mixin(TextureManager.class)
public class HeadlessTexturesMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void honest$noAnimation(CallbackInfo ci) { if (HonestClient.headless) ci.cancel(); }
}
