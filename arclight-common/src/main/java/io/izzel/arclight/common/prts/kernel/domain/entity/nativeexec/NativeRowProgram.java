/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.LiveRowSnapshot;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.ReadPermit;

/** One row's read slot in one wave: the frozen identity and permit it was issued, the single read
 * node it runs, and the slot the result or the refusal lands in. Another row never reads a slot, and
 * a slot that produced nothing is refused at wave close rather than silently skipped. */
public final class NativeRowProgram<R> {

    /** Where the slot stands; only READY hands a result to the write pump. */
    public enum State {
        OPEN,
        READY,
        REFUSED
    }

    /** Why a slot produced no result; every value stands for the row running its whole-body C form. */
    public enum Refusal {
        PERMIT_WAVE_CLOSED,
        PERMIT_EXPIRED,
        PERMIT_IDENTITY,
        PERMIT_GENERATION,
        PERMIT_VERSION,
        PERMIT_WRITER,
        PERMIT_FOREIGN_WRITE,
        PERMIT_GROUP,
        GUARD_WRITE,
        GUARD_EVENT,
        GUARD_HOST_CALLBACK,
        GUARD_DRAW,
        GUARD_CHUNK_DEMAND,
        DISPATCH_REFUSED,
        ACTION_FAILED,
        NOT_RUN
    }

    private final String rowKey;
    private final int seat;
    private final EntityRowIdentity identity;
    private final long objectGeneration;
    private final LiveRowSnapshot snapshot;
    private final ReadPermit permit;
    private NativeReadWave.NativeRead<R> action;
    private State state = State.OPEN;
    private Refusal refusal;
    private R result;
    private Throwable failure;

    NativeRowProgram(String rowKey, int seat, EntityRowIdentity identity, long objectGeneration,
                     LiveRowSnapshot snapshot, ReadPermit permit,
                     NativeReadWave.NativeRead<R> action) {
        this.rowKey = rowKey;
        this.seat = seat;
        this.identity = identity;
        this.objectGeneration = objectGeneration;
        this.snapshot = snapshot;
        this.permit = permit;
        this.action = action;
    }

    public String rowKey() {
        return rowKey;
    }

    public int seat() {
        return seat;
    }

    public EntityRowIdentity identity() {
        return identity;
    }

    public long objectGeneration() {
        return objectGeneration;
    }

    public LiveRowSnapshot snapshot() {
        return snapshot;
    }

    public ReadPermit permit() {
        return permit;
    }

    public State state() {
        return state;
    }

    /** The refusal that kept the result from the pump, or null while the slot is open or ready. */
    public Refusal refusal() {
        return refusal;
    }

    /** The read node's output; meaningful only when the state is READY, null is a real output. */
    public R result() {
        return result;
    }

    /** The failure the read node raised, if it raised one; diagnostic only. */
    public Throwable failure() {
        return failure;
    }

    NativeReadWave.NativeRead<R> action() {
        return action;
    }

    void stage(R value) {
        if (state != State.OPEN) {
            return;
        }
        this.result = value;
        this.state = State.READY;
    }

    void refuse(Refusal reason) {
        if (state == State.REFUSED) {
            return;
        }
        // A refusal decided after the node returned (a fenced access it swallowed) replaces the
        // staged value: the pump must not apply a result the wave no longer stands behind.
        this.refusal = reason;
        this.result = null;
        this.state = State.REFUSED;
    }

    void failure(Throwable cause) {
        this.failure = cause;
    }

    void clearAction() {
        this.action = null;
    }
}
