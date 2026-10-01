/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.tacz;

import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsTaczGunOperatorCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches the outgoing packet path for the respawn packet that breaks a gun mod's client state.
 *
 * <p>The class only applies while the gun mod is present, so a server without it pays neither the
 * injection nor the type check below. See {@link PrtsTaczGunOperatorCompat} for what is repaired
 * and why the repair has to happen after the packet was pushed rather than on the player's own
 * respawn handling, which a skin refresh does not go through.</p>
 */
@LoadIfMod(modid = "tacz", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PrtsTaczRespawnPacketMixin {

    /**
     * Repairs the gun state of a player whose respawn packet is on its way out.
     *
     * @param packet packet about to be sent
     * @param ci callback handle
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void prts$resyncGunOperatorOnRespawn(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundRespawnPacket)) {
            return;
        }
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "resync-tacz-gun-state-on-respawn")) {
            return;
        }
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return;
        }
        ServerPlayer player = listener.player;
        MinecraftServer server = player.getServer();
        if (server == null || server.isSameThread()) {
            PrtsTaczGunOperatorCompat.resetAndResync(player);
        } else {
            server.execute(() -> PrtsTaczGunOperatorCompat.resetAndResync(player));
        }
    }
}
