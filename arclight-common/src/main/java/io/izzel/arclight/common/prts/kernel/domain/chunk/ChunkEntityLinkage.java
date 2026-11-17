/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The linkage of one chunk to the entity due set: a chunk forwards its rows only after it really
 * reached ENTITY_TICKING, and a failed or unloaded holder generation withdraws every forwarded row
 * immediately instead of leaving it in the due set. */
public final class ChunkEntityLinkage {

    /** The holder generation and chunk one linkage belongs to. */
    public record Key(long worldIncarnation, long holderGen, long chunkPos) {

        public Key {
            if (worldIncarnation < 0L || holderGen < 0L) {
                throw new IllegalArgumentException("a linkage cannot name a negative lineage");
            }
        }
    }

    /** What one forward request did: the rows admitted and the explicit reason of a refusal. */
    public record Forwarded(boolean admitted, List<EntityRowIdentity> rows, String reason) {
    }

    private final Key key;
    private final ChunkCompletionState completion;
    private final Set<EntityRowIdentity> forwarded = new LinkedHashSet<>();
    private long admitted;
    private long withdrawn;
    private long refused;
    private boolean unloaded;

    public ChunkEntityLinkage(Key key, ChunkCompletionState completion) {
        this.key = Objects.requireNonNull(key, "key");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /** Forwards the rows only when the chunk is entity-ticking; otherwise names the refusal. */
    public synchronized Forwarded forward(List<EntityRowIdentity> rows) {
        Objects.requireNonNull(rows, "rows");
        if (unloaded) {
            refused++;
            return new Forwarded(false, List.of(), "holder-unloaded");
        }
        if (!completion.entityTicking()) {
            refused++;
            return new Forwarded(false, List.of(), "entity-ticking-not-ready");
        }
        List<EntityRowIdentity> admittedRows = new ArrayList<>();
        for (EntityRowIdentity row : rows) {
            if (row != null && forwarded.add(row)) {
                admittedRows.add(row);
            }
        }
        admitted += admittedRows.size();
        return new Forwarded(true, List.copyOf(admittedRows), "forwarded");
    }

    /** Withdraws one row at once; a row that was never forwarded is an explicit no-op. */
    public synchronized boolean withdraw(EntityRowIdentity row) {
        if (forwarded.remove(row)) {
            withdrawn++;
            return true;
        }
        return false;
    }

    /** Withdraws every forwarded row because the chunk failed or unloaded; no row stays due. */
    public synchronized int withdrawAll(String reason) {
        int removed = forwarded.size();
        forwarded.clear();
        withdrawn += removed;
        return removed;
    }

    /** Marks this holder generation unloaded; no later forward is admitted. */
    public synchronized void unload() {
        unloaded = true;
        withdrawAll("unloaded");
    }

    public synchronized boolean contains(EntityRowIdentity row) {
        return forwarded.contains(row);
    }

    public synchronized List<EntityRowIdentity> rows() {
        return List.copyOf(forwarded);
    }

    public synchronized int forwardedCount() {
        return forwarded.size();
    }

    public synchronized long admitted() {
        return admitted;
    }

    public synchronized long withdrawn() {
        return withdrawn;
    }

    public synchronized long refused() {
        return refused;
    }

    public Key key() {
        return key;
    }
}
