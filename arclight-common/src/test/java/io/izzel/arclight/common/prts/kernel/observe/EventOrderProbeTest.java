/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The common event face: a frozen dispatch list is compared against the real invocation order, the
 * root post counts as depth one, and a missing expectation, a failure, a truncated trace or a reset
 * leaves no reading instead of a zero. */
class EventOrderProbeTest {

    private final Object bus = new Object();
    private final Object otherBus = new Object();
    private final Object listenerA = new Object();
    private final Object listenerB = new Object();

    @BeforeEach
    void arm() {
        EventOrderProbe.install();
        System.setProperty(EventOrderProbe.PROPERTY, "true");
        EventOrderProbe.reset();
    }

    @AfterEach
    void disarm() {
        System.clearProperty(EventOrderProbe.PROPERTY);
        EventOrderProbe.uninstall();
    }

    private static void invoke(Object token, Object listener) {
        EventOrderProbe.invokedCurrent(listener);
    }

    @Test
    void theProbeIsOffUntilBothTheBridgeAndThePropertyArePresent() {
        EventOrderProbe.uninstall();
        assertFalse(EventOrderProbe.enabled());
        assertNull(EventOrderProbe.begin(bus, new Object[] {listenerA}, true));
        assertTrue(EventOrderProbe.reading().isEmpty());

        System.clearProperty(EventOrderProbe.PROPERTY);
        EventOrderProbe.install();
        assertFalse(EventOrderProbe.enabled());
        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void oneRootPostPublishesZeroViolationsAndDepthOne() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA, listenerB}, true);
        invoke(root, listenerA);
        invoke(root, listenerB);
        EventOrderProbe.end(root, false);

        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertEquals(0L, reading.orderViolation());
        assertEquals(1, reading.nestingDepth());
    }

    @Test
    void anActualInvocationOrderOtherThanTheFrozenListIsOneViolation() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA, listenerB}, true);
        invoke(root, listenerB);
        invoke(root, listenerA);
        EventOrderProbe.end(root, false);

        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertEquals(1L, reading.orderViolation());
        assertEquals(1, reading.nestingDepth());
    }

    @Test
    void anEarlyStopInTheFrozenOrderIsNotAViolation() {
        Object root = EventOrderProbe.begin(bus,
            new Object[] {listenerA, listenerB, new Object()}, true);
        invoke(root, listenerA);
        EventOrderProbe.end(root, false);

        assertEquals(0L, EventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void theNestingDepthIsTheDeepestSynchronousSameBusPost() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        invoke(root, listenerA);
        Object second = EventOrderProbe.begin(bus, new Object[] {listenerB}, false);
        invoke(second, listenerB);
        Object third = EventOrderProbe.begin(bus, new Object[] {listenerA}, false);
        invoke(third, listenerA);
        EventOrderProbe.end(third, false);
        EventOrderProbe.end(second, false);
        EventOrderProbe.end(root, false);

        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertEquals(3, reading.nestingDepth());
        assertEquals(0L, reading.orderViolation());
    }

    @Test
    void aNestedPostOfAnotherBusDoesNotJoinTheRootSubtree() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        invoke(root, listenerA);
        Object foreign = EventOrderProbe.begin(otherBus, new Object[] {listenerB}, false);
        invoke(foreign, listenerB);
        EventOrderProbe.end(foreign, false);
        EventOrderProbe.end(root, false);

        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertEquals(1, reading.nestingDepth());
        assertEquals(0L, reading.orderViolation());
    }

    @Test
    void aRootPostedUnderAnIgnoredPostStillOpensDepthOne() {
        Object ignored = EventOrderProbe.begin(bus, new Object[] {listenerA}, false);
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerB}, true);
        invoke(root, listenerB);
        EventOrderProbe.end(root, false);
        EventOrderProbe.end(ignored, false);

        assertEquals(1, EventOrderProbe.reading().orElseThrow().nestingDepth());
    }

    @Test
    void aFailedDispatchLeavesTheWholeGenerationWithoutAReading() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        invoke(root, listenerA);
        EventOrderProbe.end(root, true);

        assertTrue(EventOrderProbe.reading().isEmpty());

        EventOrderProbe.reset();
        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void aMissingFrozenListIsInvalidInsteadOfAnEmptyComparison() {
        Object root = EventOrderProbe.begin(bus, null, true);
        EventOrderProbe.end(root, false);

        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void aTruncatedFrozenListIsInvalid() {
        Object[] tooMany = new Object[EventOrderProbe.MAX_LISTENERS + 1];
        Arrays.fill(tooMany, new Object());
        Object root = EventOrderProbe.begin(bus, tooMany, true);
        EventOrderProbe.end(root, false);

        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void aTooDeepNestingIsInvalid() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        List<Object> nested = new ArrayList<>();
        for (int index = 0; index < EventOrderProbe.MAX_DEPTH; index++) {
            nested.add(EventOrderProbe.begin(bus, new Object[] {listenerA}, false));
        }
        for (int index = nested.size() - 1; index >= 0; index--) {
            EventOrderProbe.end(nested.get(index), false);
        }
        EventOrderProbe.end(root, false);

        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void aResetDuringADispatchDropsThatFrameAndTheOldCoverage() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        invoke(root, listenerA);
        EventOrderProbe.reset();
        EventOrderProbe.end(root, false);

        assertTrue(EventOrderProbe.reading().isEmpty());
    }

    @Test
    void aFreshGenerationPublishesOnlyAfterItsOwnRootDispatch() {
        Object first = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        EventOrderProbe.end(first, false);
        assertTrue(EventOrderProbe.reading().isPresent());
        long firstGeneration = EventOrderProbe.reading().orElseThrow().generation();

        EventOrderProbe.reset();
        assertTrue(EventOrderProbe.reading().isEmpty());

        Object second = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        EventOrderProbe.end(second, false);
        EventOrderProbe.Reading reading = EventOrderProbe.reading().orElseThrow();
        assertTrue(reading.generation() > firstGeneration);
        assertEquals(0L, reading.orderViolation());
    }

    @Test
    void twoThreadsRootTheirOwnSubtreesAndTheDeepestWins() {
        AtomicReference<EventOrderProbe.Reading> observed = new AtomicReference<>();
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        invoke(root, listenerA);

        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            Object foreignRoot = EventOrderProbe.begin(otherBus,
                new Object[] {listenerB}, true);
            invoke(foreignRoot, listenerB);
            Object foreignNested = EventOrderProbe.begin(otherBus,
                new Object[] {listenerB}, false);
            EventOrderProbe.end(foreignNested, false);
            EventOrderProbe.end(foreignRoot, false);
            done.countDown();
        });
        thread.start();
        try {
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        EventOrderProbe.end(root, false);
        observed.set(EventOrderProbe.reading().orElseThrow());

        assertEquals(2, observed.get().nestingDepth());
        assertEquals(0L, observed.get().orderViolation());
    }

    @Test
    void theSwitchPublishesNothingWhileOffAndResumesWhenArmedAgain() {
        Object root = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        EventOrderProbe.end(root, false);
        assertTrue(EventOrderProbe.reading().isPresent());

        System.setProperty(EventOrderProbe.PROPERTY, "false");
        assertTrue(EventOrderProbe.reading().isEmpty());

        System.setProperty(EventOrderProbe.PROPERTY, "true");
        EventOrderProbe.reset();
        assertTrue(EventOrderProbe.reading().isEmpty());
        Object again = EventOrderProbe.begin(bus, new Object[] {listenerA}, true);
        EventOrderProbe.end(again, false);
        assertTrue(EventOrderProbe.reading().isPresent());
    }
}
