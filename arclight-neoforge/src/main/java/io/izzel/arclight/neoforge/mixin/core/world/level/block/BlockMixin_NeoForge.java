package io.izzel.arclight.neoforge.mixin.core.world.level.block;

import io.izzel.arclight.common.bridge.core.world.level.block.BlockBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collections;
import java.util.List;

@Mixin(Block.class)
public abstract class BlockMixin_NeoForge implements BlockBridge, IBlockExtension {

    /**
     * Replaces a missing drop list with an empty one on the way into the block drop event.
     *
     * <p>The platform collects the dropped item entities into a list and passes it on. When the
     * collection was not paired - a nested break, or a break whose collection was already stopped -
     * the list is null, and passing null on ends in a crash inside the event instead of a break that
     * simply drops nothing. The entities themselves were already spawned by that point, so an empty
     * list here keeps the event and the world consistent. The normal path is not touched.</p>
     *
     * @param drops the list being passed to the event
     * @return the same list, or an empty one when it is missing
     */
    @ModifyArg(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/common/CommonHooks;handleBlockDrops(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/util/List;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V"),
            index = 4)
    private static List<ItemEntity> prts$guardNullBlockDrops(List<ItemEntity> drops) {
        return drops == null ? Collections.emptyList() : drops;
    }

    @Override
    public boolean bridge$forge$onCropsGrowPre(Level level, BlockPos pos, BlockState state, boolean def) {
        return CommonHooks.canCropGrow(level, pos, state, def);
    }

    @Override
    public void bridge$forge$onCropsGrowPost(Level level, BlockPos pos, BlockState state) {
        CommonHooks.fireCropGrowPost(level, pos, state);
    }

    @Override
    public void bridge$forge$onCaughtFire(BlockState state, Level level, BlockPos pos, @Nullable Direction direction, @Nullable LivingEntity igniter) {
        this.onCaughtFire(state, level, pos, direction, igniter);
    }
}
