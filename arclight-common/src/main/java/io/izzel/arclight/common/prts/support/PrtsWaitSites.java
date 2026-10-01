/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The seam a wait call site reports through. The site opens an observation at the head of its
 * method and closes it at the return; the seam only measures how long the call took and hands the
 * number to whoever watches. It never touches an upper bound, never shortens a wait and never
 * cancels one, and while nothing watches it costs a single volatile read per call.
 *
 * The wait sites are numbered here because this is the one class both sides compile against: the
 * hooks on the world side know only this seam, and the observation layer reads the same table, so
 * a site cannot be numbered one way on one side and named another way on the other. The list is
 * written down, not discovered, and the order is part of the contract.
 *
 * This lives beside the world write seam rather than next to the hooks, because a class in a mixin
 * package cannot be referenced at all.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.world.level.Level;

/**
 * The seam a wait call site opens and closes an observation through.
 *
 * <p>A site reports with {@link #begin(int)} at the head of its method and {@link #end(int)} at the
 * return. Both are no-ops while no watcher is installed, so a build that does not observe pays two
 * volatile reads per call and nothing else. The start stamp is kept per site and per thread, so a
 * call nested inside another site, or the same site re-entered, cannot be charged to the wrong
 * wait.</p>
 *
 * <p>The seam carries no kernel type: it sits below both the call sites and the watcher, so neither
 * side depends on the other to be compiled.</p>
 */
public final class PrtsWaitSites {

    /** Receives one finished observation. */
    public interface SiteWaitTap {

        /**
         * Notes one wait a call site produced.
         *
         * @param siteIndex index of the site in {@link #SITE_IDS}
         * @param siteId    identity of that site
         * @param worldId   world the call ran in, or an empty string when the site has none at hand
         * @param waitNanos how long the call took, in nanoseconds
         */
        void observed(int siteIndex, String siteId, String worldId, long waitNanos);
    }

    /**
     * Identities of the observed call sites, indexed by the numbers below.
     *
     * <p>The order is the contract: index {@code n} is the site the hooks pass as {@code n}, and the
     * observation layer reads the identity from the same slot.</p>
     */
    public static final String[] SITE_IDS = {
        "entity_set_pos_raw",
        "block_collisions_compute_next",
        "natural_spawner_spawn_category",
        "multiface_spreader_spread_to_face",
        "end_gateway_find_teleport_pos",
        "end_gateway_is_chunk_empty",
        "end_dragon_fight_has_active_exit_portal",
        "end_dragon_fight_find_exit_portal",
        "end_dragon_fight_is_arena_loaded",
        "map_item_update",
        "poi_manager_ensure_loaded_and_valid",
        "structure_manager_starts_for_chunk_pos",
        "structure_manager_starts_for_section",
        "structure_manager_fill_starts",
        "structure_manager_has_any_structure_at",
        "structure_manager_get_all_structures_at",
        "player_respawn_logic_get_overworld_respawn_pos",
        "server_level_set_chunk_forced",
        "chunk_map_resend_biomes_for_chunks",
        "entity_adjust_spawn_location"
    };

