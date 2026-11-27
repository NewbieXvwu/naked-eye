package dev.honest.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.honest.HonestClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Closing the window (the red button, Cmd+Q) hides it instead of quitting: the agent stays in the world.
 *  To really stop: ./stop.sh */
@Mixin(Window.class)
public class WindowCloseMixin {
    @Inject(method = "onQuitRequested", at = @At("HEAD"), cancellable = true)
    private void honest$hideInstead(CallbackInfo ci) {
        if (HonestClient.noScreen) return;
        ci.cancel();
        HonestClient.setShown(false);
    }
}
