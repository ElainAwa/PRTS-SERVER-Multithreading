/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The contract the bounded recompute adds to the graph builder. The test resolves the entry by name
 * so the same test states what is missing on a builder that has no bounded recompute at all. */
class BoundedRecomputeContractTest {

    private static final String BUILDER = "io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder";

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(BUILDER + "$" + name);
    }

    @Test
    void theBoundedRecomputeEntryExists() throws Exception {
        Class<?> request = nested("RecomputeRequest");
        assertNotNull(JobGraphBuilder.class.getDeclaredMethod("recompute", request),
            "the builder has no bounded recompute entry");
        assertNotNull(nested("Recompute"));
    }

    @Test
    void theExplicitWorkBudgetsExist() throws Exception {
        Method scan = JobGraphBuilder.class.getDeclaredMethod("scanWorkBudget", int.class);
        Method full = JobGraphBuilder.class.getDeclaredMethod("fullWorkBudget", int.class);
        assertEquals(8 * 64, scan.invoke(null, 64));
        assertEquals(64 * 64, full.invoke(null, 64));
        Class<?> recompute = nested("Recompute");
        assertNotNull(recompute.getMethod("budgeted"));
        assertNotNull(recompute.getMethod("mode"));
        assertNotNull(recompute.getMethod("fullWork"));
        assertNotNull(recompute.getMethod("scanWork"));
    }

    @Test
    void aRequestWithoutABudgetIsRefusedInsteadOfPerformed() throws Exception {
        Class<?> requestType = nested("RecomputeRequest");
        Class<?> recomputeType = nested("Recompute");
        Constructor<?> constructor = requestType.getDeclaredConstructor(JobGraph.class, List.class,
            long.class, long.class, long.class, int.class, int.class, int.class);
        Object request = constructor.newInstance(null, List.of(), 10L, 2L, 1L, 8, 0, 0);
        Object answer = JobGraphBuilder.class.getDeclaredMethod("recompute", requestType)
            .invoke(null, request);

        assertEquals(Boolean.FALSE, recomputeType.getMethod("ok").invoke(answer));
        assertEquals("RECOMPUTE_UNBOUNDED",
            recomputeType.getMethod("code").invoke(answer).toString());
        assertNull(recomputeType.getMethod("graph").invoke(answer));
        assertFalse((Boolean) recomputeType.getMethod("budgeted").invoke(answer));
    }
}
