/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares the two arms tick by tick and locates the first fork.
 *
 * <p>The comparison is read-only and never repairs anything. A mismatch descends six levels - tick,
 * world, region, batch, entity sequence, field - each narrowed to the context above it. A mismatch
 * the descent cannot place is counted as unattributed and still makes the pair unequal.</p>
 */
public final class DiffProbe {

    private long tickPairs;
    private long equal;
    private long unattributed;
    private long firstForkTick = -1L;
    private String firstForkWorld = "";
    private String firstForkRegion = "";
    private long firstForkBatch = -1L;
    private long firstForkEntitySeq = -1L;
    private String firstForkField = "";
    private final Set<String> forkedFields = new LinkedHashSet<>();
    private final Set<String> attributedWorlds = new LinkedHashSet<>();
    private long attributedSites;

    /**
     * Compares one pair of hashes.
     *
     * @param parallel the hash of the parallel arm
     * @param serial   the hash of the serial arm
     */
    public void compare(DomainHash parallel, DomainHash serial) {
        tickPairs++;
        if (parallel == null || serial == null || !parallel.comparable()
            || !serial.comparable()) {
            unattributed++;
            return;
        }
        if (parallel.algorithmId().equals(serial.algorithmId())
            && parallel.value() == serial.value()) {
            equal++;
            return;
        }
        Fork fork = locate(parallel, serial);
        if (fork == null) {
            unattributed++;
            return;
        }
        if (firstForkTick < 0) {
            firstForkTick = parallel.tickIndex();
            firstForkWorld = fork.world;
            firstForkRegion = fork.region;
            firstForkBatch = fork.batch;
            firstForkEntitySeq = fork.entitySeq;
            firstForkField = fork.field;
        }
        forkedFields.add(fork.field);
        attributedWorlds.add(fork.world);
        attributedSites++;
    }

    private Fork locate(DomainHash parallel, DomainHash serial) {
        // Level one and two: the world set is fixed for both arms, so a world that only one arm has
        // is already a located fork.
        String world = firstDivergentKey(parallel.worldDigests(), serial.worldDigests());
        if (world == null) {
            return null;
        }
        Map<String, Long> parRegions = regionsOf(parallel, world);
        Map<String, Long> serRegions = regionsOf(serial, world);
        String region = firstDivergentKey(parRegions, serRegions);
        if (region == null) {
            return null;
        }
        String regionPrefix = world + "|" + region + "|";
        String batchKey = firstDivergentKey(withPrefix(parallel.batchDigests(), regionPrefix),
            withPrefix(serial.batchDigests(), regionPrefix));
        Long batch = batchKey == null ? null : tailLong(batchKey);
        if (batch == null) {
            return null;
        }
        String batchPrefix = batchKey + "|";
        String entityKey = firstDivergentKey(withPrefix(parallel.entityDigests(), batchPrefix),
            withPrefix(serial.entityDigests(), batchPrefix));
        Long entity = entityKey == null ? null : tailLong(entityKey);
        if (entity == null) {
            return null;
        }
        String field = firstDivergentKey(parallel.fieldDigests(), serial.fieldDigests());
        if (field == null) {
            return null;
        }
        return new Fork(world, region, batch, entity, field);
    }

    private static Map<String, Long> withPrefix(Map<String, Long> digests, String prefix) {
        Map<String, Long> selected = new TreeMap<>();
        for (Map.Entry<String, Long> entry : digests.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    private static Long tailLong(String key) {
        int cut = key.lastIndexOf('|');
        if (cut < 0 || cut + 1 >= key.length()) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(cut + 1));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Map<String, Long> regionsOf(DomainHash hash, String world) {
        Map<String, Long> regions = new TreeMap<>();
        String prefix = world + "|";
        for (Map.Entry<String, Long> entry : hash.regionDigests().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                regions.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return regions;
    }

    private static String firstDivergentKey(Map<String, Long> parallel, Map<String, Long> serial) {
        Set<String> keys = new TreeMap<>(parallel).keySet();
        Set<String> other = new TreeMap<>(serial).keySet();
        for (String key : keys) {
            if (!other.contains(key) || !java.util.Objects.equals(parallel.get(key),
                serial.get(key))) {
                return key;
            }
        }
        for (String key : other) {
            if (!parallel.containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    /** @return the report of everything compared so far */
    public DiffReport report() {
        double rate = tickPairs == 0 ? 0.0 : (double) equal / (double) tickPairs;
        return new DiffReport(tickPairs, equal, rate,
            tickPairs == 0 ? "" : "fnv1a64-bitexact-v1", firstForkTick, firstForkWorld,
            firstForkRegion, firstForkBatch, firstForkEntitySeq, firstForkField, unattributed,
            forkedFields.size(), attributedSites, attributedWorlds.size());
    }

    /** @return the pairs compared so far */
    public long tickPairs() {
        return tickPairs;
    }

    /** @return the pairs that were equal */
    public long equal() {
        return equal;
    }

    /** @return mismatches the descent could not place */
    public long unattributed() {
        return unattributed;
    }

    /** Clears the comparison; used by the readout reset and by tests. */
    public void reset() {
        tickPairs = 0L;
        equal = 0L;
        unattributed = 0L;
        firstForkTick = -1L;
        firstForkWorld = "";
        firstForkRegion = "";
        firstForkBatch = -1L;
        firstForkEntitySeq = -1L;
        firstForkField = "";
        forkedFields.clear();
        attributedWorlds.clear();
        attributedSites = 0L;
    }

    private record Fork(String world, String region, long batch, long entitySeq, String field) {
    }
}
