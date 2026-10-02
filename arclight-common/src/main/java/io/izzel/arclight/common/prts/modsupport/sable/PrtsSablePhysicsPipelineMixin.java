/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSableLocks;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs the two physics steps under the native lock the rope calls also take, so the non-reentrant
 * native library is never entered from two threads at once. The whole step is one critical
 * section, not a sequence of them.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class PrtsSablePhysicsPipelineMixin {

    @Decorate(method = "prePhysicsTicks", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializePrePhysicsTicks() throws Throwable {
        if (!PrtsSableLocks.NativeCalls.enabled()) {
            DecorationOps.callsite().invoke();
            return;
        }
        PrtsSableLocks.NativeCalls.lock();
        try {
            PrtsSableLocks.NativeCalls.noteSerializedCall();
            DecorationOps.callsite().invoke();
        } finally {
            PrtsSableLocks.NativeCalls.unlock();
        }
    }

    @Decorate(method = "physicsTick", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializePhysicsTick(double delta) throws Throwable {
        if (!PrtsSableLocks.NativeCalls.enabled()) {
            DecorationOps.callsite().invoke(delta);
            return;
        }
        PrtsSableLocks.NativeCalls.lock();
        try {
            PrtsSableLocks.NativeCalls.noteSerializedCall();
            DecorationOps.callsite().invoke(delta);
        } finally {
            PrtsSableLocks.NativeCalls.unlock();
        }
    }
}
