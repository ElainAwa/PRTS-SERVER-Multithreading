/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only AI scaffold: frozen read-set, deterministic compute, host commit seam, fallback. */
class MobAiTickModelTest {

    private MobAiTickModel model;

    @BeforeEach
    void setUp() {
        model = new MobAiTickModel();
    }

    private static MobAiTickModel.ReadSet readSet(boolean valid, String behavior) {
        return new MobAiTickModel.ReadSet(77L, List.of("door=closed"), List.of("villager#2"),
            List.of("poi#3"), List.of("nearest=1"), List.of(behavior), "stroll", valid);
    }

    @Test
    void frozenReadSetComputesDeterministicallyAndCommitsOnce() {
        TickState state = new TickState();
        state.entityId = 1;
        model.captureReadSet(readSet(true, "stroll"), state);
        assertTrue(model.compute(state));
        assertEquals(1L, model.computeRows());
        assertTrue(model.computeNanos() >= 0L);
        model.apply(null, state);
        model.noteApplied();
        assertEquals(1L, model.appliedCount());
    }

    @Test
    void invalidReadSetFallsBackWithoutAnAnswer() {
        TickState state = new TickState();
        model.captureReadSet(readSet(false, "stroll"), state);
        assertFalse(model.compute(state));
        model.apply(null, state);
        assertEquals(0L, model.appliedCount());
    }

    @Test
    void changedReadSetFallsBack() {
        TickState state = new TickState();
        model.captureReadSet(readSet(true, "stroll"), state);
        model.invalidate(state);
        assertFalse(model.compute(state));
        assertEquals(0L, model.computeRows());
    }

    @Test
    void unknownBehaviorFallsBack() {
        TickState state = new TickState();
        model.captureReadSet(readSet(true, "unsupported"), state);
        assertFalse(model.compute(state));
        assertEquals(0L, model.computeRows());
    }

    @Test
    void oldIncompleteModelWouldAcceptStaleSet() {
        WholeTickModel old = new IncompleteOldModel();
        TickState state = new TickState();
        model.captureReadSet(readSet(true, "stroll"), state);
        model.invalidate(state);
        assertFalse(model.compute(state), "new model must fall back on stale read-set");
        assertTrue(old.compute(state), "old-red: incomplete model incorrectly accepts stale set");
    }

    static final class IncompleteOldModel implements WholeTickModel {
        @Override public String name() { return "old_ai_incomplete"; }
        @Override public int refusal(net.minecraft.world.entity.Entity entity) { return REPLICABLE; }
        @Override public void capture(net.minecraft.world.entity.Entity entity, TickState state) { }
        @Override public boolean compute(TickState state) { return true; }
        @Override public void apply(net.minecraft.world.entity.Entity entity, TickState state) { }
        @Override public void commitHostSteps(net.minecraft.world.entity.Entity entity, TickState state) { }
        @Override public void noteCompute(long nanos) { }
        @Override public long computeNanos() { return 0L; }
        @Override public long computeRows() { return 0L; }
        @Override public void noteApplied() { }
        @Override public long appliedCount() { return 0L; }
        @Override public void noteRefusal(int reason) { }
        @Override public LongAdder[] refusalCounts() { return new LongAdder[0]; }
        @Override public String[] refusalNames() { return new String[0]; }
        @Override public void reset() { }
    }
}
