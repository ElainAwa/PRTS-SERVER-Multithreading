/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The blocks a tree is made of are written by the world generator, so there is no single call a
 * listener could sit in front of: by the time the generator returns, the tree is already in the
 * chunk. CraftBukkit solves this by answering the block writes themselves while a sapling grows -
 * they go into a capture list instead of the chunk - and by raising StructureGrowEvent with that
 * list once the generator is done. The list is written to the chunk only when the event was not
 * cancelled, so a cancelled event leaves the world exactly as it was and no rollback is needed.
 *
 * The capture is one scope on the server thread: begin() before TreeGrower#growTree, the writes
 * arrive in capture(), and growWithEvent() raises the event and either applies or drops the list.
 * A tree grow never starts another one and no other thread may write a level, so the slots below
 * have exactly one owner - the server thread, between those two calls - and no synchronisation.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.bukkit.Bukkit;
import org.bukkit.TreeType;
import org.bukkit.craftbukkit.v.block.CapturedBlockState;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches {@link StructureGrowEvent} for a tree that a sapling grows.
 *
 * <p>Only the platform that places a tree through {@link TreeGrower} has a caller worth wrapping,
 * and the Bukkit species of the tree is the one the grower resolved, so both are provided by the
 * mixins in this package.</p>
 */
public final class PrtsStructureGrowCapture {

    /**
     * Bukkit names a tree by the configured feature the grower resolved, and the tree it grows
     * differs per feature (a 2x2 spruce is a mega redwood), so the feature path is the key here.
     */
    private static final Map<String, TreeType> SPECIES = Map.ofEntries(
        Map.entry("oak", TreeType.TREE),
        Map.entry("oak_bees_0002", TreeType.TREE),
        Map.entry("oak_bees_002", TreeType.TREE),
        Map.entry("oak_bees_005", TreeType.TREE),
        Map.entry("fancy_oak", TreeType.BIG_TREE),
        Map.entry("fancy_oak_bees", TreeType.BIG_TREE),
        Map.entry("fancy_oak_bees_0002", TreeType.BIG_TREE),
        Map.entry("fancy_oak_bees_002", TreeType.BIG_TREE),
        Map.entry("fancy_oak_bees_005", TreeType.BIG_TREE),
        Map.entry("birch", TreeType.BIRCH),
        Map.entry("birch_bees_0002", TreeType.BIRCH),
        Map.entry("birch_bees_002", TreeType.BIRCH),
        Map.entry("birch_bees_005", TreeType.BIRCH),
        Map.entry("super_birch_bees", TreeType.TALL_BIRCH),
        Map.entry("super_birch_bees_0002", TreeType.TALL_BIRCH),
        Map.entry("spruce", TreeType.REDWOOD),
        Map.entry("pine", TreeType.TALL_REDWOOD),
        Map.entry("mega_spruce", TreeType.MEGA_REDWOOD),
        Map.entry("mega_pine", TreeType.MEGA_PINE),
        Map.entry("jungle_tree", TreeType.COCOA_TREE),
        Map.entry("jungle_tree_no_vine", TreeType.SMALL_JUNGLE),
        Map.entry("mega_jungle_tree", TreeType.JUNGLE),
        Map.entry("jungle_bush", TreeType.JUNGLE_BUSH),
        Map.entry("acacia", TreeType.ACACIA),
        Map.entry("dark_oak", TreeType.DARK_OAK),
        Map.entry("swamp_oak", TreeType.SWAMP),
        Map.entry("azalea_tree", TreeType.AZALEA),
        Map.entry("mangrove", TreeType.MANGROVE),
        Map.entry("tall_mangrove", TreeType.TALL_MANGROVE),
        Map.entry("cherry", TreeType.CHERRY),
        Map.entry("cherry_bees_005", TreeType.CHERRY));

    private static ServerLevel capturedLevel;
    private static ResourceKey<ConfiguredFeature<?, ?>> capturedSpecies;
    private static final Map<BlockPos, CapturedBlockState> CAPTURED_BLOCKS = new LinkedHashMap<>();

