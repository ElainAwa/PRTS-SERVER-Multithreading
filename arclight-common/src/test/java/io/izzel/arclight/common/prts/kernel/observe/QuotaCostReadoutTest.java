/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaCostReadoutTest {

    @AfterEach
    void cleanup() {
        QuotaCostReadings.reset();
    }

    @Test
    void allFourCostFamiliesArePublished() {
        QuotaCostReadings.reset();
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.MACHINE, 2_000_000L);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.DEVICE, 3_000_000L);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.ENTITY, 4_000_000L);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.NET, 5_000_000L);
        List<String> lines = KernelReadings.export(KernelModule.instance());
        for (String key : List.of("self.machine_ms", "self.machine_calls",
            "self.device_ms", "self.device_calls", "self.entity_ms", "self.entity_calls",
            "self.net_ms", "self.net_calls")) {
            assertTrue(lines.stream().anyMatch(line -> line.startsWith(key + "=")),
                "missing readout " + key);
        }
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("self.net_ms=5.000")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("self.net_calls=1")));
    }
}
