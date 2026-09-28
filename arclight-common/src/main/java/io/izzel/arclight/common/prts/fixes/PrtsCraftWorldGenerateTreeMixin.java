/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Both World#generateTree overloads this server ships are answered here.
 *
 * The one that takes a BlockChangeDelegate writes ServerLevel#captureTreeGeneration and
 * #captureBlockStates and reads #capturedBlockStates, and none of the three exists on this
 * platform, so the call throws NoSuchFieldError before any tree is built. The plain overload does
 * not throw, but it grows the tree straight into the chunk, which leaves a listener no chance to
 * see or stop it.
 *
 * PrtsGenerateTree builds the tree first and raises StructureGrowEvent for it, so both overloads
 * share the semantics the sapling path already has: the event is raised exactly once, cancelling it
 * keeps the world untouched, and the delegate receives the blocks instead of the world.
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

    /**
     * @author PRTS
     * @reason the CraftBukkit body drives three ServerLevel members this platform does not have
     */
    @Overwrite(remap = false)
    public boolean generateTree(Location location, TreeType type, BlockChangeDelegate delegate) {
        return PrtsGenerateTree.generateWithEvent((CraftWorld) (Object) this, location, type, delegate, rand);
    }

    /**
     * @author PRTS
     * @reason the tree has to exist before a listener can be asked whether it is kept
     */
    @Overwrite(remap = false)
    public boolean generateTree(Location location, TreeType type) {
        return PrtsGenerateTree.generateWithEvent((CraftWorld) (Object) this, location, type, null, rand);
    }
}
