/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contract the run-scoped unbudgeted rebuild counter adds to the readout. The test resolves
 * the counter and the readout by name, so on a tree without them the same test states what is
 * missing: no counter class and no recompute.unbounded row in the exported readout. */
class P3Acc11ContractTest {

    private static final String COUNTER =
        "io.izzel.arclight.common.prts.kernel.jobs.RecomputeUnbounded";
    private static final String KEY = "recompute.unbounded=";

    @Test
    void theRunScopedCounterExists() throws Exception {
        Class<?> counter = Class.forName(COUNTER);
        assertNotNull(counter.getDeclaredMethod("reset"));
        assertNotNull(counter.getDeclaredMethod("noteUnbudgetedStart"));
        assertNotNull(counter.getDeclaredMethod("noteBudgetedBuild"));
        assertNotNull(counter.getDeclaredMethod("reading"));
    }

    @Test
    void aCoveredBuildPublishesTheKeyInTheReadout() throws Exception {
        Class<?> counter = Class.forName(COUNTER);
        counter.getDeclaredMethod("reset").invoke(null);
        JobDeclaration declaration = declaration();
        JobGraphBuilder.freeze(List.of(declaration), 10L, 2L, 1L, 64);
        Class<?> moduleClass = Class.forName("io.izzel.arclight.common.prts.kernel.KernelModule");
        Object module = moduleClass.getMethod("instance").invoke(null);
        Class<?> readingsClass =
            Class.forName("io.izzel.arclight.common.prts.kernel.observe.KernelReadings");
        Method export = readingsClass.getMethod("export", moduleClass);
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) export.invoke(null, module);
        boolean found = lines.stream().anyMatch(line -> line.startsWith(KEY));
        assertTrue(found, "the readout carries no recompute.unbounded row after a graph build");
    }

    private static JobDeclaration declaration() {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef("world-a", "entity", 0);
        return new JobDeclaration("a/0", 1L, "world-a", "entity", 0, List.of(), 0, "r", "region",
            List.of(ref), List.of(ref),
            io.izzel.arclight.common.prts.kernel.shares.ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
    }
}
