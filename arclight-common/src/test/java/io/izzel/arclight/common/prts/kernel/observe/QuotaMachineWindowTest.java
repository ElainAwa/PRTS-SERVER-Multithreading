/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MACHINE quota window counts at most one over per completed non-warmup window, publishes the
 * count as quota.machine_over and never acts: MACHINE has no safe action, so a positive over is a
 * named refusal for acceptance instead of a degradation. */
class QuotaMachineWindowTest {

    private static final long WIDTH = 20L;

    @BeforeEach
    void setUp() {
        QuotaMachineWindow.reset();
        System.setProperty("arclight.prts.kernel", "true");
    }

    @AfterEach
    void cleanup() {
        QuotaMachineWindow.reset();
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theFirstCallOnlySeedsTheBaseline() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void anIncompleteWindowCountsNothing() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH - 1L, 100_000_000L, WIDTH, 0L, 50.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void anUnderWindowKeepsTheCounterZero() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        // 20 ms of machine wall time per second of window: inside the 50 ms limit.
        QuotaMachineWindow.noteTick(WIDTH, 20_000_000L, WIDTH, 0L, 50.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void anOverWindowCountsExactlyOnce() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        // 100 ms of machine wall time per second of window: above the 50 ms limit.
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, 0L, 50.0);
        assertEquals(1L, QuotaMachineWindow.overCount());
    }

    @Test
    void oneOverIsTheMostOneWindowCanAdd() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 10_000_000_000L, WIDTH, 0L, 50.0);
        assertEquals(1L, QuotaMachineWindow.overCount(),
            "a window that is over by any amount adds one, not one per event");
    }

    @Test
    void eachCompletedOverWindowAddsOne() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH * 2L, 200_000_000L, WIDTH, 0L, 50.0);
        assertEquals(2L, QuotaMachineWindow.overCount());
    }

    @Test
    void aWarmupWindowDoesNotCountAnOver() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, WIDTH, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, WIDTH, 50.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void aNonPositiveLimitNeverCounts() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 0.0);
        QuotaMachineWindow.noteTick(WIDTH, 10_000_000_000L, WIDTH, 0L, 0.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void resetClearsTheWindowAndTheCounter() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, 0L, 50.0);
        assertEquals(1L, QuotaMachineWindow.overCount());
        QuotaMachineWindow.reset();
        assertEquals(0L, QuotaMachineWindow.overCount());
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        assertEquals(0L, QuotaMachineWindow.overCount());
    }

    @Test
    void theWindowOnlyReadsTheCostSourceAndNeverChangesIt() {
        long callsBefore = QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.MACHINE);
        long wallBefore = QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.MACHINE);
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, 0L, 50.0);
        assertEquals(callsBefore, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.MACHINE),
            "the counter window has no write path into the MACHINE cost source");
        assertEquals(wallBefore,
            QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.MACHINE));
    }

    @Test
    void machineOverIsPublishedAsTheReadoutKey() {
        QuotaMachineWindow.noteTick(0L, 0L, WIDTH, 0L, 50.0);
        QuotaMachineWindow.noteTick(WIDTH, 100_000_000L, WIDTH, 0L, 50.0);
        List<String> lines = KernelReadings.export(KernelModule.instance());
        assertEquals("1", value(lines, "quota.machine_over"));
        assertTrue(has(lines, "quota.entity_over"));
        assertTrue(has(lines, "quota.net_over"));
        assertTrue(has(lines, "quota.device_over"));
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
}
