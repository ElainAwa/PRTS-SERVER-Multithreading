/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.wiring.KernelWiring;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the self check leaves behind for the real call sites.
 *
 * <p>The check drives the wait observation seam on its own observer. The seam is global, so the check
 * has to hand back exactly the watcher it found - otherwise the twenty real call sites keep reporting
 * into a scratch observer that nobody reads, while the module still believes its own watcher is
 * installed. The test puts a watcher in place, runs the check, and drives a real call site afterwards
 * to see that the observation still arrives.</p>
 */
class KernelSelfCheckSeamTest {

    @BeforeEach
    void installTheDomains() {
        KernelWiring.install();
    }

    @Test
    void theCheckHandsTheWaitSeamBackAndTheRealObservationContinues() {
        AtomicInteger productionSeen = new AtomicInteger();
        PrtsWaitSites.SiteWaitTap production =
            (index, siteId, worldId, waitNanos) -> productionSeen.incrementAndGet();
        PrtsWaitSites.SiteWaitTap previous = PrtsWaitSites.watcher();
        PrtsWaitSites.install(production);
        try {
            List<String> lines = KernelSelfCheck.run();

            assertTrue(lines.contains("selftest.result=ok"), () -> String.join("\n", lines));
            assertTrue(lines.contains("selftest.wait_watcher_restored=1"));
            assertSame(production, PrtsWaitSites.watcher(),
                "the watcher that was installed before the check is installed after it");

            PrtsWaitSites.begin(PrtsWaitSites.ENTITY_SET_POS_RAW);
            PrtsWaitSites.end(PrtsWaitSites.ENTITY_SET_POS_RAW);

            assertTrue(productionSeen.get() >= 1,
                "a real call site still reaches the production watcher after the check");
        } finally {
            PrtsWaitSites.install(previous);
        }
    }
}
