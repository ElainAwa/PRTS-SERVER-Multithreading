/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * The frozen task set of one tick.
 *
 * <p>Planning reads the entity views and the plan epoch only. It never reads a clock, never starts
 * a thread and never decides again later: the task order published here is the order the merge walks
 * and the order a hash folds, so the same input always produces the same plan.</p>
 *
 * <p>The plan carries the tick whose commit segment will merge it. A plan built for tick n is
 * merged at the entry of tick n + 1, which is the hard deadline of the batch: whatever has not
 * produced a result by then is cancelled and done on the tick thread instead.</p>
 *
 * @param tickIndex        the tick the tasks were planned in
 * @param planEpoch        the dispatch epoch the plan belongs to
 * @param views            the entity views the plan was cut from, in world order
 * @param tasks            the frozen tasks, in merge order
 * @param hardDeadlineTick the tick whose entry is the hard deadline of every batch
 */
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

    /** @return how many tasks the plan carries */
    public int taskCount() {
        return tasks.size();
    }

    /** @return whether the plan has no work at all */
    public boolean empty() {
        return tasks.isEmpty();
    }

    /**
     * Freezes the plan of one tick from the entity views.
     *
     * <p>Each view is grouped by region first, so the rows of one region are contiguous and a task
     * is exactly one region. The same view always yields the same tasks in the same order: planning
     * reads no clock and touches no thread.</p>
     *
     * @param tickIndex   the tick the plan is built in
     * @param planEpoch   the dispatch epoch the plan belongs to
     * @param views       the entity views of the tick, in world order
     * @param batchChunks how many chunks one region covers on a side
     * @param firstTaskId the first task identity to hand out; identities never repeat across
     *                    ticks, which is what makes a second commit of one identity detectable
     * @return the frozen plan
     */
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
}
