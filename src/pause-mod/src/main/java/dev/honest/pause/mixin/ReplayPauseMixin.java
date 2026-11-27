package dev.honest.pause.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ServerReplay only lets Flashback recordings pause. For the ReplayMod format a pause just stops the clock — every
 *  packet is still written (the writer's pause is a no-op, record() doesn't look at it) — which is exactly what a
 *  frozen world needs: nothing lost, no dead air. (Optional: applies only when ServerReplay is installed.) */
@Pseudo
@Mixin(targets = "net.casual.arcade.replay.recorder.player.ReplayPlayerRecorder", remap = false)
public class ReplayPauseMixin {
    @Inject(method = "canPauseRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void nakedEye$clockMayStop(CallbackInfoReturnable<Boolean> cir) { cir.setReturnValue(true); }
}
