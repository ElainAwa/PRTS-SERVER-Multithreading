/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The three fields of the walk animation state, which the entity animation update decays once per
 * tick. The whole-tick model of a mob row reproduces that update, so it reads and writes them.
 * Each accessor is a plain field read or write with no behaviour attached.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(WalkAnimationState.class)
public interface PrtsWalkAnimationStateMixin {

    @Accessor("speedOld")
    float prts$speedOld();

    @Accessor("speedOld")
    void prts$setSpeedOld(float value);

    @Accessor("speed")
    float prts$speed();

    @Accessor("speed")
    void prts$setSpeed(float value);

    @Accessor("position")
    float prts$position();

    @Accessor("position")
    void prts$setPosition(float value);
}
