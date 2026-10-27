/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which attribution a site row carries: the domain of the newest report filed at it, and the family
 * of its site key when that report names no declared domain. */
class SafetyAttributionRuleTest {

    private static final SafetyNet.ViolationKind KIND = SafetyNet.ViolationKind.VERSION_CONFLICT;
    private static final String RING_SITE = "commit:ring|entity";
    private static final String FAMILY = "commit";

    private static SafetyNet net(String... declared) {
        SafetyNet net = new SafetyNet(() -> false, () -> 8);
        net.declareDomains(List.of(declared));
        return net;
    }

    private static SafetyNet.ViolationReport report(SafetyNet.ViolationKind kind, String siteId,
                                                    String domain) {
        return new SafetyNet.ViolationReport(kind, "world-a", siteId, 3L,
            RejectCode.VERSION_MISMATCH, "1 refused line(s) at " + siteId, "counted", 1, domain);
    }

    private static String attribution(SafetyNet net, List<SafetyNet.ViolationReport> reports) {
        return KernelReadings.attributionOf(net, reports, KIND, RING_SITE);
    }

    @Test
    void aDeclaredReportDomainAttributesTheRow() {
        SafetyNet net = net("entity");
        String attribution = attribution(net, List.of(report(KIND, RING_SITE, "entity")));

        assertEquals("entity", attribution);
        assertTrue(net.isDomain(attribution), "the row names a domain declared in this run");
    }

    @Test
    void aReportDomainThatWasNotDeclaredIsNotADomain() {
        SafetyNet net = net("entity");
        String attribution = attribution(net, List.of(report(KIND, RING_SITE, "chunk-pipeline")));

        assertEquals(FAMILY, attribution, "the row falls back to the family of its site key");
        assertFalse(net.isDomain(attribution),
            "a name the run never declared attributes nothing");
    }

    @Test
    void aKernelOriginReportIsNotADomain() {
        SafetyNet net = net("entity");
        String attribution = attribution(net,
            List.of(report(KIND, RING_SITE, SafetyNet.KERNEL_ORIGIN)));

        assertEquals(FAMILY, attribution);
        assertFalse(net.isDomain(attribution));
    }

    @Test
    void anUnattributedReportIsNotADomain() {
        SafetyNet net = net("entity");
        String attribution = attribution(net, List.of(report(KIND, RING_SITE, "")));

        assertEquals(FAMILY, attribution);
        assertFalse(net.isDomain(attribution));
    }

    @Test
    void withoutAReportTheDeclaredSiteFamilyStillAttributesTheRow() {
        SafetyNet net = net("commit");

        String attribution = attribution(net, List.of());

        assertEquals(FAMILY, attribution, "the site key keeps the attribution it always had");
        assertTrue(net.isDomain(attribution));
    }

    @Test
    void aReportOfAnotherKindOrOfAnotherSiteAttributesNothing() {
        SafetyNet net = net("entity");
        List<SafetyNet.ViolationReport> reports = List.of(
            report(KIND, "commit:ring|other", "entity"),
            report(SafetyNet.ViolationKind.HARD_TIMEOUT, RING_SITE, "entity"));

        assertEquals(FAMILY, attribution(net, reports));
    }
}
