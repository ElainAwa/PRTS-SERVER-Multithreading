/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import net.minecraft.world.entity.Entity;
import java.util.concurrent.atomic.LongAdder;

/** Internal, package-private AI lease/commit bridge; entity path is untouched. */
final class AiOwnershipBridge {

    static final int COMMITTED = 1;
    static final int STALE = 2;
    static final int FAILED = 3;

    private AiOwnershipBridge() {
    }

    static int commit(long actorId, long targetId, long expectedVersion, long worldEpoch) {
        if (actorId < 0L || targetId < 0L || expectedVersion <= 0L || worldEpoch <= 0L) {
            return STALE;
        }
        OwnershipLease lease = new OwnershipLease(0L, 1, 1L);
        AiRowModel model = new AiRowModel(targetId);
        TickState state = new TickState();
        state.entityId = (int) actorId;
        OwnershipLease.EntityCapability capability = new OwnershipLease.EntityCapability(
            (int) actorId, expectedVersion, worldEpoch, 0, true, true, (byte) 0, 0, 0, 0, 0, 0);
        lease.issue(capability, model, state);
        lease.publish(Runnable::run, 1);
        int index = lease.indexOf((int) actorId);
        if (index < 0 || lease.state(index) != OwnershipLease.SETTLED) {
            return FAILED;
        }
        try {
            model.apply(null, lease.answer(index));
            model.noteApplied();
            lease.consume(index);
            return COMMITTED;
        } catch (Throwable t) {
            return FAILED;
        }
    }

    private static final class AiRowModel implements WholeTickModel {
        private final long targetId;
        private final LongAdder applied = new LongAdder();
        private final LongAdder computeRows = new LongAdder();
        private volatile long computeNanos;

        private AiRowModel(long targetId) { this.targetId = targetId; }

        public String name() { return "ai-target"; }
        public int refusal(Entity entity) { return REPLICABLE; }
        public void capture(Entity entity, TickState state) { }
        public boolean compute(TickState state) { computeRows.increment(); return true; }
        public void apply(Entity entity, TickState state) { }
        public void commitHostSteps(Entity entity, TickState state) { }
        public void noteCompute(long nanos) { computeNanos += nanos; }
        public long computeNanos() { return computeNanos; }
        public long computeRows() { return computeRows.sum(); }
        public void noteApplied() { applied.increment(); }
        public long appliedCount() { return applied.sum(); }
        public void noteRefusal(int reason) { }
        public LongAdder[] refusalCounts() { return new LongAdder[0]; }
        public String[] refusalNames() { return new String[0]; }
        public void reset() { applied.reset(); computeRows.reset(); computeNanos = 0L; }

        @SuppressWarnings("unused")
        private long targetId() { return targetId; }
    }
}
