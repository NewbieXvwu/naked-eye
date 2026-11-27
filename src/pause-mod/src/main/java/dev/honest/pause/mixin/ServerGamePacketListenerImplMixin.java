package dev.honest.pause.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The player's own per-tick update (hunger, suffocation, fire…) runs from the connection, gated only by
 *  "is the integrated server paused?". Treat a frozen tick manager as paused too. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isPaused()Z"))
    private boolean honest$pausedWhenFrozen(MinecraftServer server) {
        return server.isPaused() || !server.tickRateManager().runsNormally() && !dev.honest.pause.Watch.isWatcher(player);
    }
}
