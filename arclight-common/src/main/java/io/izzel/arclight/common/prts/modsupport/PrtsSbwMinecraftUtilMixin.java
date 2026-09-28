/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
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
 * Two send paths of the optional vehicle mod, narrowed for a server with many players.
 *
 * <p>The friend-or-foe update is sent for every player on every tick; it only goes out every few
 * ticks now, and only while the operator left the mod setting at the value the mod ships. Every
 * other payload keeps its path unchanged. The vehicle shoot broadcast goes to the players tracking
 * the vehicle instead of the whole server, and falls back to the broadcast whenever the vehicle
 * cannot be resolved - an unloaded or already removed vehicle must not lose the message.</p>
 *
 * <p>Nothing here is compiled against the mod: the payload kinds are recognised by class name and
 * the one value read from the mod is read reflectively. A server without the mod never loads this
 * class, and a value that cannot be read keeps the mod's own behaviour.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.tools.MinecraftUtil", remap = false)
public abstract class PrtsSbwMinecraftUtilMixin {

    /**
     * Sends a payload to one player, spreading the friend-or-foe update over several ticks.
     *
     * @param player receiver
     * @param packet payload to send
     */
    /**
     * @author PRTS
     * @reason the friend-or-foe update is sent on every tick of every player
     */
    @Overwrite(remap = false)
    public static void sendPacketTo(Player player, CustomPacketPayload packet) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (PrtsSbwCompat.isIffPayload(packet) && PrtsSbwCompat.iffThrottleApplies(packet)) {
            MinecraftServer server = serverPlayer.getServer();
            if (server != null && server.getTickCount() % PrtsSbwCompat.IFF_THROTTLE_TICKS != 0) {
                return;
            }
        }
        PacketDistributor.sendToPlayer(serverPlayer, packet);
    }

    /**
     * Sends a payload to the whole server, narrowing the vehicle shoot broadcast to its trackers.
     *
     * @param packet payload to send
     */
    /**
     * @author PRTS
     * @reason the shoot broadcast goes to the whole server instead of the trackers
     */
    @Overwrite(remap = false)
    public static void sendPacketToAll(CustomPacketPayload packet) {
        if (PrtsSbwCompat.isVehicleShootPayload(packet)) {
            UUID vehicleId = PrtsSbwCompat.vehicleId(packet);
            if (vehicleId != null) {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        Entity vehicle = level.getEntities().get(vehicleId);
                        if (vehicle != null) {
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
