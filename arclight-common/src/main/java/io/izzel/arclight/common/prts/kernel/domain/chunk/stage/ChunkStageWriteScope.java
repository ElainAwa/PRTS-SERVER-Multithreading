/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.stage;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The owner-authorized write range of one generation stage: the range is acquired all or none
 * through the owner registry before the stage writes, a write outside the declared radius is refused
 * and counted instead of touching a chunk the stage does not own, and the released rights go back. */
public final class ChunkStageWriteScope {

    /** One declared write range: the world incarnation, holder generation, center chunk, radius. */
    public record Scope(String worldId, long worldIncarnation, long holderGen, long chunkPos,
                        int radius) {

        public Scope {
            Objects.requireNonNull(worldId, "worldId");
            if (worldId.isEmpty()) {
                throw new IllegalArgumentException("a write scope needs its world");
            }
            if (worldIncarnation < 0L || holderGen < 0L) {
                throw new IllegalArgumentException("a write scope cannot name a negative generation");
            }
            if (radius < 0) {
                throw new IllegalArgumentException("a write radius cannot be negative");
            }
        }

        /** Whether one target chunk is inside the declared radius of this scope. */
        public boolean contains(long targetChunkPos) {
            int dx = Math.abs(chunkX(targetChunkPos) - chunkX(chunkPos));
            int dz = Math.abs(chunkZ(targetChunkPos) - chunkZ(chunkPos));
            return dx <= radius && dz <= radius;
        }
    }

    /** What one write attempt did. */
    public record Attempt(boolean allowed, long targetChunkPos, String reason) {
    }

    /** The held rights of one acquired scope; the holder is the only one that releases them. */
    public record Handle(Scope scope, List<OwnerRegistry.OwnershipDomain> domains) {
    }

    /** The answer of one acquisition. */
    public record Acquired(Handle handle, boolean granted, String reason) {
    }

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final Map<Long, Long> revisions = new LinkedHashMap<>();
    private long acquired;
    private long outOfRadius;
    private long refusedRights;
    private long staleRevisions;

    /** One scope source over the shared registry and version slots. */
    public ChunkStageWriteScope(OwnerRegistry owners, WriteVersionSlots versions) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.versions = Objects.requireNonNull(versions, "versions");
    }

    /** Acquires every chunk domain of the range, all or none, under one holder site. */
    public synchronized Acquired acquire(Scope scope, String holderSite) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(holderSite, "holderSite");
        List<OwnerRegistry.OwnershipDomain> domains = new ArrayList<>();
        int centerX = chunkX(scope.chunkPos());
        int centerZ = chunkZ(scope.chunkPos());
        for (int dx = -scope.radius(); dx <= scope.radius(); dx++) {
            for (int dz = -scope.radius(); dz <= scope.radius(); dz++) {
                domains.add(listDomain(scope, chunkPos(centerX + dx, centerZ + dz)));
            }
        }
        domains.sort(ChunkStageWriteScope::compareDomains);
        List<OwnerToken> tokens = new ArrayList<>(domains.size());
        for (OwnerRegistry.OwnershipDomain domain : domains) {
            long version = versions.grant(domain.worldId(), domain.level(), domain.domainId());
            tokens.add(new OwnerToken(domain.worldId(), domain.level(), domain.domainId(), 1L,
                version, 0L, 0L, HolderKind.REGISTERED, holderSite));
        }
        if (!owners.acquireAll(tokens)) {
            refusedRights++;
            return new Acquired(null, false, "refused-ownership");
        }
        for (OwnerToken token : tokens) {
            if (versions.carried(token.worldId(), token.level(), token.domainId())
                != token.expectedVersion()) {
                for (OwnerRegistry.OwnershipDomain domain : domains) {
                    owners.release(domain, holderSite);
                }
                refusedRights++;
                return new Acquired(null, false, "refused-version");
            }
        }
        acquired++;
        return new Acquired(new Handle(scope, List.copyOf(domains)), true, "acquired");
    }

    /** An attempt to write one target chunk: outside the range is refused and counted. */
    public synchronized Attempt write(Handle handle, long targetChunkPos) {
        Objects.requireNonNull(handle, "handle");
        if (!handle.scope().contains(targetChunkPos)) {
            outOfRadius++;
            return new Attempt(false, targetChunkPos, "out-of-radius");
        }
        return new Attempt(true, targetChunkPos, "in-radius");
    }

    /** Releases every right the handle holds. */
    public synchronized void release(Handle handle, String holderSite) {
        Objects.requireNonNull(handle, "handle");
        for (OwnerRegistry.OwnershipDomain domain : handle.domains()) {
            owners.release(domain, holderSite);
        }
    }

    /** Advances one chunk's same-generation revision; a non-income revision never moves back. */
    public synchronized boolean advanceRevision(long chunkPos, long revision) {
        Long current = revisions.get(chunkPos);
        if (current != null && revision <= current) {
            staleRevisions++;
            return false;
        }
        revisions.put(chunkPos, revision);
        return true;
    }

    public synchronized long acquired() {
        return acquired;
    }

    public synchronized long outOfRadius() {
        return outOfRadius;
    }

    public synchronized long refusedRights() {
        return refusedRights;
    }

    public synchronized long staleRevisions() {
        return staleRevisions;
    }

    private static OwnerRegistry.OwnershipDomain listDomain(Scope scope, long chunkPos) {
        return new OwnerRegistry.OwnershipDomain(scope.worldId(), WriteLevel.SECTION,
            "chunk-stage:" + scope.worldIncarnation() + ":" + chunkPos);
    }

    private static int compareDomains(OwnerRegistry.OwnershipDomain left,
                                      OwnerRegistry.OwnershipDomain right) {
        int world = left.worldId().compareTo(right.worldId());
        return world != 0 ? world : left.domainId().compareTo(right.domainId());
    }

    private static int chunkX(long chunkPos) {
        return (int) (chunkPos >> 32);
    }

    private static int chunkZ(long chunkPos) {
        return (int) chunkPos;
    }

    private static long chunkPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
