package dev.honest.pause.mixin;

import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla's `/tick freeze` deliberately keeps players ticking. For a true pause, freeze them too (both sides). */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerMixin {
    @Inject(method = "isEntityFrozen", at = @At("HEAD"), cancellable = true)
    private void honest$freezePlayersToo(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // (watchers aren't in the game: they may fly around a frozen world)
        if (entity instanceof Player p && !((TickRateManager) (Object) this).runsNormally() && !dev.honest.pause.Watch.isWatcher(p)) cir.setReturnValue(true);
    }
}
