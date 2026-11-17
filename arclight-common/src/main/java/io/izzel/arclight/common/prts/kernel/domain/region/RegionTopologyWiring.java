/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;


import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The readout-exit witness of the region topology: the real ticking chunks of one world are fed to
 * the eight-connected topology; a load/unload that really merges or splits a component advances the
 * topology generation, and a readout reset leaves it untouched. It never touches scheduling, the
 * barrier count or the slow-region isolation. */
public final class RegionTopologyWiring {

    private static final Map<String, RegionTopology> TOPOLOGIES = new LinkedHashMap<>();

    private RegionTopologyWiring() {
    }

    /** Feeds one world's loaded chunk set to its topology; only the readout exit calls this. */
    public static synchronized void observe(String worldId, long[] chunks) {
        if (worldId == null || chunks == null) {
            return;
        }
        RegionTopology topology = TOPOLOGIES.computeIfAbsent(worldId,
            RegionTopology::new);
        Set<Long> desired = new LinkedHashSet<>();
        for (long chunk : chunks) {
            desired.add(chunk);
        }
        Set<Long> current = loaded(topology);
        for (long chunk : desired) {
            if (!current.contains(chunk)) {
                topology.load(chunk);
            }
        }
        for (long chunk : current) {
            if (!desired.contains(chunk)) {
                topology.unload(chunk);
            }
        }
    }

    /** The topology of one world, for the readout witness and the local tests. */
    public static synchronized RegionTopology topology(String worldId) {
        return TOPOLOGIES.get(worldId);
    }

    /** A readout reset is not a topology change. */
    public static synchronized void resetReadout(String worldId) {
        RegionTopology topology = TOPOLOGIES.get(worldId);
        if (topology != null) {
            topology.resetReadout();
        }
    }

    /** Drops the witness state; test and evidence code only. */
    public static synchronized void clear() {
        TOPOLOGIES.clear();
    }

    private static Set<Long> loaded(RegionTopology topology) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (RegionTopology.Region region : topology.regions()) {
            chunks.addAll(region.chunks());
        }
        return chunks;
    }
}
