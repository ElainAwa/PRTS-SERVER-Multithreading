/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import java.util.concurrent.atomic.LongAdder;

/** The additive evidence counters of the enabled native entity path. The server thread adds one
 * batch at a time, any reader samples the running totals, and the server stop prints the sample
 * once so a short window is never silent. Nothing here runs while the native property is off. */
final class NativeEntityEvidence {

    /** One batch's counters before they join the running totals: a row without a frozen plan is a
     * plan refusal, and every wave refusal falls into fence, dispatch, not-run or failed. */
    record Batch(long tick, int rows, int planned, int ready, int refusedPlan, int refusedFence,
                 int refusedDispatch, int refusedNotRun, int refusedFailed, int foreign,
                 long readNanos, int pumped, long pumpNanos, long planNanos, boolean faulted) {
    }

    /** One immutable sample of the running totals; the hit count comes from the staged path stage. */
    record Sample(long tick, long waves, long rows, long planned, long ready, long refusedPlan,
                  long refusedFence, long refusedDispatch, long refusedNotRun, long refusedFailed,
                  long hit, long readNanos, long pumped, long pumpNanos, long planNanos,
                  long foreign, long faultedBatches) {

        long refused() {
            return refusedPlan + refusedFence + refusedDispatch + refusedNotRun + refusedFailed;
        }

        /** The per-batch evidence line: the requested fields first, then the batch and total detail. */
        String line(boolean faulted, String waveRefusals) {
            return "[PRTS] native-entity planned=" + planned + " ready=" + ready
                + " refused=" + refused() + " hit=" + hit + " rNanos=" + readNanos
                + " pumped=" + pumped + " pumpNanos=" + pumpNanos + " tick=" + tick
                + " waves=" + waves + " rows=" + rows + " refusedPlan=" + refusedPlan
                + " refusedFence=" + refusedFence + " refusedDispatch=" + refusedDispatch
                + " refusedNotRun=" + refusedNotRun + " refusedFailed=" + refusedFailed
                + " foreign=" + foreign + " planNanos=" + planNanos
                + " faulted=" + faulted + " faultedBatches=" + faultedBatches
                + " waveRefusals=" + waveRefusals;
        }

        /** The one stop summary: the same totals with a marker so it is never read as a batch. */
        String summary() {
            return "[PRTS] native-entity summary tick=" + tick + " waves=" + waves
                + " rows=" + rows + " planned=" + planned + " ready=" + ready
                + " refused=" + refused() + " hit=" + hit + " rNanos=" + readNanos
                + " pumped=" + pumped + " pumpNanos=" + pumpNanos + " refusedPlan=" + refusedPlan
                + " refusedFence=" + refusedFence + " refusedDispatch=" + refusedDispatch
                + " refusedNotRun=" + refusedNotRun + " refusedFailed=" + refusedFailed
                + " foreign=" + foreign + " planNanos=" + planNanos
                + " faultedBatches=" + faultedBatches;
        }
    }

    private final LongAdder waves = new LongAdder();
    private final LongAdder rows = new LongAdder();
    private final LongAdder planned = new LongAdder();
    private final LongAdder ready = new LongAdder();
    private final LongAdder refusedPlan = new LongAdder();
    private final LongAdder refusedFence = new LongAdder();
    private final LongAdder refusedDispatch = new LongAdder();
    private final LongAdder refusedNotRun = new LongAdder();
    private final LongAdder refusedFailed = new LongAdder();
    private final LongAdder readNanos = new LongAdder();
    private final LongAdder pumped = new LongAdder();
    private final LongAdder pumpNanos = new LongAdder();
    private final LongAdder planNanos = new LongAdder();
    private final LongAdder foreign = new LongAdder();
    private final LongAdder faultedBatches = new LongAdder();
    /** The tick of the last added batch; written by the server thread, read by the stop summary. */
    private volatile long lastTick;

    void add(Batch batch) {
        lastTick = batch.tick();
        waves.increment();
        rows.add(batch.rows());
        planned.add(batch.planned());
        ready.add(batch.ready());
        refusedPlan.add(batch.refusedPlan());
        refusedFence.add(batch.refusedFence());
        refusedDispatch.add(batch.refusedDispatch());
        refusedNotRun.add(batch.refusedNotRun());
        refusedFailed.add(batch.refusedFailed());
        readNanos.add(batch.readNanos());
        pumped.add(batch.pumped());
        pumpNanos.add(batch.pumpNanos());
        planNanos.add(batch.planNanos());
        foreign.add(batch.foreign());
        if (batch.faulted()) {
            faultedBatches.increment();
        }
    }

    Sample sample(long hit) {
        return new Sample(lastTick, waves.sum(), rows.sum(), planned.sum(), ready.sum(),
            refusedPlan.sum(), refusedFence.sum(), refusedDispatch.sum(), refusedNotRun.sum(),
            refusedFailed.sum(), hit, readNanos.sum(), pumped.sum(), pumpNanos.sum(),
            planNanos.sum(), foreign.sum(), faultedBatches.sum());
    }
}
