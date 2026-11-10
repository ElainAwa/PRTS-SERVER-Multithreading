/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;

/** Pure AI computation: nearest visible target row from the arena read-set. */
public final class AiWorkBody implements WorkBody {

    @Override
    public long run(WorkPlan.WorkBatch batch, ArenaScratch target, CancelToken token)
        throws RetryableFault, NonRetryableFault, CancelledFault {
        if (token.cancelled()) throw new CancelledFault();
        int count = target.filled();
        int nearest = -1;
        double best = Double.MAX_VALUE;
        for (int index = 0; index < count; index++) {
            long flag = target.flags(index);
            if (flag < 100L || flag > 100L + AiArenaPayload.Kind.values().length) continue;
            if (flag - 100L != AiArenaPayload.Kind.TARGET.ordinal() + 1L) continue;
            double dx = target.posX(index);
            double dy = target.posY(index);
            double dz = target.posZ(index);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                nearest = index;
            }
        }
        return nearest < 0 ? 0L : (long) (target.posX(nearest) * 31 + target.posY(nearest) * 17
            + target.posZ(nearest));
    }
}
