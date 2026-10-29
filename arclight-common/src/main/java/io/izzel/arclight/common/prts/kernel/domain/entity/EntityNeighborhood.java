/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;

import java.util.ArrayList;
import java.util.List;

/** The offline entity neighbourhood query. A query names one frozen batch; its candidates are the
 * rows of that batch only, so a row of another batch or another world is refused rather than
 * silently merged. The view is read-only and opens no ownership entry. */
public final class EntityNeighborhood {

    /** Why a query was answered or refused. */
    public enum Status {
        IN_BATCH,
        CROSS_BATCH,
        CROSS_WORLD,
        EMPTY_BATCH
    }

    /** One row of the query's own batch. */
    public record Candidate(long entitySeq, int chunkX, int chunkZ, double x, double y, double z) {
    }

    /** The answer: the batch it stayed inside, the rows it may read and the refusal reason. */
    public record Result(Status status, long batchId, String worldId, String regionId,
                         List<Candidate> candidates) {
        public Result {
            candidates = List.copyOf(candidates);
        }

        public boolean accepted() {
            return status == Status.IN_BATCH;
        }
    }

    private EntityNeighborhood() {
    }

    /** Queries the rows within {@code radiusChunks} of one row's chunk, restricted to its batch. */
    public static Result aroundRow(DispatchPass pass, long batchId, String worldId,
                                   long entitySeq, int radiusChunks) {
        if (pass == null) {
            throw new IllegalArgumentException("a neighbourhood query needs a pass");
        }
        DispatchPass.Entry entry = null;
        for (DispatchPass.Entry candidate : pass.entries()) {
            if (candidate.batch().batchId() == batchId) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            return new Result(Status.CROSS_BATCH, batchId, worldId, "-", List.of());
        }
        WorkPlan.WorkTask task = entry.batch().task();
        if (worldId == null || !worldId.equals(task.worldId())) {
            return new Result(Status.CROSS_WORLD, batchId, worldId, task.regionId(), List.of());
        }
        EntityCandidateView view = entry.view();
        int row = -1;
        for (int index = task.entitySeqStart(); index < task.entitySeqEnd(); index++) {
            if (view.entitySeq(index) == entitySeq) {
                row = index;
                break;
            }
        }
        if (row < 0) {
            return new Result(Status.CROSS_BATCH, batchId, worldId, task.regionId(), List.of());
        }
        int centerX = view.chunkX(row);
        int centerZ = view.chunkZ(row);
        int radius = Math.max(0, radiusChunks);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = task.entitySeqStart(); index < task.entitySeqEnd(); index++) {
            int dx = Math.abs(view.chunkX(index) - centerX);
            int dz = Math.abs(view.chunkZ(index) - centerZ);
            if (Math.max(dx, dz) > radius) {
                continue;
            }
            candidates.add(new Candidate(view.entitySeq(index), view.chunkX(index),
                view.chunkZ(index), view.posX(index), view.posY(index), view.posZ(index)));
        }
        if (candidates.isEmpty()) {
            return new Result(Status.EMPTY_BATCH, batchId, worldId, task.regionId(), List.of());
        }
        return new Result(Status.IN_BATCH, batchId, worldId, task.regionId(),
            List.copyOf(candidates));
    }

    /** Queries a batch by its task range without a row identity; used to prove the batch edge. */
    public static Result aroundBatch(DispatchPass pass, long batchId, int centerX, int centerZ,
                                     int radiusChunks) {
        for (DispatchPass.Entry entry : pass.entries()) {
            if (entry.batch().batchId() != batchId) {
                continue;
            }
            WorkPlan.WorkTask task = entry.batch().task();
            EntityCandidateView view = entry.view();
            int radius = Math.max(0, radiusChunks);
            List<Candidate> candidates = new ArrayList<>();
            for (int index = task.entitySeqStart(); index < task.entitySeqEnd(); index++) {
                if (Math.max(Math.abs(view.chunkX(index) - centerX),
                    Math.abs(view.chunkZ(index) - centerZ)) > radius) {
                    continue;
                }
                candidates.add(new Candidate(view.entitySeq(index), view.chunkX(index),
                    view.chunkZ(index), view.posX(index), view.posY(index), view.posZ(index)));
            }
            Status status = candidates.isEmpty() ? Status.EMPTY_BATCH : Status.IN_BATCH;
            return new Result(status, batchId, task.worldId(), task.regionId(),
                List.copyOf(candidates));
        }
        return new Result(Status.CROSS_BATCH, batchId, "-", "-", List.of());
    }
}
