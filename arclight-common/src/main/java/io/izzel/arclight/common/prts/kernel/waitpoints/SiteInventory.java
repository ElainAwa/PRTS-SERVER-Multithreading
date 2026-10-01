/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The explicit list of call sites a wait can happen at, and what covers each one.
 *
 * <p>The list is written down rather than discovered: a site appears here with the class, the
 * method, the part of the tick it runs in, the time-budget row its work belongs to, and its own
 * copy of the four elements. Registering one is the only way it can be counted as covered, so the
 * coverage report states a fact about a list instead of a guess about a jar.</p>
 *
 * <p>Sites seen at run time are noted separately. One that no row covers is listed, never refused:
 * this layer observes coverage, it does not block a wait for being undocumented.</p>
 */
public final class SiteInventory {

    /** One entry of the written-down list. */
    private record Row(String siteId, String wpId, String classRef, String methodRef,
                       String tickPhase, String shareClass, String producer, String signalField,
                       String timeoutAction, String degradeTo, String evidence) {
    }

    /**
     * The timeout action that a forced materialization convergence would carry out.
     *
     * <p>A site whose row names it is one where a wait over the configured bound would have entered
     * that action, had this build been allowed to run one. The constant is published so the reader
     * of the wait observations asks the same question the list answers.</p>
     */
    public static final String FORCED_MATERIALIZATION = "forced materialization convergence";

    private static final String CHUNK_SOURCE = "chunk materialization pipeline";
    private static final String CHUNK_PROGRESS = "progress.chunk.materialized_per_tick";
    private static final String CHUNK_TIMEOUT = FORCED_MATERIALIZATION;
    private static final String SNAPSHOT = "read-only snapshot or placeholder upgrade";

