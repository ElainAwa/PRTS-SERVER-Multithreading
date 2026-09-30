/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

/**
 * The rows of the per-class self timer.
 *
 * <p>The first seven rows are the asserted class set: entity, block entity, graph, event, AI,
 * native payload and chunk I/O. The remaining rows are supplementary and follow the same output
 * discipline, zero values included. The region tick work is the one place where three timer rows
 * share a single share row, and the mapping says so explicitly.</p>
 */
public enum SelfClass {

    ENTITY("entity", true, Dimension.REGION),
    BLOCKENTITY("blockentity", true, Dimension.REGION),
    GRAPH("graph", true, Dimension.WORLD),
    EVENT("event", true, Dimension.SITE),
    AI("ai", true, Dimension.REGION),
    NATIVE("native", true, Dimension.PAYLOAD),
    CHUNKIO("chunkio", true, Dimension.WORLD),
    REGIONTICK_RANDOM("regiontick.random", false, Dimension.REGION),
    REGIONTICK_FLUID("regiontick.fluid", false, Dimension.REGION),
    REGIONTICK_BLOCKUPDATE("regiontick.blockupdate", false, Dimension.REGION),
    WORLDRES("worldres", false, Dimension.WORLD),
    OBSERVE("observe", false, Dimension.NONE),
    OTHER("other", false, Dimension.WORLD);

    /** The decomposition dimension a row is reported over. */
    public enum Dimension {
        /** World times region. */
        REGION,
        /** World. */
        WORLD,
        /** World times site. */
        SITE,
        /** World times payload. */
        PAYLOAD,
        /** No decomposition; observation itself. */
        NONE
    }

    private final String key;
    private final boolean asserted;
    private final Dimension dimension;

    SelfClass(String key, boolean asserted, Dimension dimension) {
        this.key = key;
        this.asserted = asserted;
        this.dimension = dimension;
    }

    /** @return the name the row is published under, after the {@code self.} prefix */
    public String key() {
        return key;
    }

    /** @return {@code true} when this row belongs to the asserted class set */
    public boolean asserted() {
        return asserted;
    }

    /** @return the dimension the row is decomposed over */
    public Dimension dimension() {
        return dimension;
    }

    /** @return the number of asserted rows; the completeness assertion uses it */
    public static int assertedCount() {
        int count = 0;
        for (SelfClass selfClass : values()) {
            if (selfClass.asserted) {
                count++;
            }
        }
        return count;
    }

    /** @return the number of rows the timer publishes, zero values included */
    public static int rowCount() {
        return values().length;
    }
}
