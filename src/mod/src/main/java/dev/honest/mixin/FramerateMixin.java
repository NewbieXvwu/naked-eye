package dev.honest.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.honest.HonestClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Not drawing (window hidden or minimised): run just enough frames to keep up with the game's ticks
 *  (a frame may run several ticks), instead of spinning through empty frames. */
@Mixin(FramerateLimitTracker.class)
public class FramerateMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void honest$idleFrames(CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (!HonestClient.headless && (mc.getWindow() == null || !mc.getWindow().isIconified())) return;
        // frozen while the brain thinks: nothing moves, a few frames a second keep the API answering
        if (mc.level != null && !mc.level.tickRateManager().runsNormally()) { cir.setReturnValue(5); return; }
        float rate = mc.level != null ? mc.level.tickRateManager().tickrate() : 20f;
        cir.setReturnValue(Math.max(20, Math.min(120, Math.round(rate / 2))));
    }
}
