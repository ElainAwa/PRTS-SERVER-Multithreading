/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The B3 token queue is bounded and all-or-nothing: no record is marked unless every token
 * still names a record and every mark succeeds. */
class P2F05uB3TokenQueueTest {

    @Test
    void queueIsBoundedAndDropsTheOldest() {
        ArrayDeque<Long> tokens = new ArrayDeque<>();
        for (long token = 1L; token <= KernelModule.MAX_PENDING_B3_TOKENS + 1L; token++) {
            KernelModule.addPendingB3Token(tokens, token);
        }
        assertEquals(KernelModule.MAX_PENDING_B3_TOKENS, tokens.size());
        assertEquals(2L, tokens.peekFirst());
    }

    @Test
    void aMissingTokenPreventsEveryMark() {
        SharePlanner planner = new SharePlanner();
        OverrunRecord record = planner.record(row(), "runtime", 1L);
        long token = planner.tokenOf(record);
        planner.recordWouldDegrade(record);
        ArrayDeque<Long> tokens = new ArrayDeque<>();
        tokens.add(token);
        tokens.add(99999L);
        assertFalse(KernelModule.markAllB3Tokens(planner, tokens));
        assertFalse(planner.anyActionExecuted());
    }

    @Test
    void allTokensMarkOnlyWhenEveryMarkSucceeds() {
        SharePlanner planner = new SharePlanner();
        OverrunRecord first = planner.record(row(), "runtime", 1L);
        OverrunRecord second = planner.record(row(), "runtime", 1L);
        planner.recordWouldDegrade(first);
        planner.recordWouldDegrade(second);
        ArrayDeque<Long> tokens = new ArrayDeque<>();
        tokens.add(planner.tokenOf(first));
        tokens.add(planner.tokenOf(second));
        assertTrue(KernelModule.markAllB3Tokens(planner, tokens));
        assertTrue(planner.anyActionExecuted());
        // A second pass is idempotent: an already-true record still counts as success.
        assertTrue(KernelModule.markAllB3Tokens(planner, tokens));
    }

    @Test
    void actionFlagSurvivesRecordEvictionAndClearsOnReset() {
        SharePlanner planner = new SharePlanner();
        OverrunRecord marked = planner.record(row(), "runtime", 1L);
        planner.recordWouldDegrade(marked);
        assertTrue(planner.markTokenExecuted(planner.tokenOf(marked)));
        assertTrue(planner.anyActionExecuted());

        for (int index = 0; index < 300; index++) {
            OverrunRecord later = planner.record(row(), "runtime", 2L + index);
            planner.recordWouldDegrade(later);
        }
        assertTrue(planner.records().size() <= 256);
        assertFalse(planner.records().stream().anyMatch(OverrunRecord::actionExecuted));
        assertTrue(planner.anyActionExecuted());

        planner.clearRecordTokens();
        assertFalse(planner.anyActionExecuted());
    }

    private static ShareTable.ShareRow row() {
        return new ShareTable.ShareRow("world", ShareClass.ENTITY, 1.0, 2.0);
    }
}