    /** Index of the entity position write in {@link #SITE_IDS}. */
    public static final int ENTITY_SET_POS_RAW = 0;
    /** Index of the collision walk step in {@link #SITE_IDS}. */
    public static final int BLOCK_COLLISIONS_COMPUTE_NEXT = 1;
    /** Index of the mob spawning call in {@link #SITE_IDS}. */
    public static final int NATURAL_SPAWNER_SPAWN_CATEGORY = 2;
    /** Index of the block face spread in {@link #SITE_IDS}. */
    public static final int MULTIFACE_SPREADER_SPREAD_TO_FACE = 3;
    /** Index of the end gateway teleport search in {@link #SITE_IDS}. */
    public static final int END_GATEWAY_FIND_TELEPORT_POS = 4;
    /** Index of the end gateway chunk check in {@link #SITE_IDS}. */
    public static final int END_GATEWAY_IS_CHUNK_EMPTY = 5;
    /** Index of the exit portal presence check in {@link #SITE_IDS}. */
    public static final int END_DRAGON_FIGHT_HAS_ACTIVE_EXIT_PORTAL = 6;
    /** Index of the exit portal search in {@link #SITE_IDS}. */
    public static final int END_DRAGON_FIGHT_FIND_EXIT_PORTAL = 7;
    /** Index of the arena readiness check in {@link #SITE_IDS}. */
    public static final int END_DRAGON_FIGHT_IS_ARENA_LOADED = 8;
    /** Index of the held map redraw in {@link #SITE_IDS}. */
    public static final int MAP_ITEM_UPDATE = 9;
    /** Index of the point of interest scan in {@link #SITE_IDS}. */
    public static final int POI_MANAGER_ENSURE_LOADED_AND_VALID = 10;
    /** Index of the structure lookup by chunk in {@link #SITE_IDS}. */
    public static final int STRUCTURE_MANAGER_STARTS_FOR_CHUNK_POS = 11;
    /** Index of the structure lookup by section in {@link #SITE_IDS}. */
    public static final int STRUCTURE_MANAGER_STARTS_FOR_SECTION = 12;
    /** Index of the structure reference fill in {@link #SITE_IDS}. */
    public static final int STRUCTURE_MANAGER_FILL_STARTS = 13;
    /** Index of the structure presence check in {@link #SITE_IDS}. */
    public static final int STRUCTURE_MANAGER_HAS_ANY_STRUCTURE_AT = 14;
    /** Index of the structure map lookup in {@link #SITE_IDS}. */
    public static final int STRUCTURE_MANAGER_GET_ALL_STRUCTURES_AT = 15;
    /** Index of the overworld respawn search in {@link #SITE_IDS}. */
    public static final int PLAYER_RESPAWN_LOGIC_GET_OVERWORLD_RESPAWN_POS = 16;
    /** Index of the forced chunk command in {@link #SITE_IDS}. */
    public static final int SERVER_LEVEL_SET_CHUNK_FORCED = 17;
    /** Index of the biome resend in {@link #SITE_IDS}. */
    public static final int CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS = 18;
    /** Index of the spawn position adjust in {@link #SITE_IDS}. */
    public static final int ENTITY_ADJUST_SPAWN_LOCATION = 19;

    /** One start stamp per site and per thread; a slot of zero means nothing is open there. */
    private static final ThreadLocal<long[]> OPEN =
        ThreadLocal.withInitial(() -> new long[SITE_IDS.length]);

    private static volatile SiteWaitTap tap;

    private PrtsWaitSites() {
    }

    /**
     * Installs the watcher.
     *
     * @param watcher the watcher, or {@code null} to remove it
     */
    public static void install(SiteWaitTap watcher) {
        tap = watcher;
    }

    /** @return whether a watcher is installed */
    public static boolean installed() {
        return tap != null;
    }

    /**
     * Opens an observation for one site.
     *
     * @param siteIndex index of the site in {@link #SITE_IDS}
     */
    public static void begin(int siteIndex) {
        if (tap == null || siteIndex < 0 || siteIndex >= SITE_IDS.length) {
            return;
        }
        OPEN.get()[siteIndex] = System.nanoTime();
    }

    /**
     * Closes an observation for one site without naming the world.
     *
     * @param siteIndex index of the site in {@link #SITE_IDS}
     */
    public static void end(int siteIndex) {
        end(siteIndex, null);
    }

    /**
     * Closes an observation for one site.
     *
     * <p>A site that already holds a level hands it over so the reading carries the world; a site
     * that does not passes nothing and the reading states an empty world. Closing a site that was
     * never opened, or closing it twice, is a no-op: the injection at the return runs for every
     * return of the method, and the first one consumes the stamp.</p>
     *
     * @param siteIndex index of the site in {@link #SITE_IDS}
     * @param levelRef  the level the call ran in, or {@code null} when the site has none at hand
     */
    public static void end(int siteIndex, Object levelRef) {
        SiteWaitTap watcher = tap;
        if (watcher == null || siteIndex < 0 || siteIndex >= SITE_IDS.length) {
            return;
        }
        long[] open = OPEN.get();
        long startedAt = open[siteIndex];
        if (startedAt == 0L) {
            return;
        }
        open[siteIndex] = 0L;
        watcher.observed(siteIndex, SITE_IDS[siteIndex], worldIdOf(levelRef),
            System.nanoTime() - startedAt);
    }

    private static String worldIdOf(Object levelRef) {
        if (!(levelRef instanceof Level level)) {
            return "";
        }
        try {
            return level.dimension().location().toString();
        } catch (Throwable unreadable) {
            return "";
        }
    }
}
