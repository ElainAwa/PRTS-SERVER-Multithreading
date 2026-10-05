/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/** Where a domain hands its declarations for the next plan. The intake has one declared capacity and
 * refuses past it: a declaration that does not fit is counted and answered with a code, so the queue
 * between a domain and the planning period can never grow without a bound somebody wrote down. */
public final class JobIntake {

    /** The answer a declaration receives. */
    public record Admission(boolean accepted, RejectCode code, int depth) {
    }

    private final IntSupplier capacity;
    private final List<JobDeclaration> pending = new ArrayList<>();
    private long submitted;
    private long refused;
    private long taken;
    private long takenBatches;

    public JobIntake(IntSupplier capacity) {
        this.capacity = capacity;
    }

    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

    /** Offers one declaration; a full intake refuses it instead of growing. */
    public synchronized Admission submit(JobDeclaration declaration) {
        if (declaration == null) {
            throw new IllegalArgumentException("an intake takes declarations");
        }
        if (pending.size() >= capacity()) {
            refused++;
            return new Admission(false, RejectCode.QUEUE_CAP_EXCEEDED, pending.size());
        }
        pending.add(declaration);
        submitted++;
        return new Admission(true, null, pending.size());
    }

    /** Takes everything offered since the last take and clears the intake. */
    public synchronized List<JobDeclaration> take() {
        List<JobDeclaration> batch = List.copyOf(pending);
        pending.clear();
        taken += batch.size();
        takenBatches++;
        return batch;
    }

    public synchronized int depth() {
        return pending.size();
    }

    public synchronized long submitted() {
        return submitted;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long taken() {
        return taken;
    }

    public synchronized long takenBatches() {
        return takenBatches;
    }

    public synchronized void reset() {
        pending.clear();
        submitted = 0L;
        refused = 0L;
        taken = 0L;
        takenBatches = 0L;
    }
}
