/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four element rule, the coverage self-check and the no-interception rule. */
class WaitPointRegistryTest {

    @Test
    void theNineExistingRowsAreRegisteredAndComplete() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        CoverageReport report = registry.reportCoverage();

        assertEquals(9, report.registeredTotal());
        assertEquals(100.0, report.coveragePct(), 1.0e-9);
        assertEquals(0, report.unregistered());
        assertEquals(9, report.injectionWalkthrough().size());
        assertEquals(0L, report.forcedConvergence());
        assertTrue(report.pendingElements().isEmpty());
    }

    @Test
    void aRowMissingAnyOfTheFourElementsIsRefused() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        RegisterResult noProducer = registry.registerWaitPoint(
            declaration("bad.one", "", "progress.custom.one"));
        RegisterResult noSignal = registry.registerWaitPoint(
            declaration("bad.two", "producer", ""));
        RegisterResult ok = registry.registerWaitPoint(
            declaration("ok.row", "producer", "progress.custom.ok"));

        assertTrue(noProducer instanceof RegisterResult.MissingElement);
        assertEquals("producer", ((RegisterResult.MissingElement) noProducer).which());
        assertTrue(noSignal instanceof RegisterResult.MissingElement);
        assertTrue(ok instanceof RegisterResult.Ok);
        assertNull(registry.lookup("bad.one"));
        assertNull(registry.lookup("bad.two"));
        assertNotNull(registry.lookup("ok.row"));
    }

    @Test
    void aDuplicateIdentityIsRefused() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitPointDeclaration first = new WaitPointDeclaration("custom", "custom wait", "producer",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT,
                "progress.custom.count"), "postpone", "snapshot", "per world", "custom.wait", 1);

        assertTrue(registry.registerWaitPoint(first) instanceof RegisterResult.Ok);
        assertTrue(registry.registerWaitPoint(first) instanceof RegisterResult.DuplicateWpId);
        assertEquals(10, registry.registeredTotal());
    }

    @Test
    void anUnregisteredWaitIsCountedAndNotRefused() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitObservation observation = registry.observeWait(null,
            new WaitSpan(null, "unknown.call", "site:a", "world", 1L, 10L, "3"));

        assertEquals(1, registry.unregisteredCallSites());
        assertEquals(1, registry.reportCoverage().unregistered());
        assertEquals(10L, observation.maxWaitMs());
        assertEquals(1, registry.unregisteredTodo().size());
        assertTrue(registry.unregisteredTodo().containsKey("unknown.call"));
    }

    @Test
    void anObservationDoesNotChangeTheBoundOrCancelTheWait() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitObservation over = registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 1L, 80L, "7"));
        WaitObservation inside = registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 2L, 20L, "8"));

        assertTrue(over.overrun());
        assertEquals(80L, over.maxWaitMs());
        assertEquals(1L, registry.waitOverrunCount());
        assertEquals(80L, inside.maxWaitMs());
        assertEquals(8L, registry.progressReadings().get("chunk"));
    }

    @Test
    void aWalkthroughIsRecordedPerRow() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        registry.noteInjectionWalkthrough("chunk");
        registry.noteInjectionWalkthrough("chunk");

        CoverageReport report = registry.reportCoverage();
        assertEquals(2, report.injectionWalkthrough().get("chunk"));
        assertEquals(0, report.injectionWalkthrough().get("save"));
        assertNotNull(registry.lookupByCallSite("chunk.materialize"));
        assertNull(registry.lookupByCallSite("not.a.call.site"));
    }

    private static WaitPointDeclaration declaration(String wpId, String producer, String fieldRef) {
        return new WaitPointDeclaration(wpId, "custom wait", producer,
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT, fieldRef),
            "postpone", "snapshot", "per world", wpId + ".call", 1);
    }
}
