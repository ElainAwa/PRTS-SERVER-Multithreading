/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The written-down list of the call-site seams the kernel needs, together with what the mixin plugin
 * decided about each of them. It lives beside the two seams rather than next to the kernel, because
 * the world side may not depend on the kernel: a mixin that carries the seam knows only this file,
 * and the kernel reads it the same way.
 *
 * The list is written down, not discovered. A seam the kernel needs but that is not in this list is a
 * seam nobody watches, so the list is a contract between the two sides and a mismatch is meant to be
 * visible in the readout instead of quiet.
 */
package io.izzel.arclight.common.prts.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The seams the kernel reaches the real call sites through, and whether they are actually there.
 *
 * <p>A kernel can be enabled, its watcher installed and its readout green while the bytecode that
 * would call the watcher was never applied, because the mixin that carries the call is gated by a
 * different switch than the kernel category. That is a false green: the readout reports a working
 * kernel over a call site that never reaches it. This class is what makes that state readable. Every
 * seam the kernel depends on is declared here with the category that gates it, the mixin plugin
 * records what it decided for that mixin and whether the mixin was applied, and a reader can compare
 * the two against the category switch.</p>
 *
 * <p>A seam whose category is off is reported as unreachable, and a kernel whose seams are not all
 * reachable must not be judged: the state carries that verdict instead of leaving it to a reader to
 * notice.</p>
 */
public final class PrtsSeams {

    /** Category that gates the correctness fixes and, with them, the write path and wait seams. */
    public static final String FIXES_CATEGORY = "fixes";

    /**
     * One seam of the kernel.
     *
     * @param seamId      readable identity of the seam
     * @param mixinClass  mixin that carries it
     * @param targetClass class the mixin is applied to
     * @param category    category switch the mixin is gated by
     */
    public record Seam(String seamId, String mixinClass, String targetClass, String category) {
    }

    /**
     * What is known about one seam at the moment it is read.
     *
     * @param seam            the declaration
     * @param categoryEnabled whether the gating category is on
     * @param decisionKnown   whether the mixin plugin was asked about this mixin
     * @param decidedToApply  what the plugin answered, meaningful only when {@code decisionKnown}
     * @param applied         whether the mixin was applied to its target
     */
    public record SeamState(Seam seam, boolean categoryEnabled, boolean decisionKnown,
                            boolean decidedToApply, boolean applied) {

        /** @return whether the call sites of this seam can reach the kernel at all */
        public boolean reachable() {
            return categoryEnabled && (!decisionKnown || decidedToApply);
        }
    }

    private static final List<Seam> KERNEL_SEAMS = List.of(
        new Seam("write_path_level",
            "io.izzel.arclight.common.prts.fixes.PrtsLevelStructureGrowCaptureMixin",
            "net.minecraft.world.level.Level", FIXES_CATEGORY),
        new Seam("write_path_platform",
            "io.izzel.arclight.common.prts.fixes.PrtsCraftBlockWriteTapMixin",
            "org.bukkit.craftbukkit.v.block.CraftBlock", FIXES_CATEGORY),
        waitSite("entity", "PrtsEntityWaitSiteMixin", "net.minecraft.world.entity.Entity"),
        waitSite("block_collisions", "PrtsBlockCollisionsWaitSiteMixin",
            "net.minecraft.world.level.BlockCollisions"),
        waitSite("natural_spawner", "PrtsNaturalSpawnerWaitSiteMixin",
            "net.minecraft.world.level.NaturalSpawner"),
        waitSite("multiface_spreader", "PrtsMultifaceSpreaderWaitSiteMixin",
            "net.minecraft.world.level.block.MultifaceSpreader"),
        waitSite("end_gateway", "PrtsEndGatewayWaitSitesMixin",
            "net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity"),
        waitSite("end_dragon_fight", "PrtsEndDragonFightWaitSitesMixin",
            "net.minecraft.world.level.dimension.end.EndDragonFight"),
        waitSite("map_item", "PrtsMapItemWaitSiteMixin", "net.minecraft.world.item.MapItem"),
        waitSite("poi_manager", "PrtsPoiManagerWaitSiteMixin",
            "net.minecraft.world.entity.ai.village.poi.PoiManager"),
        waitSite("structure_manager", "PrtsStructureManagerWaitSitesMixin",
            "net.minecraft.world.level.StructureManager"),
        waitSite("player_respawn_logic", "PrtsPlayerRespawnLogicWaitSiteMixin",
            "net.minecraft.world.level.PlayerRespawnLogic"),
        waitSite("server_level", "PrtsServerLevelWaitSiteMixin",
            "net.minecraft.server.level.ServerLevel"),
        waitSite("chunk_map", "PrtsChunkMapWaitSiteMixin", "net.minecraft.server.level.ChunkMap"));

