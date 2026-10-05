/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The five kinds of violation, the two dimensions they are counted on, the cascade cap, the
 * escalation switch and the refusal of a report without evidence. */
class SafetyNetTest {

    private static Map<SafetyNet.Point, Long> points(String world, String site, long value) {
        Map<SafetyNet.Point, Long> map = new LinkedHashMap<>();
        map.put(new SafetyNet.Point(world, site), value);
        return map;
    }

    @Test
    void everyKindIsCountedPerSiteAndPerWorld() {
        Map<SafetyNet.Point, Long> denied = points("world-a", "block_write|main|registered", 2L);
        denied.put(new SafetyNet.Point("world-b", "platform_write|worker|unregistered"), 1L);
        SafetyNet net = new SafetyNet(() -> false, () -> 8);

        List<SafetyNet.ViolationReport> reports = net.review(new SafetyNet.TickSources(denied, Map.of(),
            points(SafetyNet.NO_WORLD_SITE, "wait:chunk", 1L), 1L, 1L, 0L, 1L), 10L);

        assertEquals(6, reports.size());
        assertEquals(SafetyNet.ViolationKind.kindCount(), net.kinds().size());
        assertEquals(3L, net.count(SafetyNet.ViolationKind.CROSS_OWNER_WRITE));
        assertEquals(2, net.sites(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).size());
        assertEquals(1L, net.countAt(SafetyNet.ViolationKind.CROSS_OWNER_WRITE,
            "platform_write|worker|unregistered"));
        assertEquals(2, net.worlds(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).size());
        assertEquals(2L, net.countIn(SafetyNet.ViolationKind.CROSS_OWNER_WRITE, "world-a"));
        assertEquals(1L, net.count(SafetyNet.ViolationKind.HARD_TIMEOUT));
        assertEquals(1L, net.count(SafetyNet.ViolationKind.UNKNOWN_ACCESS));
        assertEquals(1L, net.count(SafetyNet.ViolationKind.VERSION_CONFLICT));
        assertEquals(1L, net.count(SafetyNet.ViolationKind.ESCALATE_SERIAL));
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER,
            reports.get(0).code());
    }

    @Test
    void theSameSourceReadTwiceIsNotCountedTwice() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        Map<SafetyNet.Point, Long> denied = points("world-a", "site:a", 1L);

        net.review(new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 1L);
        net.review(new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 2L);

        assertEquals(1L, net.total());
    }

    @Test
    void theCascadeStopsAtTheCapAndKeepsCountingTheSteps() {
        SafetyNet net = new SafetyNet(() -> true, () -> 2);
        for (int index = 1; index <= 3; index++) {
            net.report(SafetyNet.ViolationKind.VERSION_CONFLICT, "world-a", "site:a", 1L, "one", "counted");
        }

        SafetyNet.Cascade cascade = net.cascade();
        assertEquals(2L, cascade.cap());
        assertEquals(3L, cascade.depth());
        assertEquals(2L, cascade.steps());
        assertEquals(1L, cascade.capped());
        assertEquals(2L, cascade.stopped());
        assertEquals(1L, net.escalated());
    }

    @Test
    void theEscalationSwitchIsOffAndAReportWithoutEvidenceIsRefused() {
        SafetyNet off = new SafetyNet(() -> false, () -> 8);
        SafetyNet on = new SafetyNet(() -> true, () -> 8);
        off.report(SafetyNet.ViolationKind.HARD_TIMEOUT, "world-a", "site:a", 1L, "one", "counted");
        on.report(SafetyNet.ViolationKind.HARD_TIMEOUT, "world-a", "site:a", 1L, "one", "counted");

        assertEquals(0L, off.escalated());
        assertEquals(1L, on.escalated());
        assertFalse(off.switchEnabled());
        assertTrue(on.switchEnabled());

        long before = off.total();
        SafetyNet.ViolationReport refused = off.report(SafetyNet.ViolationKind.HARD_TIMEOUT, "world-a", "site:a", 1L,
            "", "counted");
        assertFalse(refused.evidencePresent());
        assertEquals(before, off.total());
        assertEquals(1L, off.evidenceEmpty());
    }
}
