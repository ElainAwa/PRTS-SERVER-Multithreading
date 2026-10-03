/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The mob fields the whole-tick model of a mob row reaches through accessors: the goal selector
 * whose control flags the tick recomputes every fifth tick, and the body rotation control whose
 * two counters the tick steps. Each accessor is a plain field read or write with no behaviour.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Mob.class)
public interface PrtsMobTickStateMixin {

    @Accessor("goalSelector")
    GoalSelector prts$goalSelector();

    @Accessor("bodyRotationControl")
    BodyRotationControl prts$bodyRotationControl();
}
