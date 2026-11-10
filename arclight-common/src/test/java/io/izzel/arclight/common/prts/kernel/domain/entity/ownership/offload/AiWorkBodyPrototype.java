/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;

/** WorkBody adapter: the worker entry runs on a job-scoped AiReadView; no live Level or world write. */
public final class AiWorkBodyPrototype implements WorkBody {

    @FunctionalInterface
    public interface AiEntry {
        long run(AiReadView view) throws RetryableFault, NonRetryableFault, CancelledFault;
    }

    private final AiReadView view;
    private final AiEntry entry;

    public AiWorkBodyPrototype(AiReadView view, AiEntry entry) {
        this.view = view;
        this.entry = entry;
    }

    @Override
    public long run(WorkBatch batch, ArenaScratch target, CancelToken token)
        throws RetryableFault, NonRetryableFault, CancelledFault {
        return entry.run(view);
    }
}
