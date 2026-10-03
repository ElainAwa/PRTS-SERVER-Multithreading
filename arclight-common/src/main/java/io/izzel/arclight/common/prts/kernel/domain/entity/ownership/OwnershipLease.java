/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The token table of one entity tick. The plan point freezes a row and issues a token for it, the
 * worker pool answers the token with the whole-tick model of that row, and the host entry consumes
 * or revokes the token of every row it reaches. The table is written by the tick thread before it
 * is published to the pool and read by the tick thread again at the host entries; the only field a
 * worker publishes is the per-row state, which is atomic, so one row has exactly one owner and at
 * most one outcome.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The leases of one tick. A row is addressed by its index, the host entry addresses it by entity
 * id, and a token is spent once: it is either consumed by the host entry that committed the answer
 * of the row, or revoked, so a late worker result can never skip a row the host already ran.
 */
final class OwnershipLease {

    /** Issued, no worker answer yet. */
    static final int PENDING = 0;
    /** A worker answered and the answer is published. */
    static final int SETTLED = 1;
    /** The worker answered with a failure. */
    static final int FAILED = 2;
    /** The host entry committed the answer of the row and skipped its original tick. */
    static final int CONSUMED = 3;
    /** The token was withdrawn: the row runs on the host. */
    static final int REVOKED = 4;

    private final long tickIndex;
    private final int capacity;
    private int rows;
    private final int[] ids;
    private final long[] tokens;
    private final long[] worldEpochs;
    private final long[] entityEpochs;
    private final int[] hostTickVersions;
    private final byte[] fingerprints;
    private final TickState[] captured;
    private final TickState[] answers;
    private final WholeTickModel[] models;
    private final boolean[] looked;
    private final AtomicIntegerArray states;
    private final Int2IntOpenHashMap indexById = new Int2IntOpenHashMap();
    private final LongAdder settled = new LongAdder();
    private final LongAdder lateDropped = new LongAdder();
    private long nextToken;
    private volatile boolean closed;

    OwnershipLease(long tickIndex, int capacity, long firstToken) {
        this.tickIndex = tickIndex;
        this.capacity = capacity;
        this.nextToken = firstToken;
        this.ids = new int[capacity];
        this.tokens = new long[capacity];
        this.worldEpochs = new long[capacity];
        this.entityEpochs = new long[capacity];
        this.hostTickVersions = new int[capacity];
        this.fingerprints = new byte[capacity];
        this.captured = new TickState[capacity];
        this.answers = new TickState[capacity];
        this.models = new WholeTickModel[capacity];
        this.looked = new boolean[capacity];
        this.states = new AtomicIntegerArray(capacity);
        this.indexById.defaultReturnValue(-1);
    }

    long tickIndex() {
        return tickIndex;
    }

    int capacity() {
        return capacity;
    }

    int rows() {
        return rows;
    }

    /** Freezes one row and issues its token; the caller read every value on the tick thread. */
    int issue(int entityId, long worldEpoch, long entityEpoch, int hostTickVersion, byte fingerprint,
        WholeTickModel model, TickState state) {
        if (rows >= capacity || indexById.containsKey(entityId)) {
            return -1;
        }
        int index = rows++;
        ids[index] = entityId;
        tokens[index] = nextToken++;
        worldEpochs[index] = worldEpoch;
        entityEpochs[index] = entityEpoch;
        hostTickVersions[index] = hostTickVersion;
        fingerprints[index] = fingerprint;
        // The worker owns its own copy: the captured state stays the record of what the plan point
        // saw, and a failed or late answer cannot corrupt a row the host may still run.
        TickState answer = new TickState();
        answer.copyFrom(state);
        captured[index] = state;
        answers[index] = answer;
        models[index] = model;
        states.set(index, PENDING);
        indexById.put(entityId, index);
        return index;
    }

    long lastToken() {
        return nextToken;
    }

    int indexOf(int entityId) {
        return indexById.get(entityId);
    }

    long token(int index) {
        return tokens[index];
    }

