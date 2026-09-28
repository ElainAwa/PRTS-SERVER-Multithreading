package io.izzel.arclight.common.mixin.core.world.level.block;

import net.minecraft.world.level.block.SaplingBlock;
import org.spongepowered.asm.mixin.Mixin;

// Nothing here hooks the tree that grows out of a sapling: StructureGrowEvent has no dispatch point
// on this platform, and the TreeType that the mushroom and fungus blocks capture has no reader
// either. Such a hook belongs where the platform actually places the tree (TreeGrower and
// SaplingBlock#performBonemeal, with the placed blocks collected before they are written), and it
// needs a verification path of its own. The old commented-out body came from upstream and made this
// class look as if it still carried an injector.
@Mixin(SaplingBlock.class)
public abstract class SaplingBlockMixin {
}
