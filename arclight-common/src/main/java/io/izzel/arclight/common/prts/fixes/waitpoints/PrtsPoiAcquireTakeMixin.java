/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The real point-of-interest take at the vanilla acquisition site. Behaviour: when the bridge is
 * enabled, the acquisition reads the manager's take result before it writes the memory or fires
 * the notification, so a take that did not happen leaves no memory and no notification. Why here:
 * this synthetic lambda is the one place where the vanilla code drops that result and then writes
 * the caller's state unconditionally. Invariant: with the bridge off the vanilla body runs
 * untouched; with it on the winning take writes the same memory/notification as vanilla, and the
 * losing take writes neither. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.izzel.arclight.common.prts.kernel.domain.entity.interaction.PoiSingleWinnerWiring;

import java.util.Optional;
import java.util.function.Predicate;

// The target is the synthetic lambda that the compiler generates for the ifPresent consumer. The
// lambda name and signature are checked by the startup validation; a future Minecraft revision that
// renames it fails there instead of silently running the old unconditional write.
@Mixin(AcquirePoi.class)
public abstract class PrtsPoiAcquireTakeMixin {

    @Inject(method = "lambda$create$4", at = @At("HEAD"), cancellable = true)
    private static void prts$takeResultDrivesTheWrite(PoiManager poiManager,
                                                     Predicate<Holder<PoiType>> typePredicate,
                                                     BlockPos record, MemoryAccessor memory,
                                                     ServerLevel level, Optional<Byte> notifyByte,
                                                     PathfinderMob mob,
                                                     it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> jittered,
                                                     Holder<PoiType> type,
                                                     CallbackInfo ci) {
        if (!PoiSingleWinnerWiring.enabled()) {
            return;
        }
        Optional<BlockPos> taken = poiManager.take(typePredicate,
            (candidateType, candidatePos) -> candidatePos.equals(record), record, 1);
        if (taken.isEmpty()) {
            // The record went to another owner between the scan and the take: no memory, no
            // notification, no debug packet. The retry map is kept so the next attempt jitters.
            ci.cancel();
            return;
        }
        memory.set(GlobalPos.of(level.dimension(), record));
        notifyByte.ifPresent(event -> level.broadcastEntityEvent(mob, event));
        jittered.clear();
        DebugPackets.sendPoiTicketCountPacket(level, record);
        ci.cancel();
    }
}
