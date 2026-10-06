/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The written-down list of the call-site seams the kernel needs, with what the mixin plugin decided
 * about each; it lives beside the seams because the world side may not depend on the kernel, and a
 * mismatch is meant to be visible in the readout instead of quiet. */
package io.izzel.arclight.common.prts.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Every seam the kernel reaches a real call site through, with whether it is there: a kernel can read
 * green while the mixin carrying its call was gated off, so unreachable seams must not be judged.
 */
public final class PrtsSeams {

    public static final String FIXES_CATEGORY = "fixes";

    /** One declared seam: readable identity, carrying mixin, target class and gating category. */
    public record Seam(String seamId, String mixinClass, String targetClass, String category) {
    }

    /** What is known about one seam when it is read; {@link #reachable()} carries the verdict. */
    public record SeamState(Seam seam, boolean categoryEnabled, boolean decisionKnown,
                            boolean decidedToApply, boolean applied) {

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
        waitSite("chunk_map", "PrtsChunkMapWaitSiteMixin", "net.minecraft.server.level.ChunkMap"),
        new Seam("pipeline_mailbox",
            "io.izzel.arclight.common.prts.fixes.pipeline.PrtsPipelineMailboxMixin",
            "net.minecraft.util.thread.ProcessorMailbox", FIXES_CATEGORY),
        new Seam("chunk_flow",
            "io.izzel.arclight.common.prts.fixes.pipeline.PrtsChunkFlowMailboxMixin",
            "net.minecraft.util.thread.ProcessorMailbox", FIXES_CATEGORY),
        new Seam("entity_self_cost",
            "io.izzel.arclight.common.prts.fixes.observation.PrtsEntitySelfCostMixin",
            "net.minecraft.server.level.ServerLevel", FIXES_CATEGORY),
        new Seam("block_entity_self_cost",
            "io.izzel.arclight.common.prts.fixes.observation.PrtsBlockEntitySelfCostMixin",
            "net.minecraft.world.level.Level", FIXES_CATEGORY),
        new Seam("block_entity_cost",
            "io.izzel.arclight.common.prts.fixes.observation.PrtsBlockEntityTickCostMixin",
            "net.minecraft.world.level.Level", FIXES_CATEGORY),
        new Seam("entity_cost",
            "io.izzel.arclight.common.prts.fixes.observation.PrtsEntitySelfCostMixin",
            "net.minecraft.server.level.ServerLevel", FIXES_CATEGORY),
        new Seam("chunk_demand",
            "io.izzel.arclight.common.prts.fixes.pipeline.PrtsChunkDemandMixin",
            "net.minecraft.server.level.ServerChunkCache", FIXES_CATEGORY),
        new Seam("chunk_materialization",
            "io.izzel.arclight.common.prts.fixes.pipeline.PrtsChunkMaterializationMixin",
            "net.minecraft.world.level.chunk.status.ChunkStatusTasks", FIXES_CATEGORY));

    private static final Map<String, Boolean> DECISIONS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> APPLIED = new ConcurrentHashMap<>();

    private PrtsSeams() {
    }

    /** @return the seams the kernel depends on, in declaration order */
    public static List<Seam> kernelSeams() {
        return KERNEL_SEAMS;
    }

    public static void noteDecision(String mixinClass, boolean apply) {
        if (mixinClass != null) {
            DECISIONS.put(mixinClass, apply);
        }
    }

    public static void noteApplied(String mixinClass) {
        if (mixinClass != null) {
            APPLIED.put(mixinClass, Boolean.TRUE);
        }
    }

    /** @return one state per declared seam, in declaration order */
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

    /** @return the seams whose call sites cannot reach the kernel, in declaration order */
    public static List<SeamState> gaps(Predicate<String> categoryEnabled) {
        List<SeamState> gaps = new ArrayList<>();
        for (SeamState state : states(categoryEnabled)) {
            if (!state.reachable()) {
                gaps.add(state);
            }
        }
        return gaps;
    }

    /** @return the states grouped by gating category, in declaration order */
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
