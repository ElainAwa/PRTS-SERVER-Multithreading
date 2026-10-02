/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The seam a wait call site reports through: a site opens an observation at the head of its method
 * and closes it at the return; the numbering below is shared with the mixin side and is the contract.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.world.level.Level;

/**
 * The seam a wait call site opens and closes an observation through. Both calls are no-ops while no
 * watcher is installed; the start stamp is kept per site and per thread, and no kernel type crosses it.
 */
public final class PrtsWaitSites {

    public interface SiteWaitTap {

        /** @param siteIndex index in {@link #SITE_IDS}; @param waitNanos how long the call took, in nanoseconds */
        void observed(int siteIndex, String siteId, String worldId, long waitNanos);
    }

    /** Identities of the observed call sites, indexed by the constants below; index {@code n} is the
     * site the hooks pass as {@code n}, so the order is the contract. */
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

    public static final int ENTITY_SET_POS_RAW = 0;
    public static final int BLOCK_COLLISIONS_COMPUTE_NEXT = 1;
    public static final int NATURAL_SPAWNER_SPAWN_CATEGORY = 2;
    public static final int MULTIFACE_SPREADER_SPREAD_TO_FACE = 3;
    public static final int END_GATEWAY_FIND_TELEPORT_POS = 4;
    public static final int END_GATEWAY_IS_CHUNK_EMPTY = 5;
    public static final int END_DRAGON_FIGHT_HAS_ACTIVE_EXIT_PORTAL = 6;
    public static final int END_DRAGON_FIGHT_FIND_EXIT_PORTAL = 7;
    public static final int END_DRAGON_FIGHT_IS_ARENA_LOADED = 8;
    public static final int MAP_ITEM_UPDATE = 9;
    public static final int POI_MANAGER_ENSURE_LOADED_AND_VALID = 10;
    public static final int STRUCTURE_MANAGER_STARTS_FOR_CHUNK_POS = 11;
    public static final int STRUCTURE_MANAGER_STARTS_FOR_SECTION = 12;
    public static final int STRUCTURE_MANAGER_FILL_STARTS = 13;
    public static final int STRUCTURE_MANAGER_HAS_ANY_STRUCTURE_AT = 14;
    public static final int STRUCTURE_MANAGER_GET_ALL_STRUCTURES_AT = 15;
    public static final int PLAYER_RESPAWN_LOGIC_GET_OVERWORLD_RESPAWN_POS = 16;
    public static final int SERVER_LEVEL_SET_CHUNK_FORCED = 17;
    public static final int CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS = 18;
    public static final int ENTITY_ADJUST_SPAWN_LOCATION = 19;

    /** One start stamp per site and per thread; a slot of zero means nothing is open there. */
    private static final ThreadLocal<long[]> OPEN =
        ThreadLocal.withInitial(() -> new long[SITE_IDS.length]);

    private static volatile SiteWaitTap tap;

    private PrtsWaitSites() {
    }

    public static void install(SiteWaitTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    /** @return the installed watcher, or null when none is installed; a tool that borrows the seam
     *     reads it to hand exactly that one back instead of clearing a seam still in use. */
    public static SiteWaitTap watcher() {
        return tap;
    }

    public static void begin(int siteIndex) {
        if (tap == null || siteIndex < 0 || siteIndex >= SITE_IDS.length) {
            return;
        }
        OPEN.get()[siteIndex] = System.nanoTime();
    }

    public static void end(int siteIndex) {
        end(siteIndex, null);
    }

    /** Closes an observation for one site, with the level the call ran in when the site holds one;
     * closing a site that was never opened, or closing it twice, is a no-op. */
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
