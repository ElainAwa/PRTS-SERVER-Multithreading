/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void aDeclaredDomainSuppliedForAPointRidesOnItsReport() {
        SafetyNet.Point point = new SafetyNet.Point("world-a", "block_write|main|registered");
        Map<SafetyNet.Point, Long> denied = points("world-a", "block_write|main|registered", 1L);
        Map<SafetyNet.Point, String> supplied = Map.of(point, "entity");

        SafetyNet undeclared = new SafetyNet(() -> false, () -> 8);
        List<SafetyNet.ViolationReport> plain = undeclared.review(
            new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied), 1L);
        assertEquals(SafetyNet.KERNEL_ORIGIN, plain.get(0).domainKey(),
            "a domain that was not declared is not a domain");

        SafetyNet declared = new SafetyNet(() -> false, () -> 8);
        declared.declareDomains(List.of("entity"));
        List<SafetyNet.ViolationReport> attributed = declared.review(
            new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied), 1L);
        assertEquals("entity", attributed.get(0).domainKey());
        assertTrue(attributed.get(0).attributed());
    }

    @Test
    void aMixedReviewFilesOnlyTheSuppliedDeclaredDomains() {
        SafetyNet.Point attributed = new SafetyNet.Point("world-a", "block_write|main|registered");
        SafetyNet.Point undeclared = new SafetyNet.Point("world-b", "platform_write|worker|unregistered");
        SafetyNet.Point empty = new SafetyNet.Point("world-c", "wait:chunk");
        SafetyNet.Point absent = new SafetyNet.Point("world-d", "commit:order");
        Map<SafetyNet.Point, Long> denied = new LinkedHashMap<>();
        denied.put(attributed, 1L);
        denied.put(undeclared, 1L);
        denied.put(empty, 1L);
        denied.put(absent, 1L);
        Map<SafetyNet.Point, String> supplied = new LinkedHashMap<>();
        supplied.put(attributed, "entity");
        supplied.put(undeclared, "ghost");
        supplied.put(empty, "");
        Map<SafetyNet.Point, String> expected = Map.of(attributed, "entity",
            undeclared, SafetyNet.KERNEL_ORIGIN, empty, SafetyNet.KERNEL_ORIGIN,
            absent, SafetyNet.KERNEL_ORIGIN);

        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        net.declareDomains(List.of("entity"));
        List<SafetyNet.ViolationReport> reports = net.review(
            new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied), 1L);
        SafetyNet control = new SafetyNet(() -> false, () -> 8);
        control.declareDomains(List.of("entity"));
        List<SafetyNet.ViolationReport> plain = control.review(
            new SafetyNet.TickSources(denied, Map.of(), Map.of(), 0L, 0L, 0L, 0L), 1L);

        assertEquals(4, reports.size());
        assertEquals(plain.size(), reports.size());
        for (int index = 0; index < reports.size(); index++) {
            SafetyNet.ViolationReport report = reports.get(index);
            SafetyNet.ViolationReport bare = plain.get(index);
            assertEquals(bare.kind(), report.kind());
            assertEquals(bare.worldId(), report.worldId());
            assertEquals(bare.siteId(), report.siteId());
            assertEquals(bare.code(), report.code());
            assertEquals(bare.cascade(), report.cascade());
            assertEquals(expected.get(new SafetyNet.Point(report.worldId(), report.siteId())),
                report.domainKey());
        }
        assertEquals(4L, net.total());
        assertEquals(control.total(), net.total());
        assertEquals(control.readSite(SafetyNet.ViolationKind.CROSS_OWNER_WRITE),
            net.readSite(SafetyNet.ViolationKind.CROSS_OWNER_WRITE));
        assertEquals(control.cascade(), net.cascade());
        assertTrue(net.conservationHolds());
    }

    @Test
    void aSuppliedDomainDoesNotLeakPastItsReview() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        net.declareDomains(List.of("entity"));
        SafetyNet.Point point = new SafetyNet.Point("world-a", "commit:ring|entity");
        Map<SafetyNet.Point, String> supplied = new HashMap<>();
        supplied.put(point, "entity");
        SafetyNet.TickSources firstSources = new SafetyNet.TickSources(
            points("world-a", "commit:ring|entity", 1L), Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied);
        supplied.clear();

        List<SafetyNet.ViolationReport> first = net.review(firstSources, 1L);
        assertEquals(1, first.size());
        assertEquals("entity", first.get(0).domainKey(),
            "the sources keep their own copy of the supplied domains");

        SafetyNet.ViolationReport outside = net.report(SafetyNet.ViolationKind.HARD_TIMEOUT,
            "world-a", "commit:ring|entity", 2L, "one", "counted");
        assertEquals(SafetyNet.KERNEL_ORIGIN, outside.domainKey(),
            "a report outside the review has no supplied domain");

        List<SafetyNet.ViolationReport> next = net.review(new SafetyNet.TickSources(
            points("world-a", "commit:ring|entity", 2L), Map.of(), Map.of(), 0L, 0L, 0L, 0L), 3L);
        assertEquals(1, next.size());
        assertEquals(SafetyNet.KERNEL_ORIGIN, next.get(0).domainKey());
    }

    @Test
    void anExplicitReviewDomainOverridesThePointsOwnDomain() {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        net.declareDomains(List.of("entity", "other"));
        SafetyNet.Point point = new SafetyNet.Point("world-a", "commit:ring|entity");
        Map<SafetyNet.Point, String> supplied = Map.of(point, "entity");

        List<SafetyNet.ViolationReport> reports = net.review(new SafetyNet.TickSources(
            points("world-a", "commit:ring|entity", 1L), Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied),
            1L, "other");
        assertEquals(1, reports.size());
        assertEquals("other", reports.get(0).domainKey());

        List<SafetyNet.ViolationReport> undeclared = net.review(new SafetyNet.TickSources(
            points("world-a", "commit:ring|entity", 2L), Map.of(), Map.of(), 0L, 0L, 0L, 0L, supplied),
            2L, "ghost");
        assertEquals(1, undeclared.size());
        assertEquals(SafetyNet.KERNEL_ORIGIN, undeclared.get(0).domainKey(),
            "an undeclared explicit domain falls back to the kernel origin");
    }

    @Test
    void concurrentReviewsKeepTheirOwnSuppliedDomains() throws Exception {
        int reviews = 4;
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        List<String> declared = new ArrayList<>(reviews);
        for (int index = 0; index < reviews; index++) {
            declared.add("entity-" + index);
        }
        net.declareDomains(declared);
        ExecutorService pool = Executors.newFixedThreadPool(reviews);
        try {
            CyclicBarrier start = new CyclicBarrier(reviews);
            List<Future<List<SafetyNet.ViolationReport>>> futures = new ArrayList<>(reviews);
            for (int index = 0; index < reviews; index++) {
                final int slot = index;
                futures.add(pool.submit(() -> {
                    start.await();
                    SafetyNet.Point point = new SafetyNet.Point("world-" + slot,
                        "commit:ring|entity-" + slot);
                    return net.review(new SafetyNet.TickSources(Map.of(), Map.of(point, 1L), Map.of(),
                        0L, 0L, 0L, 0L, Map.of(point, "entity-" + slot)), slot + 1L);
                }));
            }
            for (int index = 0; index < reviews; index++) {
                List<SafetyNet.ViolationReport> reports = futures.get(index).get(30L, TimeUnit.SECONDS);
                assertEquals(1, reports.size());
                assertEquals("world-" + index, reports.get(0).worldId());
                assertEquals("entity-" + index, reports.get(0).domainKey());
            }
            assertEquals((long) reviews, net.total());
        } finally {
            pool.shutdownNow();
        }
    }
}
