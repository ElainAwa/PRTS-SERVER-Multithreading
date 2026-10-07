/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void aRunThatSawNothingStillReadsEveryKindOnBothDimensions() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);

        net.review(SafetyNet.TickSources.empty(), 1L);

        assertEquals(0L, net.total());
        for (SafetyNet.ViolationKind kind : SafetyNet.ViolationKind.values()) {
            assertEquals(1, net.readSite(kind).size(), "a kind without a site still needs its row");
            assertEquals(1, net.readWorld(kind).size(), "a kind without a world still needs its row");
            assertEquals(0L, net.readSite(kind).get(0).count(), "the row has to carry zero");
            assertEquals(SafetyNet.NO_CELL, net.readSite(kind).get(0).key());
            assertEquals(0L, net.readWorld(kind).get(0).count());
            assertEquals(0L, net.siteBound(kind));
            assertEquals(0L, net.worldBound(kind));
            assertEquals(0L, net.count(kind));
        }
        assertTrue(net.conservationHolds());
    }

    @Test
    void everyKindIsReadOutOnEveryColumnAndTheZeroRowsAreTheOnesThatWereNotSeen() {
        Map<SafetyNet.Point, Long> denied = new LinkedHashMap<>();
        denied.put(new SafetyNet.Point("world-a", "block_write|main|registered"), 2L);
        SafetyNet net = new SafetyNet(() -> false, () -> 8);

        net.review(new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 1L);

        int columns = 0;
        int zeroRows = 0;
        for (SafetyNet.ViolationKind kind : SafetyNet.ViolationKind.values()) {
            List<SafetyNet.Cell> bySite = net.readSite(kind);
            List<SafetyNet.Cell> byWorld = net.readWorld(kind);
            assertEquals(1, bySite.size(), "the site seen in this run is one column for every kind");
            assertEquals(1, byWorld.size(), "the world seen in this run is one column for every kind");
            columns = columns + bySite.size() + byWorld.size();
            for (SafetyNet.Cell cell : bySite) {
                zeroRows = zeroRows + (cell.count() == 0L ? 1 : 0);
            }
            for (SafetyNet.Cell cell : byWorld) {
                zeroRows = zeroRows + (cell.count() == 0L ? 1 : 0);
            }
            assertEquals(net.count(kind), net.siteBound(kind));
            assertEquals(net.count(kind), net.worldBound(kind));
        }
        assertEquals(2L * SafetyNet.ViolationKind.kindCount(), columns);
        assertEquals(2L * (SafetyNet.ViolationKind.kindCount() - 1), zeroRows);
        assertEquals(2L, net.readSite(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).get(0).count());
        assertEquals(0L, net.readSite(SafetyNet.ViolationKind.VERSION_CONFLICT).get(0).count());
        assertTrue(net.conservationHolds());
    }

    @Test
    void onlyADeclaredDomainMakesARowDomainEvidence() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);

        assertFalse(net.isDomain(SafetyNet.KERNEL_ORIGIN));
        assertFalse(net.isDomain(SafetyNet.UNATTRIBUTED));
        assertFalse(net.isDomain("wait"));
        assertFalse(net.isDomain(null));
        assertEquals(SafetyNet.KERNEL_ORIGIN, net.attributionOf("wait:chunk"));
        assertEquals(SafetyNet.KERNEL_ORIGIN, net.attributionOf(""));

        net.declareDomains(Arrays.asList(null, "", SafetyNet.KERNEL_ORIGIN, "entity"));
        assertTrue(net.isDomain("entity"));
        assertFalse(net.isDomain("chunk"));
        assertEquals("entity", net.attributionOf("entity|tick"));
        assertEquals(SafetyNet.KERNEL_ORIGIN, net.attributionOf("wait:chunk"));
        assertEquals(1, net.domains().size());
        assertEquals("entity", SafetyNet.domainOf("entity|tick"));
        assertEquals("wait", SafetyNet.domainOf("wait:chunk"));
        assertEquals(SafetyNet.KERNEL_ORIGIN, SafetyNet.domainOf(null));

        net.report(SafetyNet.ViolationKind.HARD_TIMEOUT, "world-a", "wait:chunk", 1L, "one",
            "counted");
        assertFalse(net.readSite(SafetyNet.ViolationKind.HARD_TIMEOUT).isEmpty());
        assertFalse(net.isDomain(net.attributionOf("wait:chunk")));
    }

    @Test
    void theDomainOfAReviewRidesAlongAndIsNeverInferred() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        Map<SafetyNet.Point, Long> denied = points("world-a", "block_write|main|registered", 2L);

        List<SafetyNet.ViolationReport> plain = net.review(
            new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 1L);
        assertEquals(SafetyNet.KERNEL_ORIGIN, plain.get(0).domainKey());
        assertTrue(plain.get(0).attributed());

        Map<SafetyNet.Point, Long> more = points("world-a", "block_write|main|registered", 3L);
        List<SafetyNet.ViolationReport> attributed = net.review(
            new SafetyNet.TickSources(more, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 2L, "entity");
        assertEquals(SafetyNet.KERNEL_ORIGIN, attributed.get(0).domainKey(),
            "a domain that was never declared may not be inferred from the review");

        net.declareDomains(List.of("entity"));
        Map<SafetyNet.Point, Long> declared = points("world-a", "block_write|main|registered", 4L);
        List<SafetyNet.ViolationReport> declaredReports = net.review(
            new SafetyNet.TickSources(declared, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 3L, "entity");
        assertEquals("entity", declaredReports.get(0).domainKey());
        assertTrue(declaredReports.get(0).attributed());

        List<SafetyNet.ViolationReport> none = net.review(
            new SafetyNet.TickSources(declared, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 4L);
        assertTrue(none.isEmpty());
    }
}
