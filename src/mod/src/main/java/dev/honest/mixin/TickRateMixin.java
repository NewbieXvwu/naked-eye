package dev.honest.mixin;

import dev.honest.HonestClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla clamps the client at >= 50 ms/tick: it follows a server that slows down (/tick rate 10)
 * but never one that speeds up. In simulation mode we let the client follow the server both ways,
 * so the agent's body runs on the same clock as the world.
 */
@Mixin(Minecraft.class)
public class TickRateMixin {
    @Shadow public ClientLevel level;

    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void honest$followFastServer(float def, CallbackInfoReturnable<Float> cir) {
        if (!HonestClient.followServerClock || level == null) return;
        TickRateManager t = level.tickRateManager();
        if (t.runsNormally()) cir.setReturnValue(t.millisecondsPerTick());
    }
}
