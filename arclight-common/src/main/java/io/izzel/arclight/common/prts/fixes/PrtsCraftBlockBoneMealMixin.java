/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The CraftBukkit bone meal body writes Level#captureTreeGeneration, calls BoneMealItem#applyBonemeal
 * and reads SaplingBlock#treeType, none of which exists here, so it throws on the first field write.
 * The platform's own bone meal entry point is used instead; its tree still raises StructureGrowEvent.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LevelEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(value = CraftBlock.class, remap = false)
public abstract class PrtsCraftBlockBoneMealMixin {

    // @formatter:off
    @Shadow public abstract BlockPos getPosition();
    @Shadow public abstract CraftWorld getCraftWorld();
    // @formatter:on

    /** @author PRTS @reason the CraftBukkit body calls three members this platform does not have */
    @Overwrite(remap = false)
    public boolean applyBoneMeal(BlockFace face) {
        Direction direction = CraftBlock.blockFaceToNotch(face);
        ServerLevel level = getCraftWorld().getHandle();
        BlockPos pos = getPosition();
        ItemStack stack = Items.BONE_MEAL.getDefaultInstance();
        // The item's own useOn cannot stand in: it reports the interaction to the player that used it,
        // and Bukkit's contract here is a call without one.
        boolean applied = BoneMealItem.growCrop(stack, level, pos);
        if (!applied) {
            applied = BoneMealItem.growWaterPlant(stack, level, pos.relative(direction), direction);
        }
        if (applied) {
            level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 15);
        }
        return applied;
    }
}
