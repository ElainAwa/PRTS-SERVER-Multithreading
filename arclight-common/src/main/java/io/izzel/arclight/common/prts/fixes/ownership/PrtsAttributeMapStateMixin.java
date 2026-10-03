/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The pending attribute updates of one entity. The tick refreshes them every tick, and that write
 * is outside the model, so a row with a pending update is refused instead of answered for. The
 * accessor is a plain field read.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(AttributeMap.class)
public interface PrtsAttributeMapStateMixin {

    @Accessor("attributesToUpdate")
    Set<AttributeInstance> prts$pendingAttributes();
}
