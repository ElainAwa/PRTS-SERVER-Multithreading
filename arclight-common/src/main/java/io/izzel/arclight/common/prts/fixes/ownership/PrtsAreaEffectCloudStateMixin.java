/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The cloud fields the whole-tick model cannot reach through the public API: the wait time, the
 * life, the per-tick radius step and the contents whose effects decide whether the tick has to
 * query the entities in the cloud. The wait flag is a synched-data value the tick writes through a
 * protected setter, so the model reaches it through an invoker. Each member is a plain read or the
 * vanilla write with no behaviour attached.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.item.alchemy.PotionContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(AreaEffectCloud.class)
public interface PrtsAreaEffectCloudStateMixin {

    @Accessor("waitTime")
    int prts$waitTime();

    @Accessor("duration")
    int prts$duration();

    @Accessor("radiusPerTick")
    float prts$radiusPerTick();

    @Accessor("potionContents")
    PotionContents prts$potionContents();

    @Invoker("setWaiting")
    void prts$setWaiting(boolean waiting);
}
