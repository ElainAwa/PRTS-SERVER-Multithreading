/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 JobGraph nodes for AI batches: read/write domains, true predecessors, batch/region identity. */
class AiJobGraphPrototypeTest {

    @Test
    void aiBatchesFreezeAsP0JobGraphNodes() {
        List<JobDeclaration> declarations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<String> predecessors = i == 0 ? List.of() : List.of("ai-" + (i - 1));
            JobDeclaration.DomainRef read = new JobDeclaration.DomainRef("world", "entity", 0);
            JobDeclaration.DomainRef write = new JobDeclaration.DomainRef("world", "entity", 0);
            declarations.add(new JobDeclaration(
                "ai-" + i, i, "world", "ai", 0, predecessors, 0,
                "region-0", "batch-" + (i / 8), List.of(read), List.of(write),
                ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8));
        }
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(declarations, 1L, 1L, 1L, 64);
        assertTrue(freeze.ok(), "AI job graph must freeze: " + freeze.code());
        assertEquals(8, freeze.graph().nodes().size());
        assertTrue(freeze.graph().nodes().stream().allMatch(n -> n.domainId().equals("ai")));
        assertTrue(freeze.graph().nodes().stream().allMatch(n -> n.shareClass() == ShareClass.AI));
    }
}
