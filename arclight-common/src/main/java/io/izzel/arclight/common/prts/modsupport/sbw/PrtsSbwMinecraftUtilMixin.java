/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sbw;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
import io.izzel.arclight.common.prts.support.PrtsSbwCompat;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.UUID;

/**
 * Narrows two send paths for a server with many players: the friend-or-foe update goes out
 * every few ticks while the operator left the mod setting at the shipped value, and the vehicle
 * shoot broadcast goes to the trackers instead of the whole server, falling back to the
 * broadcast whenever the vehicle cannot be resolved.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.MinecraftUtil", remap = false)
public abstract class PrtsSbwMinecraftUtilMixin {

    /** @author PRTS @reason the friend-or-foe update is sent on every tick of every player */
    @Overwrite(remap = false)
    public static void sendPacketTo(Player player, CustomPacketPayload packet) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "throttle-sbw-iff-payloads")
                && PrtsSbwCompat.isIffPayload(packet) && PrtsSbwCompat.iffThrottleApplies(packet)) {
            MinecraftServer server = serverPlayer.getServer();
            if (server != null && server.getTickCount() % PrtsSbwCompat.IFF_THROTTLE_TICKS != 0) {
                PrtsModSupportStats.count("sbw-iff-payloads-held");
                return;
            }
        }
        PacketDistributor.sendToPlayer(serverPlayer, packet);
    }

    /** @author PRTS @reason the shoot broadcast goes to the whole server instead of the trackers */
    @Overwrite(remap = false)
    public static void sendPacketToAll(CustomPacketPayload packet) {
        if (PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "narrow-sbw-vehicle-shoot")
                && PrtsSbwCompat.isVehicleShootPayload(packet)) {
            UUID vehicleId = PrtsSbwCompat.vehicleId(packet);
            if (vehicleId != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        Entity vehicle = level.getEntities().get(vehicleId);
                        if (vehicle != null) {
                            PrtsModSupportStats.count("sbw-shoot-broadcasts-narrowed");
                            PacketDistributor.sendToPlayersTrackingEntity(vehicle, packet);
                            return;
                        }
                    }
                }
            }
        }
        PacketDistributor.sendToAllPlayers(packet);
    }
}
