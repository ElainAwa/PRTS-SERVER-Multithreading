/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitPointDeclaration;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitObservation;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.RegisterResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void theNineContractRowsAreServedInOrderAndLaterRowsAreAppended() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitPointRegistry.NineRows nine = registry.nineRows();

        assertEquals(9, nine.rows());
        assertEquals(9, nine.contractRows());
        assertTrue(nine.aligned());
        assertTrue(nine.unserved().isEmpty());
        assertTrue(nine.appended().isEmpty());

        assertTrue(registry.registerWaitPoint(declaration("later.row", "producer",
            "progress.later.count")) instanceof RegisterResult.Ok);
        WaitPointRegistry.NineRows after = registry.nineRows();
        assertEquals(10, after.rows());
        assertFalse(after.aligned());
        assertEquals(java.util.List.of("later.row"), after.appended());
        assertTrue(after.unserved().isEmpty());
    }

    @Test
    void anObservationCarriesTheFourItemsOfItsRow() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitObservation observation = registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 1L, 80L, null));

        assertTrue(observation.complete());
        assertEquals("chunk", observation.wpId());
        assertNotNull(observation.producer());
        assertEquals(Dec19Elements.SignalKind.COUNT, observation.signal().kind());
        assertNotNull(observation.signal().fieldRef());
        assertNotNull(observation.timeoutAction());
        assertNotNull(observation.degradeTo());
        assertTrue(observation.wouldConverge());
        assertFalse(observation.refused());
        assertNull(observation.rejection());
    }

    @Test
    void anUnregisteredWaitIsOnlyCountedWhileTheRefusalSwitchIsOff() {
        WaitPointRegistry counting = new WaitPointRegistry(() -> 50);
        WaitObservation counted = counting.observeWait(null, span("unknown.call"));

        assertFalse(counted.refused());
        assertNull(counted.rejection());
        assertEquals(0L, counting.refusedUnregistered());
        assertEquals(1, counting.unregisteredCallSites());

        WaitPointRegistry refusing = new WaitPointRegistry(() -> 50, () -> true);
        WaitObservation refused = refusing.observeWait(null, span("unknown.call"));

        assertTrue(refused.refused());
        assertEquals("PROGRESS_UNOBSERVED", refused.rejection());
        assertEquals(1L, refusing.refusedUnregistered());
        assertEquals(1, refusing.unregisteredCallSites());
        assertEquals(1L, refusing.observationCount());
    }

    @Test
    void aBoundProgressSignalPublishesItsValueAndItsMovement() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        long[] depth = {0L};
        registry.progress().bind("xdomain", "intent.queue_depth", () -> depth[0]);

        WaitProgress.Reading still = registry.progress().read("xdomain");
        depth[0] = 5L;
        WaitProgress.Reading moved = registry.progress().read("xdomain");
        WaitProgress.Reading unbound = registry.progress().read("xworld");

        assertTrue(still.bound());
        assertEquals("intent.queue_depth", still.source());
        assertEquals(0L, still.delta());
        assertEquals(5L, moved.value());
        assertEquals(5L, moved.delta());
        assertTrue(moved.advancing());
        assertFalse(unbound.bound());
        assertEquals(0L, unbound.value());
        assertEquals("unbound", unbound.source());
        assertEquals(5L, registry.progressReadings().get("xdomain"));
    }

    @Test
    void aWaitOverTheBoundIsCountedAgainstItsRowAndReachesItsAction() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        registry.observeWait("save", new WaitSpan("save", "save.flush", "site:a", "world", 1L, 80L,
            null));
        registry.observeWait("chunk", new WaitSpan("chunk", "chunk.materialize", "site:a", "world",
            1L, 90L, null));

        assertEquals(1L, registry.overrunOf("save"));
        assertEquals(1L, registry.overrunOf("chunk"));
        assertEquals(0L, registry.overrunOf("net"));
        assertEquals(2L, registry.waitOverrunCount());
        assertEquals(1L, registry.convergence().reached());
        assertEquals(0L, registry.forcedConvergence());
        assertEquals(0L, registry.convergence().effective());
    }

    private static WaitSpan span(String callSite) {
        return new WaitSpan(null, callSite, "site:a", "world", 1L, 10L, null);
    }

    private static WaitPointDeclaration declaration(String wpId, String producer, String fieldRef) {
        return new WaitPointDeclaration(wpId, "custom wait", producer,
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT, fieldRef),
            "postpone", "snapshot", "per world", wpId + ".call", 1);
    }
}
