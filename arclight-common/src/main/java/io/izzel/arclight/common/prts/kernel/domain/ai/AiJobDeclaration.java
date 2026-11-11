/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.List;

/** Builds the internal JobDeclaration of one AI actor: read/write ai domain, AI share, owner demand. */
public final class AiJobDeclaration {

    private AiJobDeclaration() {
    }

    public static JobDeclaration of(String worldId, String actorKey, int batchBound) {
        return of(worldId, actorKey, batchBound, 1L);
    }

    /** The same declaration with an explicit handle, for a multi-actor batch graph. */
    public static JobDeclaration of(String worldId, String actorKey, int batchBound, long handle) {
        if (worldId == null || actorKey == null) {
            throw new IllegalArgumentException("AI job needs world and actor");
        }
        JobDeclaration.DomainRef read = new JobDeclaration.DomainRef(worldId, "ai", 0);
        JobDeclaration.DomainRef write = new JobDeclaration.DomainRef(worldId, "ai", 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(worldId, WriteLevel.REGION,
            "ai", HolderKind.REGISTERED, "ai.owner", 1);
        return new JobDeclaration("ai/" + actorKey, handle, worldId, "ai", 0, List.of(), 0,
            "ai", "ai", List.of(read), List.of(write), ShareClass.AI,
            JobDeclaration.SiteClass.PARALLEL, 0, batchBound, List.of(demand), null);
    }
}
