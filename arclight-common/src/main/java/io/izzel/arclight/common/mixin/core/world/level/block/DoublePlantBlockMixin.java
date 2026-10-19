package io.izzel.arclight.common.mixin.core.world.level.block;

import org.bukkit.event.block.BlockPhysicsEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DoublePlantBlock.class)
public class DoublePlantBlockMixin {

    @Inject(method = "preventDropFromBottomPart", cancellable = true, at = @At("HEAD"))
    private static void arclight$blockPhysics(Level worldIn, BlockPos pos, BlockState state, Player player, CallbackInfo ci) {
        BlockPhysicsEvent physics = CraftEventFactory.callBlockPhysicsEvent(worldIn, pos);
        if (physics != null && physics.isCancelled()) {
            ci.cancel();
        }
    }
}
