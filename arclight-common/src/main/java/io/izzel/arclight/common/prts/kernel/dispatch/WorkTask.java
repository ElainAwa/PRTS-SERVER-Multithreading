/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

/**
 * One frozen unit of work: a contiguous entity range of one region of one world.
 *
 * <p>A task is created at planning time and never changes afterwards. The region is the scheduling
 * domain: one region of one world is held by at most one worker in a tick, which is what makes the
 * tasks of a tick independent of each other. The snapshot reference and the world generation travel
 * with the task so the merge can refuse a result that was computed against an older world.</p>
 *
 * @param taskId        identity of the task inside its plan
 * @param worldId       the world the task reads
 * @param regionId      the region of that world, the scheduling domain
 * @param batchId       identity of the batch the task is dispatched as
 * @param entitySeqStart first entity index of the range, inclusive
 * @param entitySeqEnd   last entity index of the range, exclusive
 * @param snapshotRef   identity of the snapshot the range was frozen from
 * @param worldEpoch    generation of the world at freezing time
 * @param shareMsHint   planning hint of the time the task may take, never a measurement
 * @param cancelScope   scope a cancellation of this task is reported under
 */
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

    /** @return how many entities the task covers */
    public int entityCount() {
        return entitySeqEnd - entitySeqStart;
    }
}
