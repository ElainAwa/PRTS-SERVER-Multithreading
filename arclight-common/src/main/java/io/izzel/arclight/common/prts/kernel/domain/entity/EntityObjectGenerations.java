/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The object lifecycle generations the entity domain has seen: one monotone number per UUID pair in
 * one world. A number is minted when the pair is first observed and the whole world's numbers are
 * retired when the world incarnation moves, so a row frozen against one object can never match a
 * later object that reuses its numeric id. The tick thread is the only writer and reader.
 */
public final class EntityObjectGenerations {

    /** Memory guard of the seen-pair table: past it the oldest pair is forgotten, which only makes
     * a later write-back refuse. */
    private static final int CAP = 1 << 20;

    private static final EntityObjectGenerations SHARED = new EntityObjectGenerations();

    /** One object lifecycle key: the UUID pair is the object, the world incarnation scopes it. */
    private record Key(String worldId, long uuidHigh, long uuidLow) {
    }

    private final Map<Key, Long> generations = new LinkedHashMap<>();
    private final Map<String, Long> worldEpochs = new LinkedHashMap<>();
    private long nextGeneration = 1L;

    private EntityObjectGenerations() {
    }

    /** The table the entity domain observes its rows into. */
    public static EntityObjectGenerations shared() {
        return SHARED;
    }

    /** Observes one live pair and answers its lifecycle generation. */
    public long observe(String worldId, long worldEpoch, long uuidHigh, long uuidLow) {
        if (worldId == null || worldId.isEmpty()) {
            return 0L;
        }
        Long seenEpoch = worldEpochs.get(worldId);
        if (seenEpoch == null || seenEpoch != worldEpoch) {
            // The world incarnation moved: every object of the old one is a different object now.
            worldEpochs.put(worldId, worldEpoch);
            retireWorld(worldId);
        }
        Key key = new Key(worldId, uuidHigh, uuidLow);
        Long known = generations.get(key);
        if (known != null) {
            return known;
        }
        long minted = nextGeneration++;
        generations.put(key, minted);
        while (generations.size() > CAP) {
            Iterator<Key> oldest = generations.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return minted;
    }

    /** The lifecycle generation of one pair in one world, or zero when it was never observed. */
    public long generationOf(String worldId, long uuidHigh, long uuidLow) {
        if (worldId == null) {
            return 0L;
        }
        Long known = generations.get(new Key(worldId, uuidHigh, uuidLow));
        return known == null ? 0L : known;
    }

    private void retireWorld(String worldId) {
        generations.keySet().removeIf(key -> key.worldId().equals(worldId));
    }
}
