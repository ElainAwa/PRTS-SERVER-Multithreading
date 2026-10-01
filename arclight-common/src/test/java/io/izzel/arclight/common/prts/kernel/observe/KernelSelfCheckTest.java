/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-check an operator runs on a live server: it must pass on scratch objects, and it must
 * publish the dispatch readings this layer is judged by.
 */
class KernelSelfCheckTest {

    @Test
    void theMatrixEndsWithNoFailure() {
        List<String> lines = KernelSelfCheck.run();

        assertTrue(lines.contains("selftest.result=ok"), () -> String.join("\n", lines));
        assertTrue(lines.contains("selftest.failures=0"), () -> String.join("\n", lines));
    }

    @Test
    void theCommitSegmentMatrixPublishesTheHoldAndTheWalk() {
        List<String> lines = KernelSelfCheck.run();

        assertTrue(lines.contains("selftest.intent_hold_ran=0"));
        assertTrue(lines.contains("selftest.intent_hold_depth=2"));
        assertTrue(lines.contains("selftest.intent_hold_executed=0"));
        assertTrue(lines.contains("selftest.intent_hold_mode=hold"));
        assertTrue(lines.contains("selftest.intent_walk_steps=2"));
        assertTrue(lines.contains("selftest.intent_walk_executed=2"));
        assertTrue(lines.contains("selftest.intent_walk_cursor=2"));
        assertTrue(lines.contains("selftest.intent_walk_order_violations=0"));
        assertTrue(lines.contains("selftest.path_commit_committed=1"));
        assertTrue(lines.contains("selftest.path_payload_applied=1"));
        assertEquals(0, lines.stream().filter(line -> line.startsWith("selftest.failure=")).count(),
            () -> String.join("\n", lines));
    }
}
