/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The independent counter of the original entity tick, per tick and per row: the call of the base
 * tick body and the call of the host tick path itself, the second record of the event an ownership
 * decision covers. Armed with arclight.prts.entityCensus; tick thread only. */
package io.izzel.arclight.common.prts.support;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.LongAdder;

/** The calls of the original entity tick of one tick, by row and by path. */
public final class PrtsHostTickCalls {

    /** Whether this process declared the observation face. */
    public static final boolean ARMED = Boolean.getBoolean("arclight.prts.entityCensus");

    private static final Int2IntOpenHashMap BODY = new Int2IntOpenHashMap();
    private static final Int2IntOpenHashMap PATH = new Int2IntOpenHashMap();
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder PATHS = new LongAdder();
    private static final LongAdder TICKS = new LongAdder();
    private static final LongAdder UNTRACKED = new LongAdder();
    private static boolean open;

    private PrtsHostTickCalls() {
    }

    public static void beginTick() {
        open = true;
        BODY.clear();
        PATH.clear();
        TICKS.increment();
    }

    /** Counts one call of the base tick body, however the row was ticked. */
    public static void note(Entity entity) {
        noteBodyRow(entity.getId());
    }

    static void noteBodyRow(int entityId) {
        TOTAL.increment();
        if (!open) {
            // Outside the tick the fixture planned, so it belongs to no decision of ours.
            UNTRACKED.increment();
            return;
        }
        BODY.put(entityId, BODY.get(entityId) + 1);
    }

    /** Counts one execution of the host tick path of a row, whatever its tick body does. */
    public static void notePath(Entity entity) {
        notePathRow(entity.getId());
    }

    static void notePathRow(int entityId) {
        if (!open) {
            UNTRACKED.increment();
            return;
        }
        PATHS.increment();
        PATH.put(entityId, PATH.get(entityId) + 1);
    }

    /** How many times the base body of this row ran in the tick that is open. */
    public static int bodyCallsOf(int entityId) {
        return BODY.get(entityId);
    }

    /** How many times the host tick path of this row ran in the tick that is open. */
    public static int pathCallsOf(int entityId) {
        return PATH.get(entityId);
    }

    public static void endTick() {
        open = false;
    }

    /** One evidence line: the calls of both paths, the ticks opened and the calls outside one. */
    public static String evidence() {
        return "probe_calls=" + TOTAL.sum() + " probe_path_calls=" + PATHS.sum()
            + " probe_ticks=" + TICKS.sum() + " probe_untracked=" + UNTRACKED.sum();
    }

    /** Clears every counter; the readout reset and the tests use it. */
    public static void reset() {
        BODY.clear();
        PATH.clear();
        TOTAL.reset();
        PATHS.reset();
        TICKS.reset();
        UNTRACKED.reset();
        open = false;
    }
}
