/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.light;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The convergence gate of a chunk's light sections: a boundary inflow change invalidates the
 * section and its horizontal neighbours, a late result of an older holder generation is refused,
 * and only a section whose converged revision caught its dirt and whose inflow is stable is
 * send-ready. Boundary propagation is nested here because it is one decision of this gate, not a
 * separate type the callers name. */
public final class LightConvergenceGate {

    /** One section of one holder generation of one world incarnation. */
    public record SectionKey(long worldIncarnation, long holderGen, long sectionPos) {

        public SectionKey {
            if (worldIncarnation < 0L || holderGen < 0L) {
                throw new IllegalArgumentException("a section key cannot name a negative lineage");
            }
        }
    }

    /** The pinned solve of one section. */
    public record Solve(SectionKey key, LightSectionRevision.SolveToken token) {
    }

    /** What one solve finish did. */
    public enum Finish {
        CONVERGED,
        SUCCESSOR_REQUIRED,
        STALE_GENERATION
    }

    private final Map<SectionKey, LightSectionRevision> sections = new LinkedHashMap<>();
    private final Map<SectionKey, LightSectionRevision> heightmaps = new LinkedHashMap<>();
    private final Map<Long, Long> holderGenerations = new LinkedHashMap<>();
    private final Set<Long> unloaded = new LinkedHashSet<>();
    private long invalidated;
    private long staleResults;
    private long published;
    private long halfLit;

    public LightSectionRevision revision(SectionKey key) {
        return revision(key, LightSectionRevision.Kind.LIGHT);
    }

    public synchronized LightSectionRevision revision(SectionKey key,
                                                      LightSectionRevision.Kind kind) {
        Objects.requireNonNull(key, "key");
        Map<SectionKey, LightSectionRevision> map =
            kind == LightSectionRevision.Kind.LIGHT ? sections : heightmaps;
        return map.computeIfAbsent(key, created -> new LightSectionRevision(kind));
    }

    /** Raises one section's dirt; the holder generation stays the section key's own. */
    public synchronized void markDirty(SectionKey key, long revision) {
        revision(key).markDirty(revision);
        holderGenerations.merge(key.sectionPos(), key.holderGen(), Math::max);
    }

    /**
     * A neighbour boundary inflow change invalidates the section and its horizontal neighbours,
     * including the sections of the adjacent chunks, and never clears their dirt.
     */
    public synchronized int boundaryInflow(long sectionPos, long inflowVersion) {
        int affected = 0;
        for (long neighbour : neighbours(sectionPos)) {
            for (Map<SectionKey, LightSectionRevision> map : List.of(sections, heightmaps)) {
                for (Map.Entry<SectionKey, LightSectionRevision> held : map.entrySet()) {
                    if (held.getKey().sectionPos() == neighbour) {
                        held.getValue().noteBoundaryInflow(inflowVersion);
                        affected++;
                    }
                }
            }
        }
        if (affected > 0) {
            invalidated++;
        }
        return affected;
    }

    /** Pins one section solve; a solve of an older holder generation is refused. */
    public synchronized Solve beginSolve(SectionKey key) {
        Objects.requireNonNull(key, "key");
        Long current = holderGenerations.get(key.sectionPos());
        if (current != null && key.holderGen() < current || unloaded.contains(key.sectionPos())) {
            staleResults++;
            return null;
        }
        LightSectionRevision.SolveToken token = revision(key).beginSolve();
        return token == null ? null : new Solve(key, token);
    }

    /** Finishes one solve; the result is stale, a successor is owed, or the section converged. */
    public synchronized Finish finishSolve(Solve solve) {
        Objects.requireNonNull(solve, "solve");
        SectionKey key = solve.key();
        Long current = holderGenerations.get(key.sectionPos());
        if (current != null && key.holderGen() < current || unloaded.contains(key.sectionPos())) {
            staleResults++;
            return Finish.STALE_GENERATION;
        }
        LightSectionRevision section = revision(key);
        if (!section.converge(solve.token())) {
            section.endWithSuccessor();
            return Finish.SUCCESSOR_REQUIRED;
        }
        return Finish.CONVERGED;
    }

    /** The section is send-ready only when its settled revision caught its dirt and no successor
     * is owed; a half-lit section is refused and counted, never published. */
    public synchronized boolean sendReady(SectionKey key) {
        Objects.requireNonNull(key, "key");
        Long current = holderGenerations.get(key.sectionPos());
        if (current != null && key.holderGen() < current || unloaded.contains(key.sectionPos())) {
            staleResults++;
            return false;
        }
        LightSectionRevision section = sections.get(key);
        if (section == null || section.halfLit() || !section.converged()) {
            halfLit++;
            return false;
        }
        published++;
        return true;
    }

    /** Advances one chunk's holder generation and drops its sections: a late result is refused. */
    public synchronized void advanceGeneration(long sectionPos, long holderGen) {
        Long current = holderGenerations.get(sectionPos);
        if (current != null && holderGen <= current) {
            return;
        }
        holderGenerations.put(sectionPos, holderGen);
        unloaded.remove(sectionPos);
        sections.keySet().removeIf(key -> key.sectionPos() == sectionPos
            && key.holderGen() < holderGen);
        heightmaps.keySet().removeIf(key -> key.sectionPos() == sectionPos
            && key.holderGen() < holderGen);
    }

    /** Marks one chunk unloaded; every later solve of it is refused instead of completed. */
    public synchronized void unload(long sectionPos) {
        unloaded.add(sectionPos);
        sections.keySet().removeIf(key -> key.sectionPos() == sectionPos);
        heightmaps.keySet().removeIf(key -> key.sectionPos() == sectionPos);
    }

    public synchronized long invalidatedSections() {
        return invalidated;
    }

    public synchronized long staleResults() {
        return staleResults;
    }

    public synchronized long publishedSections() {
        return published;
    }

    public synchronized long halfLitSections() {
        return halfLit;
    }

    /** The section and its horizontal neighbours, across the chunk boundary at the same height. */
    public static List<Long> neighbours(long sectionPos) {
        int x = (int) (sectionPos & 0x3FFFFFL);
        int z = (int) ((sectionPos >> 22) & 0x3FFFFFL);
        long y = sectionPos & ~0x3FFFFFL & ~(0x3FFFFFL << 22);
        List<Long> neighbours = new ArrayList<>(5);
        neighbours.add(sectionPos);
        neighbours.add((long) (x + 1) & 0x3FFFFFL | y);
        neighbours.add(((long) (x - 1) & 0x3FFFFFL) | y);
        neighbours.add((((long) z + 1) & 0x3FFFFFL) << 22 | y);
        neighbours.add((((long) z - 1) & 0x3FFFFFL) << 22 | y);
        return List.copyOf(neighbours);
    }
}
