/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import java.util.LinkedHashMap;
import java.util.Map;

/** The slow-region isolation: one region over its time share is isolated on its own while every
 * other region of every world keeps its own deadline, so the server-wide barrier wait stays zero. */
public final class SlowRegionIsolation {

    /** What one region's share check did. */
    public record Verdict(boolean isolated, long missedSla, String reason) {
    }

    private final Map<String, Long> missed = new LinkedHashMap<>();
    private long barrierWaits;
    private long isolatedRegions;

    /** Notes one region's self/share pair; only the over-share region itself is isolated. */
    public synchronized Verdict observe(String worldId, long regionId, long selfMs, long shareMs) {
        if (selfMs <= shareMs) {
            return new Verdict(false, missed.getOrDefault(key(worldId, regionId), 0L), "within-share");
        }
        isolatedRegions++;
        long missedCount = missed.merge(key(worldId, regionId), 1L, Long::sum);
        return new Verdict(true, missedCount, "isolated-over-share");
    }

    /** A healthy region or another world never sees the barrier count move. */
    public synchronized long barrierWaits() {
        return barrierWaits;
    }

    public synchronized long missedSla(String worldId, long regionId) {
        return missed.getOrDefault(key(worldId, regionId), 0L);
    }

    public synchronized long isolatedRegions() {
        return isolatedRegions;
    }

    private static String key(String worldId, long regionId) {
        return worldId + ":" + regionId;
    }
}