    private static final Row[] LIST = {
        new Row("entity_set_pos_raw", "chunk", "net.minecraft.world.entity.Entity", "setPosRaw",
            "entity_tick", "entity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, entity tick"),
        new Row("block_collisions_compute_next", "chunk", "net.minecraft.world.level.BlockCollisions",
            "computeNext", "entity_tick", "entity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT,
            SNAPSHOT, "site census, blocking bucket, collision walk"),
        new Row("natural_spawner_spawn_category", "chunk",
            "net.minecraft.world.level.NaturalSpawner", "spawnCategoryForPosition", "spawn", "ai",
            CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, mob spawning"),
        new Row("multiface_spreader_spread_to_face", "chunk",
            "net.minecraft.world.level.block.MultifaceSpreader", "spreadToFace", "block_tick",
            "regiontick", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, block spread"),
        new Row("end_gateway_find_teleport_pos", "chunk",
            "net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity",
            "findOrCreateValidTeleportPos", "blockentity_tick", "blockentity", CHUNK_SOURCE,
            CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, gateway teleport search"),
        new Row("end_gateway_is_chunk_empty", "chunk",
            "net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity", "isChunkEmpty",
            "blockentity_tick", "blockentity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, gateway exit search"),
        new Row("end_dragon_fight_has_active_exit_portal", "chunk",
            "net.minecraft.world.level.dimension.end.EndDragonFight", "hasActiveExitPortal",
            "entity_tick", "entity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, exit portal check"),
        new Row("end_dragon_fight_find_exit_portal", "chunk",
            "net.minecraft.world.level.dimension.end.EndDragonFight", "findExitPortal", "entity_tick",
            "entity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, exit portal pattern"),
        new Row("end_dragon_fight_is_arena_loaded", "chunk",
            "net.minecraft.world.level.dimension.end.EndDragonFight", "isArenaLoaded", "entity_tick",
            "entity", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, arena readiness"),
        new Row("map_item_update", "chunk", "net.minecraft.world.item.MapItem", "update",
            "map_update", "other", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT,
            "skip this update and retry on the next tick",
            "site census, blocking bucket, held map redraw"),
        new Row("poi_manager_ensure_loaded_and_valid", "chunk",
            "net.minecraft.world.entity.ai.village.poi.PoiManager", "ensureLoadedAndValid",
            "poi_update", "ai", "point of interest storage", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, point of interest scan"),
        new Row("structure_manager_starts_for_chunk_pos", "chunk",
            "net.minecraft.world.level.StructureManager", "startsForStructure", "structure_query",
            "graph", "structure index", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, structure starts by chunk"),
        new Row("structure_manager_starts_for_section", "chunk",
            "net.minecraft.world.level.StructureManager", "startsForStructure", "structure_query",
            "graph", "structure index", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, structure starts by section"),
        new Row("structure_manager_fill_starts", "chunk",
            "net.minecraft.world.level.StructureManager", "fillStartsForStructure",
            "structure_query", "graph", "structure index", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, structure reference fill"),
        new Row("structure_manager_has_any_structure_at", "chunk",
            "net.minecraft.world.level.StructureManager", "hasAnyStructureAt", "structure_query",
            "graph", "structure index", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, structure presence"),
        new Row("structure_manager_get_all_structures_at", "chunk",
            "net.minecraft.world.level.StructureManager", "getAllStructuresAt", "structure_query",
            "graph", "structure index", CHUNK_PROGRESS, CHUNK_TIMEOUT, SNAPSHOT,
            "site census, blocking bucket, structure lookup"),
        new Row("player_respawn_logic_get_overworld_respawn_pos", "chunk",
            "net.minecraft.server.level.PlayerRespawnLogic", "getOverworldRespawnPos", "respawn",
            "other", CHUNK_SOURCE, CHUNK_PROGRESS, CHUNK_TIMEOUT,
            "fall back to the world spawn position",
            "site census, blocking bucket, respawn position search"),
        new Row("server_level_set_chunk_forced", "chunk", "net.minecraft.server.level.ServerLevel",
            "setChunkForced", "command", "worldres", "chunk ticket manager", CHUNK_PROGRESS,
            CHUNK_TIMEOUT, "return the previous ticket state and count",
            "site census, blocking bucket, forced chunk command"),
        new Row("chunk_map_resend_biomes_for_chunks", "chunk", "net.minecraft.server.level.ChunkMap",
            "resendBiomesForChunks", "chunk_send", "other", "chunk send pipeline", CHUNK_PROGRESS,
            CHUNK_TIMEOUT, "defer to the next tick in order",
            "site census, blocking bucket, biome resend"),
        new Row("entity_adjust_spawn_location", "chunk", "net.minecraft.world.entity.Entity",
            "adjustSpawnLocation", "entity_join", "entity", CHUNK_SOURCE, CHUNK_PROGRESS,
            CHUNK_TIMEOUT, "keep the current position",
            "site census, blocking bucket, spawn position adjust")
    };

    private final Map<String, WaitSite> sites = new LinkedHashMap<>();
    private final Map<String, LongAdder> observed = new ConcurrentHashMap<>();

    /** Creates the inventory with its written-down list already registered. */
    public SiteInventory() {
        for (Row row : LIST) {
            register(rowOf(row));
        }
    }

    /**
     * Registers one call site.
     *
     * @param site the site
     * @return the stored site, a duplicate refusal, or the element that is missing
     */
    public synchronized SiteRegisterResult register(WaitSite site) {
        if (site == null || site.siteId() == null || site.siteId().isBlank()) {
            return new SiteRegisterResult.MissingElement(
                site == null ? "" : String.valueOf(site.siteId()), "identity");
        }
        if (sites.containsKey(site.siteId())) {
            return new SiteRegisterResult.DuplicateSiteId(site.siteId());
        }
        String missing = site.missingElement();
        if (missing != null) {
            return new SiteRegisterResult.MissingElement(site.siteId(), missing);
        }
        sites.put(site.siteId(), site);
        return new SiteRegisterResult.Ok(site);
    }

    /**
     * Notes a call site seen at run time.
     *
     * @param siteId identity of the site
     */
    public void noteObserved(String siteId) {
        if (siteId != null && !siteId.isBlank()) {
            observed.computeIfAbsent(siteId, ignored -> new LongAdder()).increment();
        }
    }

    /**
     * Looks a site up.
     *
     * @param siteId identity of the site
     * @return the site, or {@code null}
     */
    public synchronized WaitSite lookup(String siteId) {
        return siteId == null ? null : sites.get(siteId);
    }

    /** @return the registered sites, in the order they were written down */
    public synchronized List<WaitSite> sites() {
        return List.copyOf(sites.values());
    }

    /** @return sites that are registered but miss one of the four elements; always empty here */
    public synchronized List<String> pendingElements() {
        List<String> pending = new ArrayList<>();
        for (WaitSite site : sites.values()) {
            if (!site.complete()) {
                pending.add(site.siteId());
            }
        }
        return pending;
    }

    /** @return call sites the list stands for */
    public synchronized int callSites() {
        int total = 0;
        for (WaitSite site : sites.values()) {
            total += site.callSites();
        }
        return total;
    }

    /** @return call sites seen at run time that the list does not cover */
    public synchronized List<String> observedWithoutRow() {
        List<String> unknown = new ArrayList<>();
        for (String siteId : observed.keySet()) {
            if (!sites.containsKey(siteId)) {
                unknown.add(siteId);
            }
        }
        unknown.sort(String::compareTo);
        return unknown;
    }

    /** @return identities of the sites seen at run time */
    public synchronized List<String> observedIds() {
        List<String> ids = new ArrayList<>(observed.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    private static WaitSite rowOf(Row row) {
        return new WaitSite(row.siteId(), row.wpId(), row.classRef(), row.methodRef(),
            row.tickPhase(), row.shareClass(), row.producer(),
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT, row.signalField()),
            row.timeoutAction(), row.degradeTo(), row.evidence(), 1);
    }
}
