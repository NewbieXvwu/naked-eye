package dev.honest.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nor may a real keyboard: no ESC menu, no inventory, no walking for the agent. */
@Mixin(KeyboardHandler.class)
public class SpectatorKeysMixin {
    @Inject(method = {"keyPress", "charTyped"}, at = @At("HEAD"), cancellable = true)
    private void honest$watchOnly(CallbackInfo ci) { ci.cancel(); }
}