    long worldEpoch(int index) {
        return worldEpochs[index];
    }

    long entityEpoch(int index) {
        return entityEpochs[index];
    }

    int hostTickVersion(int index) {
        return hostTickVersions[index];
    }

    byte fingerprint(int index) {
        return fingerprints[index];
    }

    int state(int index) {
        return states.get(index);
    }

    /** The answer a settled worker published for one row; its state is read only after SETTLED. */
    TickState answer(int index) {
        return answers[index];
    }

    /** Whether the row still stands where the plan point froze it: the host wrote that position
     * into the previous-position fields right before this host entry. */
    boolean positionHolds(int index, double xo, double yo, double zo) {
        TickState state = captured[index];
        return state != null && state.x == xo && state.y == yo && state.z == zo;
    }

    boolean looked(int index) {
        return looked[index];
    }

    void markLooked(int index) {
        looked[index] = true;
    }

    /** Spends the token of a row the host entry committed and skipped. */
    void consume(int index) {
        states.set(index, CONSUMED);
    }

    /** Withdraws the token of a row so the host runs it. The published answer of that row is
     * never read again: the decision of one row of one tick happens at most once. */
    void revoke(int index) {
        states.set(index, REVOKED);
    }

    /** Withdraws the token of a row that never reached the host entry: the lease expires. */
    void recycle(int index) {
        states.set(index, REVOKED);
    }

    /** Rows whose token is neither spent nor withdrawn; zero means no lease outlived its tick. */
    int unresolved() {
        int open = 0;
        for (int index = 0; index < rows; index++) {
            int state = states.get(index);
            if (state != CONSUMED && state != REVOKED) {
                open++;
            }
        }
        return open;
    }

    long settledRows() {
        return settled.sum();
    }

    long lateDroppedRows() {
        return lateDropped.sum();
    }

    /** Runs the rows of this lease on the pool, in chunks, and closes the plan of the tick. */
    void publish(Executor executor, int chunks) {
        if (rows == 0) {
            return;
        }
        int count = Math.max(1, Math.min(chunks, rows));
        int span = (rows + count - 1) / count;
        for (int from = 0; from < rows; from += span) {
            int to = Math.min(rows, from + span);
            try {
                executor.execute(new Chunk(from, to));
            } catch (RuntimeException refused) {
                // A refused chunk is a row the host must run: the token is withdrawn, not waited on.
                for (int index = from; index < to; index++) {
                    states.compareAndSet(index, PENDING, FAILED);
                }
            }
        }
    }

    /** Marks the lease closed; a worker that answers after this is counted, never applied. */
    void close() {
        closed = true;
    }

    private void settle(int index) {
        if (closed) {
            lateDropped.increment();
            return;
        }
        if (states.compareAndSet(index, PENDING, SETTLED)) {
            settled.increment();
        } else {
            lateDropped.increment();
        }
    }

    private void fail(int index) {
        states.compareAndSet(index, PENDING, FAILED);
    }

    /** One range of rows of this lease, run on a pool thread. */
    private final class Chunk implements Runnable {

        private final int from;
        private final int to;

        private Chunk(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public void run() {
            for (int index = from; index < to; index++) {
                if (closed) {
                    return;
                }
                if (FaultInjection.ownershipFails()) {
                    fail(index);
                    continue;
                }
                long delay = FaultInjection.ownershipDelayNanos();
                if (delay > 0L) {
                    try {
                        Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                // The declared break answers with the captured state and never runs the model: it
                // exists so a harness can be shown to reject the answer of an owned row.
                WholeTickModel model = models[index];
                boolean answered;
                if (FaultInjection.ownershipBreaks() || model == null) {
                    answered = true;
                } else {
                    long startedAt = System.nanoTime();
                    answered = model.compute(answers[index]);
                    model.noteCompute(System.nanoTime() - startedAt);
                }
                if (answered) {
                    settle(index);
                } else {
                    fail(index);
                }
            }
        }
    }
}
