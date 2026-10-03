/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The two counters of the body rotation control: how long the head has been stable and the yaw it
 * was stable around. Mob#tickHeadTurn steps them once per tick, so the whole-tick model of a mob
 * row has to read and write them. Each accessor is a plain field read or write.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(BodyRotationControl.class)
public interface PrtsBodyRotationControlStateMixin {

    @Accessor("headStableTime")
    int prts$headStableTime();

    @Accessor("headStableTime")
    void prts$setHeadStableTime(int value);

    @Accessor("lastStableYHeadRot")
    float prts$lastStableYHeadRot();

    @Accessor("lastStableYHeadRot")
    void prts$setLastStableYHeadRot(float value);
}
