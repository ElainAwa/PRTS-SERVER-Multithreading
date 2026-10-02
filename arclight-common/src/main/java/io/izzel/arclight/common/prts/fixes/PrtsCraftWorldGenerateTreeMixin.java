/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Answers both World#generateTree overloads. The one with a BlockChangeDelegate throws on three
 * ServerLevel members this platform lacks; the plain one grows straight into the chunk and gives
 * a listener no say. PrtsGenerateTree builds the tree first and raises StructureGrowEvent, so a
 * cancel keeps the world untouched and the delegate receives the blocks instead of the world.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsGenerateTree;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(value = CraftWorld.class, remap = false)
public abstract class PrtsCraftWorldGenerateTreeMixin {

    // @formatter:off
    @Shadow @Final private static Random rand;
    // @formatter:on

    /** @author PRTS @reason the CraftBukkit body drives three ServerLevel members this platform does not have */
    @Overwrite(remap = false)
    public boolean generateTree(Location location, TreeType type, BlockChangeDelegate delegate) {
        return PrtsGenerateTree.generateWithEvent((CraftWorld) (Object) this, location, type, delegate, rand);
    }

    /** @author PRTS @reason the tree has to exist before a listener can be asked whether it is kept */
    @Overwrite(remap = false)
    public boolean generateTree(Location location, TreeType type) {
        return PrtsGenerateTree.generateWithEvent((CraftWorld) (Object) this, location, type, null, rand);
    }
}
