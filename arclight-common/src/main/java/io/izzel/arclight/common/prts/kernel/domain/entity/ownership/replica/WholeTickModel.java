/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * One whole-tick model of one entity class: the predicate that says whether the model can answer
 * for a row, the state capture the plan point takes on the tick thread, the pure transition the
 * worker runs on that copy, the write-back the host entry commits, and the steps whose input is
 * the row's own random stream or the level clock, which only the host entry can take.
 *
 * A model answers for a row or refuses it; it never approximates. Refusal reasons are counted so
 * the evidence line can say why a class the predicate offered did not enter the model.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import net.minecraft.world.entity.Entity;
import java.util.concurrent.atomic.LongAdder;

public interface WholeTickModel {

    /** The row's whole tick is reproduced by this model. */
    int REPLICABLE = 0;

    /** A stable name for the evidence line. */
    String name();

    /** The first condition that keeps this row out of the model, or {@link #REPLICABLE}. */
    int refusal(Entity entity);

    /** Whether the row is still one this model covers, judged without a world query; the host
     * entry rechecks it between the plan point and the tick, and a false sends the row to the host. */
    default boolean retains(Entity entity) {
        return true;
    }

    /** Reads every field the model needs; the caller runs on the tick thread and owns the row. */
    void capture(Entity entity, TickState state);

    /** The whole tick as pure arithmetic on the captured state; false means the row stays with
     * the host and the host entry runs the original tick. */
    boolean compute(TickState state);

    /** Writes the answer back onto the row; the host entry runs it on the tick thread. */
    void apply(Entity entity, TickState state);

    /** The part of the tick whose input is the row's own random stream, the level clock or a
     * field the model cannot hold; the host entry runs it on the tick thread, exactly once. */
    void commitHostSteps(Entity entity, TickState state);

    /** Records the cost of one worker answer; observation only, the wait is spent outside it. */
    void noteCompute(long nanos);

    /** Nanoseconds the workers spent in this model. */
    long computeNanos();

    /** Rows the workers answered with this model. */
    long computeRows();

    /** Counts one answer the host entry committed onto its row. */
    void noteApplied();

    /** Answers the host entry committed onto their rows. */
    long appliedCount();

    /** Counts one refusal by the row that was offered. */
    void noteRefusal(int reason);

    /** The refusal counters, indexed by the reasons the model declares. */
    LongAdder[] refusalCounts();

    /** The names of the refusal reasons, indexed the same way. */
    String[] refusalNames();

    /** Clears the refusal counters; the readout reset uses it. */
    void reset();
}