    private static final Map<String, Boolean> DECISIONS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> APPLIED = new ConcurrentHashMap<>();

    private PrtsSeams() {
    }

    /** @return the seams the kernel depends on, in the order they are declared */
    public static List<Seam> kernelSeams() {
        return KERNEL_SEAMS;
    }

    /**
     * Records what the mixin plugin decided about one mixin.
     *
     * @param mixinClass the mixin the plugin was asked about
     * @param apply      the answer it gave
     */
    public static void noteDecision(String mixinClass, boolean apply) {
        if (mixinClass != null) {
            DECISIONS.put(mixinClass, apply);
        }
    }

    /**
     * Records that one mixin was applied to its target.
     *
     * @param mixinClass the mixin that was applied
     */
    public static void noteApplied(String mixinClass) {
        if (mixinClass != null) {
            APPLIED.put(mixinClass, Boolean.TRUE);
        }
    }

    /**
     * Renders the state of every declared seam.
     *
     * @param categoryEnabled resolves a category name to its switch
     * @return one state per declared seam, in declaration order
     */
    public static List<SeamState> states(Predicate<String> categoryEnabled) {
        List<SeamState> states = new ArrayList<>(KERNEL_SEAMS.size());
        for (Seam seam : KERNEL_SEAMS) {
            Boolean decision = DECISIONS.get(seam.mixinClass());
            states.add(new SeamState(seam, categoryEnabled.test(seam.category()),
                decision != null, decision != null && decision,
                APPLIED.containsKey(seam.mixinClass())));
        }
        return states;
    }

    /**
     * Returns the seams whose call sites cannot reach the kernel.
     *
     * @param categoryEnabled resolves a category name to its switch
     * @return the unreachable seams, in declaration order
     */
    public static List<SeamState> gaps(Predicate<String> categoryEnabled) {
        List<SeamState> gaps = new ArrayList<>();
        for (SeamState state : states(categoryEnabled)) {
            if (!state.reachable()) {
                gaps.add(state);
            }
        }
        return gaps;
    }

    /**
     * Groups the states by the category that gates them.
     *
     * @param categoryEnabled resolves a category name to its switch
     * @return category to its states, in declaration order
     */
    public static Map<String, List<SeamState>> byCategory(Predicate<String> categoryEnabled) {
        Map<String, List<SeamState>> grouped = new LinkedHashMap<>();
        for (SeamState state : states(categoryEnabled)) {
            grouped.computeIfAbsent(state.seam().category(), key -> new ArrayList<>()).add(state);
        }
        return grouped;
    }

    /** Forgets every recorded decision and application. Used by tests. */
    public static void clearRecords() {
        DECISIONS.clear();
        APPLIED.clear();
    }

    private static Seam waitSite(String seamId, String mixin, String target) {
        return new Seam("wait_site_" + seamId, "io.izzel.arclight.common.prts.fixes.waitpoints." + mixin,
            target, FIXES_CATEGORY);
    }
}
