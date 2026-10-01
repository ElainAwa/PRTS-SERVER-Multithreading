/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSableNativeLock;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Runs the rope calls of the optional physics mod under the native lock.
 *
 * <p>A rope is created, read and removed from the server thread while the physics thread is inside
 * the same native library. Both sides take the lock the pipeline member of this batch takes, which
 * is what makes the two paths mutually exclusive.</p>
 *
 * <p>Only the two methods that reach the library are wrapped; a rope mutation that stays in Java
 * is not, so the lock is held as briefly as the problem allows.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle", remap = false)
public abstract class PrtsSableRopeMixin {

    /**
     * Reads the pose of a rope under the native lock.
     *
     * @param out list the pose is written into
     * @throws Throwable whatever the wrapped method throws
     */
    @Decorate(method = "readPose", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializeReadPose(List<?> out) throws Throwable {
        if (!PrtsSableNativeLock.enabled()) {
            DecorationOps.callsite().invoke(out);
            return;
        }
        PrtsSableNativeLock.lock();
        try {
            PrtsSableNativeLock.noteSerializedCall();
            DecorationOps.callsite().invoke(out);
        } finally {
            PrtsSableNativeLock.unlock();
        }
    }

    /**
     * Removes a rope under the native lock.
     *
     * @throws Throwable whatever the wrapped method throws
     */
    @Decorate(method = "remove", at = @At("HEAD"), remap = false, inject = true)
    private void prts$serializeRemove() throws Throwable {
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
}
