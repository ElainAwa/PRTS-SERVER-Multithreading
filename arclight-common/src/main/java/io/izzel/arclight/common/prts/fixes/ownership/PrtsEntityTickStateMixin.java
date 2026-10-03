/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The entity fields the whole-tick model cannot reach through the public API: the first-tick flag,
 * the boarding cooldown, the block-state memo the base tick clears, and the eye-in-water flag the
 * fluid update rewrites. Each accessor is a plain field read or write with no behaviour attached.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Entity.class)
public interface PrtsEntityTickStateMixin {

    @Accessor("firstTick")
    boolean prts$firstTick();

    @Accessor("boardingCooldown")
    int prts$boardingCooldown();

    @Accessor("wasEyeInWater")
    boolean prts$wasEyeInWater();

    @Accessor("inBlockState")
    void prts$setInBlockState(BlockState state);

    @Accessor("portalProcess")
    PortalProcessor prts$portalProcess();
}
