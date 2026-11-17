/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** The per-tick row claim shared by two owner sites; a later site skips what is taken. */
public final class OwnerTickClaim {

    private record RowKey(String worldId, int entitySeq) {
    }

    private final Map<Long, Map<RowKey, String>> claims = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, LongAdder>> conflicts = new ConcurrentHashMap<>();
    private final LongAdder totalConflicts = new LongAdder();

    /** Claims one row for one site in one tick; false means another site owns it. */
    public boolean claim(long tick, String siteId, String worldId, int entitySeq) {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(worldId, "worldId");
        Map<RowKey, String> tickClaims = claims.computeIfAbsent(tick,
            key -> new ConcurrentHashMap<>());
        RowKey row = new RowKey(worldId, entitySeq);
        String installed = tickClaims.putIfAbsent(row, siteId);
        if (installed == null || installed.equals(siteId)) {
            return true;
        }
        conflicts.computeIfAbsent(tick, key -> new ConcurrentHashMap<>())
            .computeIfAbsent(siteId, key -> new LongAdder()).increment();
        totalConflicts.increment();
        return false;
    }

    /** How many rows one site found taken by another site in one tick. */
    public long conflictsIn(long tick, String siteId) {
        Map<String, LongAdder> tickConflicts = conflicts.get(tick);
        if (tickConflicts == null) {
            return 0L;
        }
        LongAdder counter = tickConflicts.get(siteId);
        return counter == null ? 0L : counter.sum();
    }

    /** How many conflicts one site found over the process. */
    public long totalConflicts() {
        return totalConflicts.sum();
    }

    /** Keeps only the newest tick. */
    public void retainTick(long tick) {
        claims.keySet().removeIf(older -> older < tick);
        conflicts.keySet().removeIf(older -> older < tick);
    }

    /** Drops every claim and counter; test and evidence code only. */
    public void reset() {
        claims.clear();
        conflicts.clear();
        totalConflicts.reset();
    }
}
