/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.rcon.RconCommandDeferral;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event rung's return contract: only a rung that is the deepest one, whose real pressure
 * cleared (EVENT row back inside the margin and the deferral hand-off idle), and whose declared
 * clean/second gate is ready may return; it returns once and never invents an effective action.
 * A pending deferred command changes only what the counted return clears. */
class B4ReturnContractTest {

    private static final int WINDOW_TICKS = 3;

    private static DegradeLadder ladder(boolean actionsEnabled, boolean pressureCleared) {
        DegradeLadder ladder = new DegradeLadder(() -> actionsEnabled);
        ladder.bindSecondCondition(DegradeLevel.B4, "budget.event_over", () -> pressureCleared);
        return ladder;
    }

    private static DegradeLadder standingOn(DegradeLadder ladder, DegradeLevel level, int cleanTicks) {
        ladder.noteEntered(level, 1L);
        for (int index = 0; index < cleanTicks; index++) {
            ladder.noteTick(false);
        }
        return ladder;
    }

    private static boolean returnB4(DegradeLadder ladder, boolean deferralWanted, boolean deferralIdle) {
        return KernelModule.B4Return.apply(ladder, ladder.sign().deepest(),
            deferralWanted, deferralIdle, WINDOW_TICKS);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(5L);
        }
    }

    @Test
    void anUnboundRungCannotReturn() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertFalse(returnB4(ladder, false, true));
        assertEquals(0L, ladder.counters(DegradeLevel.B4).returned());
        assertEquals("second_condition_unbound",
            ladder.gate(DegradeLevel.B4, WINDOW_TICKS).blockedBy());
    }

    @Test
    void aShortCleanRunCannotReturn() {
        DegradeLadder ladder = ladder(true, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS - 1);
        assertFalse(returnB4(ladder, false, true));
        assertEquals("clean_ticks", ladder.gate(DegradeLevel.B4, WINDOW_TICKS).blockedBy());
        ladder.noteTick(false);
        assertTrue(returnB4(ladder, false, true));
    }

    @Test
    void aWantedOrBusyHandoffCannotReturn() {
        DegradeLadder ladder = ladder(true, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertFalse(returnB4(ladder, true, true), "the event gate still wants to defer");
        assertFalse(returnB4(ladder, false, false), "the deferral hand-off is busy");
        assertFalse(returnB4(ladder, true, false));
        assertEquals(DegradeLevel.B4, ladder.sign().deepest());
    }

    @Test
    void eventPressureStillOverCannotReturn() {
        DegradeLadder ladder = ladder(true, false);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertFalse(returnB4(ladder, true, true));
        assertFalse(returnB4(ladder, false, true));
        assertEquals("second_condition",
            ladder.gate(DegradeLevel.B4, WINDOW_TICKS).blockedBy());
    }

    @Test
    void aRungThatIsNotTheDeepestOneCannotReturn() {
        DegradeLadder below = ladder(true, true);
        standingOn(below, DegradeLevel.B3, WINDOW_TICKS + 2);
        assertFalse(returnB4(below, false, true));
        assertEquals(0L, below.counters(DegradeLevel.B4).returned());
        DegradeLadder above = ladder(true, true);
        standingOn(above, DegradeLevel.B5, WINDOW_TICKS + 2);
        assertFalse(returnB4(above, false, true));
        assertEquals(0L, above.counters(DegradeLevel.B4).returned());
    }

    @Test
    void allConditionsReturnOnceWithoutFakingAnEffectiveAction() {
        DegradeLadder ladder = ladder(true, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertTrue(returnB4(ladder, false, true));
        assertEquals(1L, ladder.counters(DegradeLevel.B4).returned());
        assertEquals(0L, ladder.counters(DegradeLevel.B4).effective(),
            "a return is not an effective action and the ledger is not rewritten");
        assertEquals(DegradeLevel.B3, ladder.sign().deepest(), "the lower entered rung takes over");
        assertFalse(returnB4(ladder, false, true), "a second call has nothing left to return");
        assertEquals(1L, ladder.counters(DegradeLevel.B4).returned());
    }

    @Test
    void repeatedCallsDoNotDoubleCount() {
        DegradeLadder ladder = ladder(true, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        int counted = 0;
        for (int index = 0; index < 4; index++) {
            if (returnB4(ladder, false, true)) {
                counted++;
            }
        }
        assertEquals(1, counted);
        assertEquals(1L, ladder.counters(DegradeLevel.B4).returned());
    }

    @Test
    void disabledActionsCountNoReturnAndKeepTheRung() {
        DegradeLadder ladder = ladder(false, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertFalse(returnB4(ladder, false, true));
        assertEquals(0L, ladder.counters(DegradeLevel.B4).returned());
        assertEquals(DegradeLevel.B4, ladder.sign().deepest());
    }

    @Test
    void aResetAfterTheReturnDoesNotReportAnotherOne() {
        DegradeLadder ladder = ladder(true, true);
        standingOn(ladder, DegradeLevel.B4, WINDOW_TICKS + 2);
        assertTrue(returnB4(ladder, false, true));
        ladder.reset();
        assertEquals(DegradeLevel.NONE, ladder.sign().deepest());
        assertEquals(0L, ladder.counters(DegradeLevel.B4).returned());
        assertFalse(returnB4(ladder, false, true));
        assertEquals(0L, ladder.counters(DegradeLevel.B4).returned());
    }

    @Test
    void theDeferralHandoffIsNotIdleWhileQueuedOrPumping() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> caller = workers.submit(() -> deferral.await("cmd",
                new RconCommandDeferral.OwnerUnit() {
                    @Override
                    public void prepare() {
                    }

                    @Override
                    public void dispatch() {
                        started.countDown();
                        await(release);
                    }

                    @Override
                    public String capture() {
                        return "reply";
                    }
                }));
            awaitTrue(() -> deferral.queued() == 1);
            assertFalse(deferral.idle(), "a queued request is not idle");
            Future<?> pump = workers.submit(deferral::pumpOwner);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(deferral.idle(), "a pumping owner is not idle even with an empty queue");
            release.countDown();
            assertEquals("reply", caller.get(5, TimeUnit.SECONDS));
            pump.get(5, TimeUnit.SECONDS);
            assertTrue(deferral.idle());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
