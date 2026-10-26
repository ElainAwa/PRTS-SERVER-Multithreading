/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chunk-pipeline row pair the measurement uses: every sink-minted submit is one host-entered
 * row and every task a freeze claims into a batch is one owned row. Both keys reset with the
 * pipeline ledger, so a window delta is a real numerator/denominator pair rather than a default. */
class PipelineRowReadingsTest {

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.fixes");
        System.clearProperty("arclight.prts.kernel");
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
