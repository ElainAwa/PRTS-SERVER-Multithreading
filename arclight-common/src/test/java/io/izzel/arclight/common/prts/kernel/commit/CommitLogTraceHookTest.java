/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommitLogTraceHookTest {

    private static CommitLog.Batch batch() {
        return new CommitLog.Batch("w", "entity", "node", CommitLog.Batch.Kind.APPLY, 1L, 7L, 2);
    }

    private static CommitLog log() {
        return new CommitLog(() -> 8, (worldId, domainId, nodeKey) ->
            new CommitLog.Resolved(1L, 0, false));
    }

    @Test
    void boundTraceRecordsAndNullTraceKeepsBehaviour() {
        CommitLog log = log();
        log.beginTick(1L);
        log.bindTrace(null);
        log.reach(batch(), 1L, 0, 0L);

        CommitTrace trace = new CommitTrace(8, 8, 8);
        log.beginTick(2L);
        log.bindTrace(trace);
        log.reach(batch(), 1L, 0, 0L);
        assertEquals(1L, trace.recorded());
        assertEquals(1, trace.worlds().size());
        assertEquals(true, trace.worlds().contains("w"));
    }
}
