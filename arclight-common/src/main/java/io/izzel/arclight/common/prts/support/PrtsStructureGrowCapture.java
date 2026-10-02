/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Answers the block writes of a growing tree from a capture list instead of the chunk and raises
 * StructureGrowEvent with that list once the generator is done: a cancelled event leaves the world
 * untouched, so no rollback is needed. One scope on the server thread, the only writer of a level.
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
import java.util.function.BooleanSupplier;

/**
 * Dispatches StructureGrowEvent for a tree a sapling grows, with the Bukkit species the grower resolved.
 */
public final class PrtsStructureGrowCapture {

    // Bukkit names a tree by the configured feature the grower resolved (a 2x2 spruce is a mega redwood).
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
     * @return what the wrapped grow call returned, so the caller keeps its own behaviour
     */
    public static boolean growWithEvent(TreeGrower grower, ServerLevel level, ChunkGenerator generator,
                                        BlockPos pos, BlockState state, RandomSource random) {
        return growWithEvent(level, pos, null, () -> grower.growTree(level, generator, pos, state, random));
    }

    /**
     * Grows the tree the given call builds with the writes captured.
     * @param species the Bukkit species, or null to take the feature the grower resolved
     * @return what the call returned, so the caller keeps its own behaviour
     */
    public static boolean growWithEvent(ServerLevel level, BlockPos pos, TreeType species, BooleanSupplier grow) {
        List<org.bukkit.block.BlockState> blocks;
        boolean grown = false;
        begin(level);
        try {
            grown = grow.getAsBoolean();
        } finally {
            blocks = new ArrayList<>(CAPTURED_BLOCKS.values());
            if (species == null) {
                species = resolveSpecies();
            }
            reset();
        }
        if (grown && !blocks.isEmpty()) {
            // Only a grown tree has blocks worth an event: a failed feature puts back what it removed.
            dispatch(level, pos, species, blocks);
        }
        return grown;
    }

    /** @return true when the write was captured and must not reach the chunk */
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
     * The state a captured position reads back as while a tree is built, so a generator that clears the
     * sapling and asks whether the spot is free sees its own write.
     * @return the captured state, or null when the growing tree did not write this position
     */
    public static net.minecraft.world.level.block.state.BlockState capturedState(Level target, BlockPos pos) {
        if (capturedLevel == null || capturedLevel != target) {
            return null;
        }
        CapturedBlockState captured = CAPTURED_BLOCKS.get(pos);
        return captured == null ? null : captured.getHandle();
    }

    /** Records the feature the grower resolved, which is the species of the tree. */
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
            // Keep the tree and say so: an unknown species is a gap in the table, not a reason to drop it.
            ArclightServer.LOGGER.debug("No TreeType for configured feature {}, growing without an event", path);
        }
        return species;
    }

    private static void dispatch(ServerLevel level, BlockPos pos, TreeType species,
                                 List<org.bukkit.block.BlockState> blocks) {
        if (species != null) {
            // CraftBukkit raises this event without a player and without the bone meal flag on this path.
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
