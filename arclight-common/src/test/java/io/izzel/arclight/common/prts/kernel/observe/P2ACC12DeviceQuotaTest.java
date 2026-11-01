/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.quota.DeviceTickDeferral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DEVICE quota readout: quota.device_over is published next to the other quota families and
 * counts only over-budget DEVICE plans whose deferred ticks really ran; a clean plan and a reset
 * leave it at zero. */
class P2ACC12DeviceQuotaTest {

    private static final String WORLD = "quota-device-world";
    private static final Object SCOPE = new Object();
    private static final long TICK = 61L;
    private static final long EPOCH = 3L;

    @BeforeEach
    void enable() {
        System.setProperty("arclight.prts.kernel", "true");
        DeviceTickDeferral.reset();
    }

    @AfterEach
    void clear() {
        DeviceTickDeferral.reset();
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theDeviceOverCounterIsPublishedNextToTheOtherQuotaFamilies() {
        List<String> lines = KernelReadings.export(KernelModule.instance());
        assertTrue(has(lines, "quota.entity_over"));
        assertTrue(has(lines, "quota.net_over"));
        assertTrue(has(lines, "quota.device_over"));
    }

    @Test
    void anOverBudgetPlanRaisesTheDeviceOverCounter() {
        assertEquals("0", value(KernelReadings.export(KernelModule.instance()),
            "quota.device_over"));
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 10L, true));
        FakeTicker ticker = new FakeTicker();
        DeviceTickDeferral.noteInline(SCOPE, 11L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, ticker));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(1, DeviceTickDeferral.drainScope(SCOPE, worldId -> EPOCH),
            "the level tick drains its own waiting plan at its own RETURN");
        assertEquals(1, ticker.ticks, "the deferred tick really ran at the safe point");
        assertEquals("1", value(KernelReadings.export(KernelModule.instance()),
            "quota.device_over"));
        assertEquals(DeviceTickDeferral.Result.DEFERRED,
            DeviceTickDeferral.receipts().get(0).result());

        DeviceTickDeferral.reset();
        assertEquals("0", value(KernelReadings.export(KernelModule.instance()),
            "quota.device_over"));
    }

    @Test
    void anUnderBudgetPlanLeavesTheCounterAtZero() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1_000L, true));
        DeviceTickDeferral.noteInline(SCOPE, 100L);
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertFalse(DeviceTickDeferral.overCount() > 0L);
        assertEquals(0, DeviceTickDeferral.drainScope(SCOPE, worldId -> EPOCH));
        assertEquals("0", value(KernelReadings.export(KernelModule.instance()),
            "quota.device_over"));
    }

    private static String value(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static boolean has(List<String> lines, String key) {
        return value(lines, key) != null;
    }

    private static final class FakeTicker implements TickingBlockEntity {

        private int ticks;
        private boolean removed;

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos getPos() {
            return BlockPos.ZERO;
        }

        @Override
        public String getType() {
            return "furnace";
        }
    }
}