    private PrtsStructureGrowCapture() {
    }

    /**
     * Grows the tree with the block writes captured and raises the event for them.
     *
     * @return what {@link TreeGrower#growTree} returned, so the caller keeps its own behaviour
     */
    public static boolean growWithEvent(TreeGrower grower, ServerLevel level, ChunkGenerator generator,
                                        BlockPos pos, BlockState state, RandomSource random) {
        List<org.bukkit.block.BlockState> blocks;
        TreeType species;
        boolean grown = false;
        begin(level);
        try {
            grown = grower.growTree(level, generator, pos, state, random);
        } finally {
            blocks = new ArrayList<>(CAPTURED_BLOCKS.values());
            species = resolveSpecies();
            reset();
        }
        if (!blocks.isEmpty()) {
            dispatch(level, pos, species, blocks);
        }
        return grown;
    }

    /**
     * Answers a block write made while a tree grows.
     *
     * @return true when the write was captured and must not reach the chunk
     */
    public static boolean capture(Level target, BlockPos pos, BlockState state, int flags) {
        if (capturedLevel == null || capturedLevel != target) {
            return false;
        }
        CapturedBlockState captured = CAPTURED_BLOCKS.get(pos);
        if (captured == null) {
            captured = CapturedBlockState.getTreeBlockState(target, pos, flags);
            CAPTURED_BLOCKS.put(pos.immutable(), captured);
        }
        captured.setData(state);
        captured.setFlag(flags);
        return true;
    }

    /**
     * The state a captured position reads back as while a tree is being built. A generator that
     * clears the sapling and then asks whether the spot is free has to see its own write, exactly
     * as it would in the chunk.
     *
     * @return the captured state, or null when this position was not written by the growing tree
     */
    public static net.minecraft.world.level.block.state.BlockState capturedState(Level target, BlockPos pos) {
        if (capturedLevel == null || capturedLevel != target) {
            return null;
        }
        CapturedBlockState captured = CAPTURED_BLOCKS.get(pos);
        return captured == null ? null : captured.getHandle();
    }

    /**
     * Records the feature {@link TreeGrower#growTree} resolved, which is the species of the tree.
     */
    public static void recordSpecies(ResourceKey<ConfiguredFeature<?, ?>> species) {
        if (capturedLevel != null) {
            capturedSpecies = species;
        }
    }

    private static void begin(ServerLevel level) {
        capturedLevel = level;
        capturedSpecies = null;
        CAPTURED_BLOCKS.clear();
    }

    private static void reset() {
        capturedLevel = null;
        capturedSpecies = null;
        CAPTURED_BLOCKS.clear();
    }

    private static TreeType resolveSpecies() {
        if (capturedSpecies == null) {
            return null;
        }
        String path = capturedSpecies.location().getPath();
        TreeType species = SPECIES.get(path);
        if (species == null) {
            // Keep the tree, but say so: an unknown species is a gap in the table above, not a
            // reason to drop what the generator already built.
            ArclightServer.LOGGER.debug("No TreeType for configured feature {}, growing without an event", path);
        }
        return species;
    }

    private static void dispatch(ServerLevel level, BlockPos pos, TreeType species,
                                 List<org.bukkit.block.BlockState> blocks) {
        if (species != null) {
            // A sapling cannot tell who asked it to grow, which is why CraftBukkit raises this
            // event without a player and without the bone meal flag on this path.
            StructureGrowEvent event = new StructureGrowEvent(CraftBlock.at(level, pos).getLocation(),
                species, false, null, blocks);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                // The capture is the cancel: nothing reached the chunk while the tree was built.
                return;
            }
        }
        // The list belongs to the event, so a listener that removed a block keeps it out.
        for (org.bukkit.block.BlockState state : blocks) {
            CapturedBlockState.setBlockState(state);
        }
    }
}
