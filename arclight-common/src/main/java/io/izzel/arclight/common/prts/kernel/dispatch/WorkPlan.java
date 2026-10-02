/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

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
