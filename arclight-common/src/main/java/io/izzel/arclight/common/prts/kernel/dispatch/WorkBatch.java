/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

/**
 * The dispatched form of one task: the frozen task, the view it reads and the epoch it belongs to.
 *
 * <p>The batch epoch is the identity a late result is judged by. A worker that finishes after the
 * merge of its tick gave up carries an older epoch, and the merge or the slot refuses it instead of
 * letting it land in a later frame. The view is shared by all batches of a tick and is immutable, so
 * a worker receives values rather than world objects.</p>
 *
 * @param batchId    identity of the batch
 * @param task       the frozen task
 * @param batchEpoch the dispatch epoch the batch was created in
 * @param view       the read-only entity view the task reads
 */
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

    /** @return the entity range start of the task */
    public int rangeStart() {
        return task.entitySeqStart();
    }

    /** @return the entity range end of the task, exclusive */
    public int rangeEnd() {
        return task.entitySeqEnd();
    }
}
