/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.ArrayList;
import java.util.List;

/** The frozen task set of one tick. Planning reads the entity views and the plan epoch only. */
public record WorkPlan(long tickIndex, long planEpoch, List<EntityCandidateView> views,
                       List<WorkTask> tasks, long hardDeadlineTick) {

    /** Validates the plan identity and freezes the lists. */
    public WorkPlan {
        if (tickIndex < 0) {
            throw new IllegalArgumentException("a plan needs a tick");
        }
        if (hardDeadlineTick <= tickIndex) {
            throw new IllegalArgumentException("the deadline must be after the planning tick");
        }
        views = List.copyOf(views);
        tasks = List.copyOf(tasks);
    }

    public int taskCount() {
        return tasks.size();
    }

    public boolean empty() {
        return tasks.isEmpty();
    }

    /** One actual adjacent merge in the entity scope: the rows keep their order and only the
     * task boundary moves. */
    public record MergeReceipt(String worldId, int mergedTasks, int entities, long firstBatchId,
                              long lastBatchId) {
    }

    /** The merged plan and the receipts of the merges that actually happened. */
    public record MergeResult(WorkPlan plan, List<MergeReceipt> receipts) {
    }

    /** Merges adjacent tasks of the same world whose ranges touch, bounded by the entity count
     * the caller allows in one merged task. Views and row order are untouched. */
    public MergeResult mergeAdjacent(int maxEntities) {
        int cap = Math.max(1, maxEntities);
        List<WorkTask> merged = new ArrayList<>(tasks.size());
        List<MergeReceipt> receipts = new ArrayList<>();
        for (WorkTask task : tasks) {
            if (!merged.isEmpty()) {
                WorkTask previous = merged.get(merged.size() - 1);
                if (previous.worldId().equals(task.worldId())
                    && previous.entitySeqEnd() == task.entitySeqStart()
                    && previous.entityCount() + task.entityCount() <= cap) {
                    // The merged task carries the sum of the shares and both cancellation
                    // scopes; the '+' names the composed region, never a fresh identity.
                    WorkTask combined = new WorkTask(previous.taskId(), previous.worldId(),
                        previous.regionId() + "+" + task.regionId(), previous.batchId(),
                        previous.entitySeqStart(), task.entitySeqEnd(), previous.snapshotRef(),
                        previous.worldEpoch(), previous.shareMsHint() + task.shareMsHint(),
                        previous.cancelScope() + "+" + task.cancelScope());
                    merged.set(merged.size() - 1, combined);
                    receipts.add(new MergeReceipt(combined.worldId(), 2, combined.entityCount(),
                        previous.batchId(), task.batchId()));
                    continue;
                }
            }
            merged.add(task);
        }
        if (receipts.isEmpty()) {
            return new MergeResult(this, List.of());
        }
        return new MergeResult(new WorkPlan(tickIndex, planEpoch, views, merged, hardDeadlineTick),
            List.copyOf(receipts));
    }

    /** Freezes the plan of one tick from the entity views. Each view is grouped by region first,
     * so the rows of one region are contiguous and a task is exactly one region. */
    public static WorkPlan freeze(long tickIndex, long planEpoch, List<EntityCandidateView> views,
                                  int batchChunks, long firstTaskId) {
        List<EntityCandidateView> sortedViews = new ArrayList<>(views.size());
        List<WorkTask> tasks = new ArrayList<>();
        long nextTaskId = Math.max(1L, firstTaskId);
        for (EntityCandidateView view : views) {
            EntityCandidateView sorted = view.sortedByRegion(batchChunks);
            sortedViews.add(sorted);
            int count = sorted.count();
            int runStart = 0;
            while (runStart < count) {
                String region = EntityCandidateView.regionKey(sorted.chunkX(runStart),
                    sorted.chunkZ(runStart), batchChunks);
                int runEnd = runStart + 1;
                while (runEnd < count) {
                    String next = EntityCandidateView.regionKey(sorted.chunkX(runEnd),
                        sorted.chunkZ(runEnd), batchChunks);
                    if (!region.equals(next)) {
                        break;
                    }
                    runEnd++;
                }
                tasks.add(new WorkTask(nextTaskId, sorted.worldId(), region, nextTaskId,
                    runStart, runEnd, planEpoch, sorted.worldEpoch(), 0, region));
                nextTaskId++;
                runStart = runEnd;
            }
        }
        return new WorkPlan(tickIndex, planEpoch, sortedViews, tasks, tickIndex + 1);
    }

    /** The batch epoch is the identity a late result is judged by. */
    public record WorkBatch(long batchId, WorkTask task, long batchEpoch, EntityCandidateView view) {

        /** Validates the batch identity. */
        public WorkBatch {
            if (task == null || view == null) {
                throw new IllegalArgumentException("a batch needs a task and a view");
            }
            if (batchId != task.batchId()) {
                throw new IllegalArgumentException("a batch must carry the identity of its task");
            }
        }

        public int rangeStart() {
            return task.entitySeqStart();
        }

        public int rangeEnd() {
            return task.entitySeqEnd();
        }
    }

    /** A task is created at planning time and never changes afterwards. */
    public record WorkTask(long taskId, String worldId, String regionId, long batchId,
                           int entitySeqStart, int entitySeqEnd, long snapshotRef, long worldEpoch,
                           int shareMsHint, String cancelScope) {

        /** Validates the identity and the range of one task. */
        public WorkTask {
            if (worldId == null || worldId.isEmpty()) {
                throw new IllegalArgumentException("a task needs a world");
            }
            if (regionId == null || regionId.isEmpty()) {
                throw new IllegalArgumentException("a task needs a region");
            }
            if (cancelScope == null || cancelScope.isEmpty()) {
                throw new IllegalArgumentException("a task needs a cancellation scope");
            }
            if (entitySeqStart < 0 || entitySeqEnd <= entitySeqStart) {
                throw new IllegalArgumentException("a task needs a non-empty entity range");
            }
        }

        public int entityCount() {
            return entitySeqEnd - entitySeqStart;
        }
    }
}
