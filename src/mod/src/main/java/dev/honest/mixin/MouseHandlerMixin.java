package dev.honest.mixin;

import dev.honest.Controller;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The agent holds the mouse even though the (virtual) window never has OS focus. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
    private void honest$grabbed(CallbackInfoReturnable<Boolean> cir) {
        if (Controller.active) cir.setReturnValue(true);
    }
}
