/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;

/** The default-off owner-result ledger for one level tick: a record is keyed by dimension and row,
 * so the same numeric id in two dimensions never shares or removes another dimension's entry. A
 * record expires on the next tick or in another dimension, and the next record in that dimension
 * clears the older tick entries so a removed entity leaves nothing behind. */
public final class RestOwnerWiring {

    private record Scope(long tick, RealEntityOwnerBody.Result result) {
    }

    private static final Map<String, Map<Integer, Scope>> RESULTS = new LinkedHashMap<>();
    private static final Map<String, Long> LAST_TICK = new LinkedHashMap<>();
    private static final LongAdder SKIPS = new LongAdder();

    private RestOwnerWiring() {
    }

    /** Whether the owner path is declared; false is the only shipped value. */
    public static boolean enabled() {
        return EntityRowOwnershipWindow.enabled();
    }

    /** Records one owner outcome for the row's current level tick; disabled records nothing and
     * takes no ledger lock on the shipped default path. */
    public static void record(Entity entity, RealEntityOwnerBody.Result result) {
        if (!enabled() || entity == null) {
            return;
        }
        recordLocked(entity, result);
    }

    private static synchronized void recordLocked(Entity entity,
                                                   RealEntityOwnerBody.Result result) {
        if (!(entity.level() instanceof ServerLevel level) || !skips(result)) {
            return;
        }
        recordScoped(level.dimension().location().toString(), level.getGameTime(),
            entity.getId(), result);
    }

    /** Whether the host must not tick this row in the same level tick; the disabled fast path
     * returns before the ledger lock, so the shipped default path is unchanged. */
    public static boolean hostMustSkip(Entity entity) {
        if (!enabled() || entity == null) {
            return false;
        }
        return hostMustSkipLocked(entity);
    }

    private static synchronized boolean hostMustSkipLocked(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return hostMustSkipScoped(level.dimension().location().toString(), level.getGameTime(),
            entity.getId());
    }

    static synchronized void recordScoped(String dimension, long tick, int row,
                                          RealEntityOwnerBody.Result result) {
        if (!enabled() || !skips(result)) {
            return;
        }
        Map<Integer, Scope> rows = RESULTS.computeIfAbsent(dimension,
            key -> new LinkedHashMap<>());
        Long last = LAST_TICK.get(dimension);
        if (last == null || last != tick) {
            // One clear at the tick boundary, not one scan per recorded row: the old entries all
            // belong to an earlier tick, so dropping them all is the same expiry the scan did.
            rows.clear();
            LAST_TICK.put(dimension, tick);
        }
        rows.put(row, new Scope(tick, result));
    }

    static synchronized boolean hostMustSkipScoped(String dimension, long tick, int row) {
        if (!enabled()) {
            return false;
        }
        Map<Integer, Scope> rows = RESULTS.get(dimension);
        if (rows == null) {
            return false;
        }
        Scope scope = rows.get(row);
        if (scope == null) {
            return false;
        }
        if (scope.tick() != tick) {
            rows.remove(row);
            if (rows.isEmpty()) {
                RESULTS.remove(dimension);
            }
            return false;
        }
        boolean skip = skips(scope.result());
        if (skip) {
            SKIPS.increment();
        }
        return skip;
    }

    private static boolean skips(RealEntityOwnerBody.Result result) {
        return result == RealEntityOwnerBody.Result.EXECUTED
            || result == RealEntityOwnerBody.Result.TERMINAL;
    }

    static synchronized void clearRows(java.util.Collection<Integer> rows) {
        for (Map<Integer, Scope> dimension : RESULTS.values()) {
            for (Integer row : rows) {
                dimension.remove(row);
            }
        }
        RESULTS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** Counts the one host skip owned by a body executed at the original call site. */
    static void noteSkip() {
        SKIPS.increment();
    }

    static long skips() {
        return SKIPS.sum();
    }

    static synchronized int recorded() {
        int count = 0;
        for (Map<Integer, Scope> rows : RESULTS.values()) {
            count += rows.size();
        }
        return count;
    }

    static synchronized boolean terminal(int row) {
        for (Map<Integer, Scope> rows : RESULTS.values()) {
            Scope scope = rows.get(row);
            if (scope != null && scope.result() == RealEntityOwnerBody.Result.TERMINAL) {
                return true;
            }
        }
        return false;
    }

    static synchronized int terminals() {
        return count(RealEntityOwnerBody.Result.TERMINAL);
    }

    static synchronized int executed() {
        return count(RealEntityOwnerBody.Result.EXECUTED);
    }

    private static int count(RealEntityOwnerBody.Result result) {
        int count = 0;
        for (Map<Integer, Scope> rows : RESULTS.values()) {
            for (Scope scope : rows.values()) {
                if (scope.result() == result) {
                    count++;
                }
            }
        }
        return count;
    }
}
