/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the outermost neighbour-update call of one server level and reports it to the internal
 * quota counter. The update itself is called exactly as before; only a span is read around it.
 * Nested propagation is not reported again, and the gate is off by default. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostGate;
import io.izzel.arclight.common.prts.kernel.observe.QuotaCostSpans;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Level.class)
public abstract class PrtsNeighborUpdateCostMixin {

    @WrapMethod(method = "updateNeighborsAt")
    private void prts$timeNeighborsAt(BlockPos pos, Block block, Operation<Void> original) {
        if (!QuotaCostGate.enabled()) {
            original.call(pos, block);
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
        boolean root = QuotaCostSpans.enterMachine();
        long startedAt = root ? System.nanoTime() : 0L;
        try {
            original.call(pos, block, facing);
        } finally {
            QuotaCostSpans.exitMachine(root, startedAt);
        }
    }
}
