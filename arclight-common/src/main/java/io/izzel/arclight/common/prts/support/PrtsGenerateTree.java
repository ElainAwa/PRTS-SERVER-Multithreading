/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * World#generateTree is how a plugin asks for a tree to be placed on demand, and the two overloads
 * this server ships were written against the Spigot shapes: the one that takes a
 * BlockChangeDelegate drives ServerLevel#captureTreeGeneration and #capturedBlockStates, and the
 * plain one grows the tree straight into the chunk. Neither of them can be answered here - the
 * capture members do not exist on this platform - and the plain one gives a listener no say at all,
 * because the tree is already in the world by the time the call returns.
 *
 * The tree is therefore built into BlockStateListPopulator, the buffer CraftBukkit itself uses for
 * its filtered generateTree overload: reads answer with the writes made so far, and the chunk stays
 * untouched. StructureGrowEvent then decides what happens to the list - a cancelled event drops it
 * (nothing reached the chunk, so there is nothing to roll back), otherwise the list is either
 * applied to the world with the same update call that overload uses, or handed to the caller's
 * BlockChangeDelegate one block at a time.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.craftbukkit.v.CraftRegionAccessor;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.bukkit.craftbukkit.v.block.CraftBlockState;
import org.bukkit.craftbukkit.v.util.BlockStateListPopulator;
import org.bukkit.craftbukkit.v.util.CraftLocation;
import org.bukkit.craftbukkit.v.util.RandomSourceWrapper;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grows the tree {@link org.bukkit.World#generateTree} asks for and lets {@link StructureGrowEvent}
 * decide whether it is kept.
 *
 * <p>The tree is built before the world is touched, so a listener can still stop it, and the list
 * the event carries is the list that is applied afterwards - a listener that removes a block from
 * it keeps that block out of the world.</p>
 */
public final class PrtsGenerateTree {

    private PrtsGenerateTree() {
    }

    /**
     * Grows one tree and either applies it to the world or hands it to the given delegate.
     *
     * @param delegate where the blocks go, or null to write them into the world
     * @return true when the tree was grown and not cancelled
     */
    public static boolean generateWithEvent(CraftWorld world, Location location, TreeType species,
                                            BlockChangeDelegate delegate, Random random) {
        ServerLevel level = world.getHandle();
        BlockStateListPopulator populator = new BlockStateListPopulator(level);
        BlockPos pos = CraftLocation.toBlockPosition(location);
        // The tree type is mapped to the configured feature by the region accessor this call lands
        // in, so the table of tree types lives in one place and this path cannot drift from it.
        boolean grown = ((CraftRegionAccessor) world).generateTree(populator, level.getChunkSource().getGenerator(),
            pos, new RandomSourceWrapper(random), species);
        populator.refreshTiles();
        List<org.bukkit.block.BlockState> blocks = new ArrayList<>(populator.getList());
        if (grown && !blocks.isEmpty()) {
            // A tree placed through the API has no player behind it and no bone meal of its own,
            // the same two values CraftBukkit hardcodes where a sapling grows by itself.
            StructureGrowEvent event = new StructureGrowEvent(location, species, false, null, blocks);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                // The capture is the cancel: the tree was built into the buffer, never into a chunk.
                return false;
            }
        }
        if (delegate == null) {
            for (org.bukkit.block.BlockState state : blocks) {
                state.update(true, true);
            }
        } else {
            for (org.bukkit.block.BlockState state : blocks) {
                handToDelegate(level, delegate, (CraftBlockState) state);
            }
        }
        return grown;
    }

    /**
     * Offers one block of the tree to the delegate and notifies the world about what the delegate
     * did with it.
     *
     * <p>The CraftBukkit shape re-reads the world after every delegate call and notifies physics
     * for the position; the member it used for that does not exist on this platform, so the two
     * calls that member is made of are used instead, and only when the delegate actually wrote
     * something - a delegate that only records blocks never touches the world.</p>
     */
    private static void handToDelegate(ServerLevel level, BlockChangeDelegate delegate, CraftBlockState state) {
        BlockPos pos = state.getPosition();
        int flags = state.getFlag();
        BlockState before = level.getBlockState(pos);
        delegate.setBlockData(pos.getX(), pos.getY(), pos.getZ(), state.getBlockData());
        BlockState after = level.getBlockState(pos);
        if (after != before) {
            level.updateNeighborsAt(pos, after.getBlock());
            if ((flags & Block.UPDATE_CLIENTS) != 0) {
                level.sendBlockUpdated(pos, before, after, flags);
            }
        }
    }
}
