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

import java.util.List;

/**
 * Runs the rope calls under the same native lock as the physics pipeline: the server thread
 * creates, reads and removes ropes while the physics thread is inside the same library.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle", remap = false)
public abstract class PrtsSableRopeMixin {

    @Decorate(method = "readPose", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializeReadPose(List<?> out) throws Throwable {
        if (!PrtsSableLocks.NativeCalls.enabled()) {
            DecorationOps.callsite().invoke(out);
            return;
        }
        PrtsSableLocks.NativeCalls.lock();
        try {
            PrtsSableLocks.NativeCalls.noteSerializedCall();
            DecorationOps.callsite().invoke(out);
        } finally {
            PrtsSableLocks.NativeCalls.unlock();
        }
    }

    @Decorate(method = "remove", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializeRemove() throws Throwable {
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
}
