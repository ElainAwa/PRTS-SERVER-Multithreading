/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.be;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The compound identity of one BE update and the registry that refuses a stale one: the identity
 * names the world incarnation, the block position, the BE type, the host block revision, the BE
 * lifecycle generation and the update ordinal, and a replaced host block or a rebuilt BE makes every
 * in-flight update of the old generation fail before its first write instead of landing on the new
 * object. */
public record BlockEntityIdentity(String worldId, long worldIncarnation, long blockPosLong,
                                  String beTypeId, long hostBlockRevision,
                                  long beLifecycleGeneration, long logicalUpdateOrdinal) {

    public BlockEntityIdentity {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(beTypeId, "beTypeId");
        if (worldId.isEmpty() || beTypeId.isEmpty()) {
            throw new IllegalArgumentException("a BE identity names its world and type");
        }
        if (worldIncarnation < 0L || hostBlockRevision < 0L || beLifecycleGeneration < 0L
            || logicalUpdateOrdinal < 0L) {
            throw new IllegalArgumentException("a BE identity cannot name a negative lineage");
        }
    }

    /** The verdict of one identity check. */
    public enum Verdict {
        ACCEPTED,
        IDEMPOTENT,
        STALE_HOST_BLOCK,
        STALE_BE_GENERATION,
        WORLD_MISMATCH
    }

    /** The outcome of one update admission; a refused update writes nothing. */
    public record Admission(Verdict verdict, boolean writesAllowed) {
    }

    private static final class Bound {

        private long hostBlockRevision;
        private long beLifecycleGeneration;
        private long logicalUpdateOrdinal;
    }

    /** The observation-only admission face: it counts accepted, idempotent and refused BE tick
     * identities from real world/position/type values and writes no host state. */
    public static final class Observation {

        private static final Registry REGISTRY = new Registry();
        private static long observed;
        private static long admitted;
        private static long idempotent;
        private static long refused;
        private static long failed;

        public static synchronized Admission observe(String worldId, long worldIncarnation,
                                                     long blockPosLong, String beTypeId,
                                                     long logicalUpdateOrdinal) {
            try {
                String type = beTypeId == null || beTypeId.isEmpty() ? "unknown" : beTypeId;
                BlockEntityIdentity identity = new BlockEntityIdentity(worldId,
                    Math.max(0L, worldIncarnation), blockPosLong, type, 0L, 0L,
                    Math.max(0L, logicalUpdateOrdinal));
                Admission admission = REGISTRY.admit(identity);
                observed++;
                if (admission.verdict() == Verdict.ACCEPTED) {
                    admitted++;
                } else if (admission.verdict() == Verdict.IDEMPOTENT) {
                    idempotent++;
                } else {
                    refused++;
                }
                return admission;
            } catch (Throwable unavailable) {
                failed++;
                return null;
            }
        }

        public static synchronized long observed() {
            return observed;
        }

        public static synchronized long admitted() {
            return admitted;
        }

        public static synchronized long idempotent() {
            return idempotent;
        }

        public static synchronized long refused() {
            return refused;
        }

        public static synchronized long failed() {
            return failed;
        }

        public static synchronized void reset() {
            REGISTRY.reset();
            observed = 0L;
            admitted = 0L;
            idempotent = 0L;
            refused = 0L;
            failed = 0L;
        }
    }

    /** One process-wide registry of the current host/rebuilt bindings of one block position. */
    public static final class Registry {

        private final Map<Long, Bound> bounds = new LinkedHashMap<>();
        private long refused;
        private long idempotent;

        /** Binds a new host block or BE generation; an older revision must not lower the binding. */
        public synchronized void bind(long blockPosLong, long hostBlockRevision,
                                       long beLifecycleGeneration) {
            Bound bound = bounds.computeIfAbsent(blockPosLong, key -> new Bound());
            bound.hostBlockRevision = Math.max(bound.hostBlockRevision, hostBlockRevision);
            bound.beLifecycleGeneration = Math.max(bound.beLifecycleGeneration,
                beLifecycleGeneration);
        }

        /** Admits one update only when its host revision and BE generation are still current. */
        public synchronized Admission admit(BlockEntityIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            Bound bound = bounds.get(identity.blockPosLong());
            if (bound == null) {
                bound = new Bound();
                bounds.put(identity.blockPosLong(), bound);
            }
            if (identity.hostBlockRevision() < bound.hostBlockRevision) {
                refused++;
                return new Admission(Verdict.STALE_HOST_BLOCK, false);
            }
            if (identity.beLifecycleGeneration() < bound.beLifecycleGeneration) {
                refused++;
                return new Admission(Verdict.STALE_BE_GENERATION, false);
            }
            if (identity.hostBlockRevision() > bound.hostBlockRevision
                || identity.beLifecycleGeneration() > bound.beLifecycleGeneration) {
                bind(identity.blockPosLong(), identity.hostBlockRevision(),
                    identity.beLifecycleGeneration());
            }
            if (identity.logicalUpdateOrdinal() < bound.logicalUpdateOrdinal) {
                refused++;
                return new Admission(Verdict.STALE_BE_GENERATION, false);
            }
            if (identity.logicalUpdateOrdinal() == bound.logicalUpdateOrdinal) {
                idempotent++;
                return new Admission(Verdict.IDEMPOTENT, false);
            }
            bound.logicalUpdateOrdinal = identity.logicalUpdateOrdinal();
            return new Admission(Verdict.ACCEPTED, true);
        }

        public synchronized long refused() {
            return refused;
        }

        public synchronized long idempotent() {
            return idempotent;
        }

        /** Clears the observation's bindings and counters; test and evidence code only. */
        public synchronized void reset() {
            bounds.clear();
            refused = 0L;
            idempotent = 0L;
        }
    }
}
