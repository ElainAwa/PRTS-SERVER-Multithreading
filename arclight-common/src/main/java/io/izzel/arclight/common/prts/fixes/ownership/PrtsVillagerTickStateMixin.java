/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The two villager fields the whole-tick model cannot reach through the public API: the gossip
 * decay clock and the gossip container, which the tick decays every game day. The model runs that
 * step in the commit segment, so it reads and writes them there. Each accessor is a plain field
 * read or write with no behaviour attached.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Villager.class)
public interface PrtsVillagerTickStateMixin {

    @Accessor("lastGossipDecayTime")
    long prts$lastGossipDecayTime();

    @Accessor("lastGossipDecayTime")
    void prts$setLastGossipDecayTime(long value);

    @Accessor("gossips")
    GossipContainer prts$gossips();
}
