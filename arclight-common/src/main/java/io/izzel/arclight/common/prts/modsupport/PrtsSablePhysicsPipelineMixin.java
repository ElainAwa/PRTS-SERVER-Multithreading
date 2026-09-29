/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSableNativeLock;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs the two physics steps of the optional physics mod under the native lock.
 *
 * <p>The step and the tick of that class call into the native physics library, and so do the rope
 * calls of the server thread (see the rope member of this batch). Both sides take the same lock
 * here, which is what keeps the library from being entered twice at the same time.</p>
 *
 * <p>The caller is wrapped rather than its call sites: the two methods each make more than one
 * native call, and the whole step has to be one critical section, not a sequence of them. The
 * switch is on by default, because what it prevents is a deadlock, not a difference in cost.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class PrtsSablePhysicsPipelineMixin {

    /**
     * Runs the pre-step tick under the native lock.
     *
     * @throws Throwable whatever the wrapped method throws
     */
    @Decorate(method = "prePhysicsTicks", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializePrePhysicsTicks() throws Throwable {
        if (!PrtsSableNativeLock.enabled()) {
            DecorationOps.callsite().invoke();
            return;
        }
        PrtsSableNativeLock.lock();
        try {
            PrtsSableNativeLock.noteSerializedCall();
            DecorationOps.callsite().invoke();
        } finally {
            PrtsSableNativeLock.unlock();
        }
    }

    /**
     * Runs the physics step under the native lock.
     *
     * @param delta time the step advances
     * @throws Throwable whatever the wrapped method throws
     */
    @Decorate(method = "physicsTick", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializePhysicsTick(double delta) throws Throwable {
        if (!PrtsSableNativeLock.enabled()) {
            DecorationOps.callsite().invoke(delta);
            return;
        }
        PrtsSableNativeLock.lock();
        try {
            PrtsSableNativeLock.noteSerializedCall();
            DecorationOps.callsite().invoke(delta);
        } finally {
            PrtsSableNativeLock.unlock();
        }
    }
}
