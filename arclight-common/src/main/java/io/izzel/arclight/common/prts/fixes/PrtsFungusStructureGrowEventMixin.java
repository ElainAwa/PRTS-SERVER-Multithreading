/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * A bone-mealed fungus grows a huge fungus through the same one ConfiguredFeature#place call a
 * mushroom uses, but the call sits in a lambda that FungusBlock#performBonemeal hands to
 * Optional#ifPresent. The event is raised here around that call, which is the only instruction in
 * the method where the growth can be wrapped; the core injector sits at the head of the method and
 * reads the same block for the species, so the two never share an instruction.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.bukkit.TreeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Consumer;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(FungusBlock.class)
public abstract class PrtsFungusStructureGrowEventMixin {

    @Redirect(method = "performBonemeal", at = @At(value = "INVOKE",
        target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private void prts$growWithEvent(Optional<Holder<ConfiguredFeature<?, ?>>> optional,
                                    Consumer<Holder<ConfiguredFeature<?, ?>>> consumer,
                                    ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        PrtsStructureGrowCapture.growWithEvent(level, pos, prts$species(), () -> {
            optional.ifPresent(consumer);
            return true;
        });
    }

    /**
     * The species of the fungus, which is the block itself - a modded fungus block is not one of
     * the two and grows without an event, as it does today.
     */
    @SuppressWarnings("ConstantConditions")
    private TreeType prts$species() {
        if ((Object) this == Blocks.WARPED_FUNGUS) {
            return TreeType.WARPED_FUNGUS;
        }
        if ((Object) this == Blocks.CRIMSON_FUNGUS) {
            return TreeType.CRIMSON_FUNGUS;
        }
        return null;
    }
}
