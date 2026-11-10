/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;

/** Internal AI commit seat: plan sequence/position/world/domain/site, plus the Batch shape. */
public final class AiCommitSeat {

    public record Seat(long planSequence, int position, String worldId, String domainId,
                       String nodeKey, String site) {
        public Seat {
            if (planSequence < 0L || position < 0 || worldId == null || domainId == null
                || nodeKey == null || site == null) {
                throw new IllegalArgumentException("invalid AI seat");
            }
        }
    }

    private AiCommitSeat() {
    }

    public static Seat of(JobDeclaration declaration, long planSequence, int position) {
        if (declaration == null) throw new IllegalArgumentException("declaration is required");
        return new Seat(planSequence, position, declaration.worldId(), declaration.domainId(),
            declaration.key(), "ai.job");
    }

    public static CommitLog.Batch batch(Seat seat, long expectedVersion, long writeSetDigest,
                                       int rows) {
        if (seat == null) throw new IllegalArgumentException("seat is required");
        return new CommitLog.Batch(seat.worldId(), seat.domainId(), seat.nodeKey(),
            CommitLog.Batch.Kind.APPLY, expectedVersion, writeSetDigest, rows);
    }
}
