/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one real queue row wired in this batch: the compute wait already booked by dispatch. The
 * producer row stays absent because no producer-wait counter exists. */
class P2F02aQueueWaitComputeTest {

    private KernelModule module;
    private EntityDomain domain;

    @BeforeEach
    void install() {
        System.setProperty("arclight.prts.kernel", "true");
        module = KernelModule.instance();
        module.resetReadings();
        domain = KernelWiring.install();
    }

    @AfterEach
    void restore() {
        module.resetReadings();
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theWaitRowsStartAtZeroAndReadTheirOwnDispatchCounters() {
        List<String> zero = KernelReadings.export(module);
        assertEquals("0.000", line(zero, "queue.wait_compute_ms"));
        assertEquals("0.000", line(zero, "queue.wait_producer_ms"));

        domain.readings().noteComputeWait(2_500_000L);
        domain.readings().noteProducerWait(3_000_000L);
        List<String> after = KernelReadings.export(module);
        assertEquals("2.500", line(after, "queue.wait_compute_ms"));
        assertEquals("3.000", line(after, "queue.wait_producer_ms"));

        domain.readings().reset();
        List<String> reset = KernelReadings.export(module);
        assertEquals("0.000", line(reset, "queue.wait_compute_ms"));
        assertEquals("0.000", line(reset, "queue.wait_producer_ms"));
    }

    private static String line(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }
}
