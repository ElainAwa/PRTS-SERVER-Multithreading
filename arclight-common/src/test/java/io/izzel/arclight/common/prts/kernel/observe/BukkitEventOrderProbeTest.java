/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HandlerList/RegisteredListener arrays are the expected order; the probe accepts a legal
 * skipped subsequence and counts only a real reorder or unknown listener as a violation. */
class BukkitEventOrderProbeTest {

    private HandlerList list;
    private final List<RegisteredListener> registered = new ArrayList<>();

    @BeforeEach
    void install() {
        BukkitEventOrderProbe.install();
        list = new HandlerList();
        registered.clear();
    }

    @AfterEach
    void uninstall() {
        BukkitEventOrderProbe.uninstall();
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) ->
                "isEnabled".equals(method.getName()) ? Boolean.TRUE : null);
    }

    private RegisteredListener listener(EventPriority priority) {
        Listener listener = (Listener) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Listener.class}, (proxy, method, args) -> null);
        RegisteredListener registeredListener = new RegisteredListener(listener,
            (l, event) -> { }, priority, plugin(), false);
        list.register(registeredListener);
        registered.add(registeredListener);
        return registeredListener;
    }

    private RegisteredListener[] snapshot() {
        list.bake();
        return list.getRegisteredListeners();
    }

    @Test
    void aSkippedOrFilteredListenerIsALegalSubsequence() {
        RegisteredListener first = listener(EventPriority.NORMAL);
        RegisteredListener second = listener(EventPriority.NORMAL);
        RegisteredListener third = listener(EventPriority.MONITOR);
        RegisteredListener[] expected = snapshot();

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.invokedCurrent(first);
        BukkitEventOrderProbe.invokedCurrent(third);
        BukkitEventOrderProbe.end(false);

        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
        assertEquals(1, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
    }

    @Test
    void aReorderedOrUnknownListenerIsAViolation() {
        RegisteredListener first = listener(EventPriority.NORMAL);
        RegisteredListener second = listener(EventPriority.NORMAL);
        RegisteredListener[] expected = snapshot();

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.invokedCurrent(second);
        BukkitEventOrderProbe.invokedCurrent(first);
        BukkitEventOrderProbe.end(false);

        assertEquals(1L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void rootOnlyCoverageAndNestedSameThreadDepth() {
        RegisteredListener root = listener(EventPriority.NORMAL);
        RegisteredListener child = listener(EventPriority.NORMAL);
        RegisteredListener[] expected = snapshot();

        BukkitEventOrderProbe.begin(expected, false);
        BukkitEventOrderProbe.end(false);
        assertTrue(BukkitEventOrderProbe.reading().isEmpty());

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.invokedCurrent(root);
        BukkitEventOrderProbe.begin(expected, false);
        BukkitEventOrderProbe.invokedCurrent(child);
        BukkitEventOrderProbe.end(false);
        BukkitEventOrderProbe.end(false);

        BukkitEventOrderProbe.Reading reading = BukkitEventOrderProbe.reading().orElseThrow();
        assertEquals(0L, reading.orderViolation());
        assertEquals(2, reading.nestingDepth());
    }

    @Test
    void failureAndTruncationDoNotPublishAZero() {
        RegisteredListener listener = listener(EventPriority.NORMAL);
        RegisteredListener[] expected = snapshot();

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.invokedCurrent(listener);
        BukkitEventOrderProbe.end(true);
        assertTrue(BukkitEventOrderProbe.reading().isEmpty());

        Object[] tooMany = new Object[BukkitEventOrderProbe.MAX_LISTENERS + 1];
        BukkitEventOrderProbe.begin(tooMany, true);
        BukkitEventOrderProbe.end(false);
        assertTrue(BukkitEventOrderProbe.reading().isEmpty());
    }

    @Test
    void resetStartsANewGenerationAndOldFramesCannotPublish() {
        RegisteredListener listener = listener(EventPriority.NORMAL);
        RegisteredListener[] expected = snapshot();

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.reset();
        BukkitEventOrderProbe.end(false);
        assertTrue(BukkitEventOrderProbe.reading().isEmpty());

        BukkitEventOrderProbe.begin(expected, true);
        BukkitEventOrderProbe.invokedCurrent(listener);
        BukkitEventOrderProbe.end(false);
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void rootsOnTwoThreadsKeepTheirOwnFrames() throws Exception {
        RegisteredListener listener = listener(EventPriority.NORMAL);
        RegisteredListener[] expected = snapshot();
        CountDownLatch both = new CountDownLatch(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = workers.submit(() -> {
                BukkitEventOrderProbe.begin(expected, true);
                BukkitEventOrderProbe.invokedCurrent(listener);
                both.countDown();
                try {
                    both.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                BukkitEventOrderProbe.end(false);
            });
            Future<?> two = workers.submit(() -> {
                BukkitEventOrderProbe.begin(expected, true);
                BukkitEventOrderProbe.invokedCurrent(listener);
                both.countDown();
                try {
                    both.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                BukkitEventOrderProbe.end(false);
            });
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertFalse(BukkitEventOrderProbe.reading().isEmpty());
        assertEquals(1, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
    }
}
