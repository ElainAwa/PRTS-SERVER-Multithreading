/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the outermost neighbour-update call of one server level and reports it to the internal
 * quota counter. While the MACHINE window is over, a root call is handed to the bounded tick
 * deferral instead of running in place: it is replayed once, unchanged and in order, at this same
 * level tick's RETURN (same tick, same thread), so no call is lost or moved to another tick. The
 * module-level drain is only the fallback for a call issued after its level returned. A nested
 * propagation is not reported again and is never deferred by itself; the gate is off by default. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostGate;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostSpans;
import io.izzel.arclight.common.prts.kernel.observe.QuotaMachineWindow;
import io.izzel.arclight.common.prts.kernel.quota.MachineTickDeferral;
import io.izzel.arclight.common.prts.support.PrtsMachineReplay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerLevel.class)
public abstract class PrtsNeighborUpdateCostMixin {

    @WrapMethod(method = "updateNeighborsAt")
    private void prts$timeNeighborsAt(BlockPos pos, Block block, Operation<Void> original) {
        if (!QuotaCostGate.enabled()) {
            original.call(pos, block);
            return;
        }
        if (prts$deferMachineUpdate(pos, block, null)) {
            return;
        }
        boolean root = QuotaCostSpans.enterMachine();
        long startedAt = root ? System.nanoTime() : 0L;
        try {
            original.call(pos, block);
        } finally {
            QuotaCostSpans.exitMachine(root, startedAt);
        }
    }

    @WrapMethod(method = "updateNeighborsAtExceptFromFacing")
    private void prts$timeNeighborsAtExceptFromFacing(BlockPos pos, Block block, Direction facing,
                                                      Operation<Void> original) {
        if (!QuotaCostGate.enabled()) {
            original.call(pos, block, facing);
            return;
        }
        if (prts$deferMachineUpdate(pos, block, facing)) {
            return;
        }
        boolean root = QuotaCostSpans.enterMachine();
        long startedAt = root ? System.nanoTime() : 0L;
        try {
            original.call(pos, block, facing);
        } finally {
            QuotaCostSpans.exitMachine(root, startedAt);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void prts$drainMachineAtLevelReturn(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!MachineTickDeferral.hasWaiting(level)) {
            return;
        }
        // The same ServerLevel tick's own safe point: same tick, same thread. The module-level drain
        // is only the fallback for a call issued after its level returned.
        KernelModule module = KernelModule.instance();
        MachineTickDeferral.drainScope(level, module.guard().worldEpochs()::epochOf,
            PrtsMachineReplay.INSTANCE);
    }

    @Unique
    private boolean prts$deferMachineUpdate(BlockPos pos, Block block, Direction facing) {
        if (!QuotaMachineWindow.overActive() || MachineTickDeferral.inReplay()
            || QuotaCostSpans.machineDepth() != 0) {
            return false;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        KernelModule module = KernelModule.instance();
        String worldId = level.dimension().location().toString();
        return MachineTickDeferral.defer(level, worldId, module.tickIndex(),
            module.guard().worldEpochs().epochOf(worldId), pos, block, facing, true,
            PrtsMachineReplay.INSTANCE);
    }
}
