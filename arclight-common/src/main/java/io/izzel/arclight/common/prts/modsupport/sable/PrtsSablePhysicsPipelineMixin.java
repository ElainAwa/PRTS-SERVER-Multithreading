/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSableLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Wraps the two whole physics steps under the native lock the rope calls also take, so the
 * non-reentrant native library is never entered from two threads at once. The whole step is one
 * critical section, and the unlock runs on both the normal and the exceptional return.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class PrtsSablePhysicsPipelineMixin {

    @WrapMethod(method = "prePhysicsTicks", remap = false)
    private void prts$serializePrePhysicsTicks(Operation<Void> original) throws Throwable {
        if (!PrtsSableLocks.NativeCalls.enabled()) {
            original.call();
            return;
        }
        PrtsSableLocks.NativeCalls.lock();
        try {
            PrtsSableLocks.NativeCalls.noteSerializedCall();
            original.call();
        } finally {
            PrtsSableLocks.NativeCalls.unlock();
        }
    }

    @WrapMethod(method = "physicsTick", remap = false)
    private void prts$serializePhysicsTick(double delta, Operation<Void> original) throws Throwable {
        if (!PrtsSableLocks.NativeCalls.enabled()) {
            original.call(delta);
            return;
        }
        PrtsSableLocks.NativeCalls.lock();
        try {
            PrtsSableLocks.NativeCalls.noteSerializedCall();
            original.call(delta);
        } finally {
            PrtsSableLocks.NativeCalls.unlock();
        }
    }
}
