package dev.honest.mixin;

import dev.honest.Hurt;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server tells every client what hurt an entity (that's what tilts your screen toward the hit): keep mine. */
@Mixin(LivingEntity.class)
public class DamageEventMixin {
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void honest$hurt(DamageSource src, CallbackInfo ci) {
        if ((Object) this == net.minecraft.client.Minecraft.getInstance().player) Hurt.record(src);
    }
}
