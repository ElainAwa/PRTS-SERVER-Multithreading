/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The one combat-tracker field the whole-tick model has to read: while a row is taking damage its
 * periodic recheck can clear the tracker, and that branch is outside the model, so such a row is
 * refused instead of answered for.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.damagesource.CombatTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(CombatTracker.class)
public interface PrtsCombatTrackerStateMixin {

    @Accessor("takingDamage")
    boolean prts$takingDamage();
}
