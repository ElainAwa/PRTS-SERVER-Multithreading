/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal gate is off by default; the recursion guard reports only the outermost span;
 * the net accumulator is safe under concurrent event-loop threads. */
class P2B03A04QuotaCostGateTest {

    @AfterEach
    void closeGate() {
        QuotaCostGate.clearTestOverride();
        System.clearProperty(QuotaCostGate.PROPERTY);
        QuotaCostReadings.reset();
    }

    @Test
    void gateIsOffByDefault() {
        QuotaCostGate.clearTestOverride();
        System.clearProperty(QuotaCostGate.PROPERTY);
        assertFalse(QuotaCostGate.enabled());
    }

    @Test
    void controlledStartPropertyTurnsTheGateOn() {
        QuotaCostGate.clearTestOverride();
        System.setProperty(QuotaCostGate.PROPERTY, "true");
        assertTrue(QuotaCostGate.enabled());
        System.clearProperty(QuotaCostGate.PROPERTY);
        assertFalse(QuotaCostGate.enabled());
    }

    @Test
    void nestedMachineSpansReportOnlyTheOutermostCall() {
        QuotaCostReadings.reset();
        QuotaCostGate.setEnabledForTests(true);
        boolean root = QuotaCostSpans.enterMachine();
        long startedAt = root ? System.nanoTime() : 0L;
        boolean nested = QuotaCostSpans.enterMachine();
        QuotaCostSpans.exitMachine(nested, 0L);
        QuotaCostSpans.exitMachine(root, startedAt);
        assertTrue(root);
        assertFalse(nested);
        assertEquals(1L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.MACHINE));
        assertTrue(QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.MACHINE) >= 0L);
    }

    @Test
    void exceptionStillEndsTheOutermostSpan() {
        QuotaCostReadings.reset();
        QuotaCostGate.setEnabledForTests(true);
        boolean root = QuotaCostSpans.enterMachine();
        long startedAt = root ? System.nanoTime() : 0L;
        try {
            throw new IllegalStateException("fixture");
        } catch (IllegalStateException expected) {
            // the finally below is the behaviour under test
        } finally {
            QuotaCostSpans.exitMachine(root, startedAt);
        }
        assertEquals(1L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.MACHINE));
    }

    @Test
    void netAccumulatorCountsConcurrentEventLoopThreads() throws Exception {
        QuotaCostReadings.reset();
        int threads = 4;
        int perThread = 2000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int call = 0; call < perThread; call++) {
                    QuotaCostReadings.note(QuotaCostReadings.QuotaClass.NET, 1L);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals((long) threads * perThread,
            QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.NET));
        assertEquals((long) threads * perThread,
            QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.NET));
    }
}
