/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which producers of the safety review name a domain: a commit ring does, and the counters built
 * above it or beside it do not. */
class KernelSafetyDomainsTest {

    private static CommitLog log(int capacity) {
        return new CommitLog(() -> capacity,
            (worldId, domainId, nodeKey) -> new CommitLog.Resolved(7L, 0, false));
    }

    private static CommitLog.Batch batch(String worldId, String domainId) {
        return new CommitLog.Batch(worldId, domainId, "a/0", CommitLog.Batch.Kind.APPLY, 1L, 1L, 1);
    }

    @Test
    void everyRingCarriesTheDomainItDeclares() {
        CommitLog log = log(1);
        assertTrue(log.reach(batch("world-a", "entity")).logged());
        assertTrue(log.reach(batch("world-b", "chunk-pipeline")).logged());

        Map<SafetyNet.Point, String> domains = KernelModule.commitRingDomains(log.rings());

        assertEquals(2, domains.size());
        assertEquals("entity", domains.get(new SafetyNet.Point("world-a", "commit:ring|entity")));
        assertEquals("chunk-pipeline",
            domains.get(new SafetyNet.Point("world-b", "commit:ring|chunk-pipeline")));
        assertFalse(domains.containsKey(new SafetyNet.Point(SafetyNet.NO_WORLD_SITE,
            "commit:order")), "the aggregate counter names no domain");
        assertTrue(KernelModule.commitRingDomains(List.of()).isEmpty());
    }

    @Test
    void aDeclaredRingDomainRidesOnItsReportAndAnUndeclaredOneDoesNot() {
        CommitLog log = log(1);
        assertTrue(log.reach(batch("world-a", "entity")).logged());
        assertTrue(log.reach(batch("world-b", "chunk-pipeline")).logged());
        Map<SafetyNet.Point, Long> refusals = new LinkedHashMap<>();
        refusals.put(new SafetyNet.Point("world-a", "commit:ring|entity"), 1L);
        refusals.put(new SafetyNet.Point("world-b", "commit:ring|chunk-pipeline"), 1L);
        Map<SafetyNet.Point, String> domains = KernelModule.commitRingDomains(log.rings());

        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        net.declareDomains(List.of("entity"));
        List<SafetyNet.ViolationReport> reports = net.review(new SafetyNet.TickSources(Map.of(),
            refusals, Map.of(), 0L, 0L, 0L, 0L, domains), 1L);
        SafetyNet control = new SafetyNet(() -> false, () -> 8);
        control.declareDomains(List.of("entity"));
        List<SafetyNet.ViolationReport> plain = control.review(new SafetyNet.TickSources(Map.of(),
            refusals, Map.of(), 0L, 0L, 0L, 0L), 1L);

        assertEquals(2, reports.size());
        assertEquals(plain.size(), reports.size());
        Map<String, String> domainBySite = new LinkedHashMap<>();
        for (int index = 0; index < reports.size(); index++) {
            SafetyNet.ViolationReport report = reports.get(index);
            SafetyNet.ViolationReport bare = plain.get(index);
            assertEquals(bare.kind(), report.kind());
            assertEquals(bare.worldId(), report.worldId());
            assertEquals(bare.siteId(), report.siteId());
            assertEquals(bare.code(), report.code());
            assertEquals(bare.cascade(), report.cascade());
            assertEquals(RejectCode.VERSION_MISMATCH, report.code());
            domainBySite.put(report.siteId(), report.domainKey());
        }
        assertEquals("entity", domainBySite.get("commit:ring|entity"));
        assertEquals(SafetyNet.KERNEL_ORIGIN, domainBySite.get("commit:ring|chunk-pipeline"),
            "a domain that was not declared is not a domain");
        assertEquals(2L, net.count(SafetyNet.ViolationKind.VERSION_CONFLICT));
        assertEquals(control.count(SafetyNet.ViolationKind.VERSION_CONFLICT),
            net.count(SafetyNet.ViolationKind.VERSION_CONFLICT));
        assertEquals(control.cascade().depth(), net.cascade().depth());
        assertEquals(control.cascade().steps(), net.cascade().steps());
        assertTrue(net.conservationHolds());
    }
}
