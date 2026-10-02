/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Both World#generateTree overloads: the delegate form touches ServerLevel members this platform
 * lacks and the plain form gives a listener no say; PrtsGenerateTree builds first and raises the event. */
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

    @Shadow @Final private static Random rand;

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
