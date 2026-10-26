/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chunk-pipeline row pair the measurement uses: every submit at the sorter's host entry is
 * one host-ticked row and every task a freeze claims into a batch is one owned row. Both keys
 * reset with the readings, so a window delta is a real numerator/denominator pair. */
class PipelineRowReadingsTest {

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.fixes");
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theHostEntryCounterIsResettable() {
        PrtsPipelineBatches.resetHostTicked();
        PrtsPipelineBatches.hostTicked();
        PrtsPipelineBatches.hostTicked();
        org.junit.jupiter.api.Assertions.assertEquals(2L, PrtsPipelineBatches.hostTickedCount());
        PrtsPipelineBatches.resetHostTicked();
        org.junit.jupiter.api.Assertions.assertEquals(0L, PrtsPipelineBatches.hostTickedCount());
    }

    @Test
    void theRowPairIsPublishedByTheKernelReadings() {
        System.setProperty("arclight.prts.kernel", "true");
        System.setProperty("arclight.prts.fixes", "true");
        List<String> export = KernelReadings.export(KernelModule.instance());
        assertTrue(export.stream().anyMatch(line -> line.startsWith("host_ticked_rows=")),
            "the sink-minted pipeline submits are published as host-ticked rows");
        assertTrue(export.stream().anyMatch(line -> line.startsWith("owned_rows=")),
            "the tasks a freeze claims are published as owned rows");
    }
}
