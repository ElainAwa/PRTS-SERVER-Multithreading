/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Block#applyBoneMeal is answered by the CraftBukkit class this server ships, and that class was
 * written against a server that carries the Spigot patches: it writes Level#captureTreeGeneration,
 * calls BoneMealItem#applyBonemeal and reads SaplingBlock#treeType, and none of the three exists
 * here, so the very first field write throws NoSuchFieldError and no bone meal is applied at all.
 * The platform's own bone meal entry point is used instead - the one a player's item use runs
 * through - and the tree it grows raises StructureGrowEvent on the path this category installs, so
 * dropping the Spigot capture loses nothing.
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

    /**
     * @author PRTS
     * @reason the CraftBukkit body calls three members this platform does not have
     */
    @Overwrite(remap = false)
    public boolean applyBoneMeal(BlockFace face) {
        Direction direction = CraftBlock.blockFaceToNotch(face);
        ServerLevel level = getCraftWorld().getHandle();
        BlockPos pos = getPosition();
        ItemStack stack = Items.BONE_MEAL.getDefaultInstance();
        // The item's own useOn cannot stand in for this: it reports the interaction to the player
        // that used it, and Bukkit's contract here is a call without one.
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
