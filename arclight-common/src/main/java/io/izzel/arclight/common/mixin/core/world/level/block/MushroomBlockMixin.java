package io.izzel.arclight.common.mixin.core.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The tree type this mixin used to record at the ConfiguredFeature#place call of growMushroom is
// not recorded any more: the platform raises StructureGrowEvent for a bone-mealed mushroom from
// the block itself, and the recorded value never had a reader. Dropping the injector also frees
// the instruction, which is the one any future wrapper of that call would have to sit on.
@Mixin(MushroomBlock.class)
public class MushroomBlockMixin {

    @Redirect(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    public boolean arclight$blockSpread(ServerLevel world, BlockPos toPos, BlockState newState, int flags, BlockState state, ServerLevel worldIn, BlockPos fromPos) {
        return CraftEventFactory.handleBlockSpreadEvent(world, fromPos, toPos, newState, flags);
    }
}
