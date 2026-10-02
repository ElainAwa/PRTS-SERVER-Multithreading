/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

/** The rows of the per-class self timer. The first seven rows are the asserted class set: entity,
 * block entity, graph, event, AI, native payload and chunk I/O. */
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
        REGION,
        WORLD,
        SITE,
        PAYLOAD,
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

    public String key() {
        return key;
    }

    public boolean asserted() {
        return asserted;
    }

    public Dimension dimension() {
        return dimension;
    }

    public static int assertedCount() {
        int count = 0;
        for (SelfClass selfClass : values()) {
            if (selfClass.asserted) {
                count++;
            }
        }
        return count;
    }

    public static int rowCount() {
        return values().length;
    }
}
