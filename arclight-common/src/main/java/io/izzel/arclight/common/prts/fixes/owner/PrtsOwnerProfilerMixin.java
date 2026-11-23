/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.owner;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerBodyMarker;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Gives an opt-in parallel owner body its own inactive profiler instead of the level's shared one.
 * The host path never sets the marker and keeps the original supplier. */
@Mixin(Level.class)
public abstract class PrtsOwnerProfilerMixin {

    @WrapMethod(method = "getProfiler")
    private ProfilerFiller prts$ownerProfiler(Operation<ProfilerFiller> original) {
        return OwnerBodyMarker.active() ? InactiveProfiler.INSTANCE : original.call();
    }
}
