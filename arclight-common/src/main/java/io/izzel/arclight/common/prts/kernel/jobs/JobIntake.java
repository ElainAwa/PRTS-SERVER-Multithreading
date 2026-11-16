/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/** Where a domain hands its declarations for the next plan. The intake has one declared capacity and
 * refuses past it: a declaration that does not fit is counted and answered with a code, so the queue
 * between a domain and the planning period can never grow without a bound somebody wrote down.
 *
 * <p>An intake may carry a work account. Every submit is then registered as an offered unit before
 * the capacity check, and a refusal closes that unit as rejected before effect with the same code it
 * answers the caller. A null account keeps the historical behaviour exactly. */
public final class JobIntake {

    /** The answer a declaration receives. */
    public record Admission(boolean accepted, RejectCode code, int depth) {
    }

    private final IntSupplier capacity;
    private final WorkAccount account;
    private final List<JobDeclaration> pending = new ArrayList<>();
    private long submitted;
    private long refused;
    private long taken;
    private long takenBatches;

    /** Opens an intake without a work account. */
    public JobIntake(IntSupplier capacity) {
        this(capacity, null);
    }

    /** Opens an intake that registers every offer in the given work account. */
    public JobIntake(IntSupplier capacity, WorkAccount account) {
        this.capacity = capacity;
        this.account = account;
    }

    /** The work account of this intake, or null when it carries none. */
    public WorkAccount account() {
        return account;
    }

    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

    /** Offers one declaration; a full intake refuses it instead of growing. */
    public synchronized Admission submit(JobDeclaration declaration) {
        if (declaration == null) {
            throw new IllegalArgumentException("an intake takes declarations");
        }
        String unitId = WorkAccount.unitId(declaration.key(),
            declaration.logicalUpdateOrdinal());
        boolean registered = account != null
            && account.offer(unitId, declaration.key(), declaration.worldId(),
                declaration.domainId());
        if (pending.size() >= capacity()) {
            refused++;
            if (registered) {
                account.close(unitId, WorkAccount.Outcome.REJECTED_BEFORE_EFFECT,
                    RejectCode.QUEUE_CAP_EXCEEDED.text());
            }
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

    /** Clears the intake counters and queue. The carried work account is a window ledger and is not
     * cleared here; its units keep their offered, seated and closed identities. */
    public synchronized void reset() {
        pending.clear();
        submitted = 0L;
        refused = 0L;
        taken = 0L;
        takenBatches = 0L;
    }
}
