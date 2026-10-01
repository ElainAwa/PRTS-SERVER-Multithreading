/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport.paper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the menu provider lookup of a block to the interop layer.
 *
 * <p>The lookup is protected, so the Paper inventory methods reach it through this invoker instead
 * of widening the access of the platform class.</p>
 */
@Mixin(BlockBehaviour.class)
public interface PrtsBlockMenuProviderInvoker {

    /**
     * Returns the menu a player gets when using this block.
     *
     * @param state  block state at the position
     * @param level  level holding the block
     * @param pos    position of the block
     * @return the menu provider, or {@code null} when the block opens no menu
     */
    @Invoker("getMenuProvider")
    MenuProvider prts$getMenuProvider(BlockState state, Level level, BlockPos pos);
}
