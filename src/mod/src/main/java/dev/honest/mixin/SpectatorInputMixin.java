package dev.honest.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The window is for watching: a real mouse over it must not turn the agent's head or click for it.
 *  (The agent's own camera and clicks don't come through here.) */
@Mixin(MouseHandler.class)
public class SpectatorInputMixin {
    @Inject(method = {"onMove", "onButton", "onScroll", "onDrop"}, at = @At("HEAD"), cancellable = true)
    private void honest$watchOnly(CallbackInfo ci) { ci.cancel(); }
}
