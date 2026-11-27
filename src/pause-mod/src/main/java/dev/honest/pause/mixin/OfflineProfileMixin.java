package dev.honest.pause.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** an offline player's profile, as the server makes it at login: with the skin skins.txt gives that name */
@Mixin(UUIDUtil.class)
public class OfflineProfileMixin {
    @Inject(method = "createOfflineProfile", at = @At("RETURN"), cancellable = true)
    private static void nakedEye$skin(String name, CallbackInfoReturnable<GameProfile> cir) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.SERVER) return;
        cir.setReturnValue(dev.honest.pause.Skins.dress(cir.getReturnValue()));
    }
}
