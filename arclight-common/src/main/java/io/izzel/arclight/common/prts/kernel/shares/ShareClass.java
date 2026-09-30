/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;

/**
 * The rows of the time-budget share table.
 *
 * <p>Five rows are the asserted classes, one row carries the region tick work as a single item,
 * one row carries the world level resources and one is the catch-all. The weights split the fair
 * share of a world; the reserved pool is not a row here and is planned as its own column.</p>
 */
public enum ShareClass {

    ENTITY("entity", 0.32),
    BLOCKENTITY("blockentity", 0.20),
    GRAPH("graph", 0.08),
    EVENT("event", 0.08),
    AI("ai", 0.24),
    REGIONTICK("regiontick", 0.06),
    WORLDRES("worldres", 0.02),
    OTHER("other", 0.02);

    private final String key;
    private final double weight;

    ShareClass(String key, double weight) {
        this.key = key;
        this.weight = weight;
    }

    /** @return the name the row is published under */
    public String key() {
        return key;
    }

    /** @return the part of a world fair share this row receives */
    public double weight() {
        return weight;
    }

    /** @return the number of rows, the row count the readout publishes */
    public static int rowCount() {
        return values().length;
    }

    /**
     * Maps a metered class onto its share row.
     *
     * <p>Three classes deliberately map to nothing: chunk I/O belongs to the waiting side and the
     * storage side, native payload is metered on its own and must not squeeze the entity or region
     * shares, and observation itself must never be charged to a class share. The mapping is the
     * only place this decision is written.</p>
     *
     * @param selfClass the metered class
     * @return the share row, or {@code null} when the class has no row of its own
     */
    public static ShareClass of(SelfClass selfClass) {
        return switch (selfClass) {
            case ENTITY -> ENTITY;
            case BLOCKENTITY -> BLOCKENTITY;
            case GRAPH -> GRAPH;
            case EVENT -> EVENT;
            case AI -> AI;
            case REGIONTICK_RANDOM, REGIONTICK_FLUID, REGIONTICK_BLOCKUPDATE -> REGIONTICK;
            case WORLDRES -> WORLDRES;
            case OTHER -> OTHER;
            case NATIVE, CHUNKIO, OBSERVE -> null;
        };
    }
}
