/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one marker row. Marker#tick is empty, so the ticks a takeover skips are
 * the level bookkeeping around the call - the previous position, the rotation lags and the tick
 * counter - which the host writes outside the call the host entry cancels. The model is therefore
 * the identity on the frozen row, and the controlled window is what shows that skipping the empty
 * call changes nothing. It refuses no row: an empty tick cannot depend on row state.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.LongAdder;

/** The whole-tick model of a marker. All entry points are the identity; none reads the world. */
public final class MarkerTick implements WholeTickModel {

    /** The one model instance of this class. */
    public static final WholeTickModel MODEL = new MarkerTick();

    private static final LongAdder[] REFUSAL_COUNTS = {new LongAdder()};
    private static final LongAdder COMPUTE_NANOS = new LongAdder();
    private static final LongAdder COMPUTE_ROWS = new LongAdder();
    private static final LongAdder APPLIED = new LongAdder();
    private static final String[] REFUSAL_NAMES = {"replicable"};

    private MarkerTick() {
    }

    @Override
    public String name() {
        return "marker";
    }

    @Override
    public int refusal(Entity entity) {
        return REPLICABLE;
    }

    @Override
    public void capture(Entity entity, TickState state) {
        state.clear();
        state.entityId = entity.getId();
        // The host entry rechecks the position the level wrote into the previous-position fields.
        state.x = entity.getX();
        state.y = entity.getY();
        state.z = entity.getZ();
    }

    @Override
    public boolean compute(TickState state) {
        return true;
    }

    @Override
    public void apply(Entity entity, TickState state) {
    }

    @Override
    public void commitHostSteps(Entity entity, TickState state) {
    }

    @Override
    public void noteCompute(long nanos) {
        COMPUTE_NANOS.add(nanos);
        COMPUTE_ROWS.increment();
    }

    @Override
    public long computeNanos() {
        return COMPUTE_NANOS.sum();
    }

    @Override
    public long computeRows() {
        return COMPUTE_ROWS.sum();
    }

    @Override
    public void noteApplied() {
        APPLIED.increment();
    }

    @Override
    public long appliedCount() {
        return APPLIED.sum();
    }

    @Override
    public void noteRefusal(int reason) {
        if (reason > 0 && reason < REFUSAL_COUNTS.length) {
            REFUSAL_COUNTS[reason].increment();
        }
    }

    @Override
    public LongAdder[] refusalCounts() {
        return REFUSAL_COUNTS;
    }

    @Override
    public String[] refusalNames() {
        return REFUSAL_NAMES.clone();
    }

    @Override
    public void reset() {
        for (LongAdder counter : REFUSAL_COUNTS) {
            counter.reset();
        }
        COMPUTE_NANOS.reset();
        COMPUTE_ROWS.reset();
        APPLIED.reset();
    }
}
