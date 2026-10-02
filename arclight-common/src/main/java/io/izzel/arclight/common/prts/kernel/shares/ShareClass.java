/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;

/** The rows of the time-budget share table. Five rows are the asserted classes, one row carries
 * the region tick work as a single item, one row carries the world level resources and one is the
 * catch-all. */
public enum ShareClass {

    ENTITY("entity", 0.30),
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

    public String key() {
        return key;
    }

    public double weight() {
        return weight;
    }

    public static int rowCount() {
        return values().length;
    }

    /** Three classes deliberately map to nothing: chunk I/O belongs to the waiting side and the
     * storage side, native payload is metered on its own and must not squeeze the entity or region
     * shares, and observation itself must never be charged to a class share. */
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
