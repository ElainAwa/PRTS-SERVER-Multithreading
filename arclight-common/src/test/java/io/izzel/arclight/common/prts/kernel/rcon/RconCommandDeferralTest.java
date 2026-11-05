/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded hand-off contract: one immutable request, one owner unit, one completion; a full
 * queue or deadline rejects before a command side effect; a started request is never cancelled. */
class RconCommandDeferralTest {

    private static RconCommandDeferral.OwnerUnit unit(String id, List<String> order, String response) {
        return new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
                order.add("prepare:" + id);
            }

            @Override
            public void dispatch() {
                order.add("dispatch:" + id);
            }

            @Override
            public String capture() {
                order.add("capture:" + id);
                return response;
            }
        };
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
    void ownerRunsCompleteUnitInAcceptedOrder() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        List<String> order = new ArrayList<>();
        CountDownLatch firstAccepted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(() -> deferral.await("one",
                unit("one", order, "reply-one"), 5_000L, firstAccepted::countDown));
            assertTrue(firstAccepted.await(5, TimeUnit.SECONDS));
            Future<String> second = workers.submit(() -> deferral.await("two",
                unit("two", order, "reply-two"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 2);
            deferral.pumpOwner();
            assertEquals("reply-one", first.get(5, TimeUnit.SECONDS));
            assertEquals("reply-two", second.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("prepare:one", "dispatch:one", "capture:one",
                "prepare:two", "dispatch:two", "capture:two"), order);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void incompleteLimitRejectsNewWithoutRunningIt() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<String>> accepted = new ArrayList<>();
        try {
            for (int index = 0; index < RconCommandDeferral.MAX_PENDING; index++) {
                String id = "q" + index;
                accepted.add(workers.submit(() -> deferral.await(id, unit(id, new ArrayList<>(), id),
                    5_000L, null)));
            }
            awaitTrue(() -> deferral.queued() >= RconCommandDeferral.MAX_PENDING);
            assertThrows(RconCommandDeferral.Rejected.class,
                () -> deferral.await("overflow", unit("overflow", new ArrayList<>(), "no"), 100L, null));
            while (!deferral.idle()) {
                deferral.pumpOwner();
            }
            for (Future<String> future : accepted) {
                assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("q"));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void byteLimitRejectsBeforeEnqueue() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        String full = "x".repeat(RconCommandDeferral.MAX_PENDING_BYTES);
        try {
            Future<String> first = workers.submit(() -> deferral.await(full,
                unit("bytes", new ArrayList<>(), "ok"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            assertThrows(RconCommandDeferral.Rejected.class,
                () -> deferral.await("y", unit("y", new ArrayList<>(), "no"), 100L, null));
            deferral.pumpOwner();
            assertEquals("ok", first.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void deadlineOnlyAppliesBeforeTheOwnerStarts() {
        RconCommandDeferral deferral = new RconCommandDeferral();
        AtomicInteger ran = new AtomicInteger();
        RconCommandDeferral.OwnerUnit unit = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
                ran.incrementAndGet();
            }

            @Override
            public void dispatch() {
                ran.incrementAndGet();
            }

            @Override
            public String capture() {
                ran.incrementAndGet();
                return "late";
            }
        };
        assertThrows(RconCommandDeferral.Rejected.class,
            () -> deferral.await("late", unit, 20L, null));
        assertEquals(0, ran.get());
        assertEquals(0, deferral.queued());
    }

    @Test
    void ownerUnitCanCancelOrRewriteAndCaptureItsOwnResponse() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicReference<String> lastCommand = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>();
        RconCommandDeferral.OwnerUnit unit = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
                response.set(null);
            }

            @Override
            public void dispatch() {
                if ("cancel".equals(lastCommand.get())) {
                    return;
                }
                if ("rewrite".equals(lastCommand.get())) {
                    lastCommand.set("say rewritten");
                    response.set("rewritten");
                }
            }

            @Override
            public String capture() {
                return response.get();
            }
        };
        try {
            Future<String> cancelled = workers.submit(() -> {
                lastCommand.set("cancel");
                return deferral.await("cancel", unit, 5_000L, null);
            });
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            assertEquals(null, cancelled.get(5, TimeUnit.SECONDS));
            Future<String> rewritten = workers.submit(() -> {
                lastCommand.set("rewrite");
                return deferral.await("rewrite", unit, 5_000L, null);
            });
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            assertEquals("rewritten", rewritten.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void ownerFailureIsReturnedAndTheNextRequestStillRuns() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        RconCommandDeferral.OwnerUnit bad = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
            }

            @Override
            public void dispatch() {
                throw new IllegalStateException("boom");
            }

            @Override
            public String capture() {
                return "never";
            }
        };
        try {
            Future<String> failed = workers.submit(() ->
                deferral.await("bad", bad, 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> failed.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            Future<String> next = workers.submit(() -> deferral.await("ok",
                unit("ok", new ArrayList<>(), "fine"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            assertEquals("fine", next.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void shutdownRejectsQueuedButLetsAStartedRequestFinish() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RconCommandDeferral.OwnerUnit blocking = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
            }

            @Override
            public void dispatch() {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String capture() {
                return "started-done";
            }
        };
        try {
            Future<String> a = workers.submit(() -> deferral.await("a", blocking, 5_000L, null));
            Thread pump = new Thread(deferral::pumpOwner);
            pump.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<String> b = workers.submit(() -> deferral.await("b",
                unit("b", new ArrayList<>(), "b"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.terminateQueued();
            ExecutionException rejected = assertThrows(ExecutionException.class,
                () -> b.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RconCommandDeferral.Rejected.class, rejected.getCause());
            release.countDown();
            pump.join(5_000L);
            assertEquals("started-done", a.get(5, TimeUnit.SECONDS));
            assertThrows(RconCommandDeferral.Rejected.class,
                () -> deferral.await("c", unit("c", new ArrayList<>(), "c"), 100L, null));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void resetRejectsQueuedRequestsAndKeepsTheHandOffOpen() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> queued = workers.submit(() -> deferral.await("reset",
                unit("reset", new ArrayList<>(), "no"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.resetQueued();
            ExecutionException rejected = assertThrows(ExecutionException.class,
                () -> queued.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RconCommandDeferral.Rejected.class, rejected.getCause());
            Future<String> next = workers.submit(() -> deferral.await("next",
                unit("next", new ArrayList<>(), "yes"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            assertEquals("yes", next.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void resetLetsTheStartedRequestFinishAndKeepsTheNewResponseSeparate() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RconCommandDeferral.OwnerUnit first = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
            }

            @Override
            public void dispatch() {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String capture() {
                return "old-response";
            }
        };
        try {
            Future<String> old = workers.submit(() -> deferral.await("old", first, 5_000L, null));
            Thread pump = new Thread(deferral::pumpOwner);
            pump.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            deferral.resetQueued();
            assertEquals(1L, deferral.epoch());
            Future<String> next = workers.submit(() -> deferral.await("next",
                unit("next", new ArrayList<>(), "new-response"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 2);
            release.countDown();
            pump.join(5_000L);
            assertEquals("old-response", old.get(5, TimeUnit.SECONDS));
            assertEquals("new-response", next.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void aReentrantOwnerCallIsRejectedInsteadOfWaitingOnItself() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> nested = new AtomicReference<>();
        RconCommandDeferral.OwnerUnit unit = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
            }

            @Override
            public void dispatch() {
                try {
                    deferral.await("nested", unit("nested", new ArrayList<>(), "nested"), 100L, null);
                } catch (Throwable thrown) {
                    nested.set(thrown);
                }
            }

            @Override
            public String capture() {
                return "outer";
            }
        };
        try {
            Future<String> outer = workers.submit(() -> deferral.await("outer", unit, 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            deferral.pumpOwner();
            assertEquals("outer", outer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RconCommandDeferral.Rejected.class, nested.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void aSecondPumpCannotRunTheOwnerBatchAgain() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicInteger dispatches = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RconCommandDeferral.OwnerUnit unit = new RconCommandDeferral.OwnerUnit() {
            @Override
            public void prepare() {
            }

            @Override
            public void dispatch() {
                dispatches.incrementAndGet();
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String capture() {
                return "once";
            }
        };
        try {
            Future<String> result = workers.submit(() -> deferral.await("once", unit, 5_000L, null));
            awaitTrue(() -> deferral.queued() == 1);
            Thread pump = new Thread(deferral::pumpOwner);
            pump.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            deferral.pumpOwner();
            release.countDown();
            pump.join(5_000L);
            assertEquals("once", result.get(5, TimeUnit.SECONDS));
            assertEquals(1, dispatches.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void acceptedOrderIsOneGlobalFifo() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        List<String> order = new ArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstAccepted = new CountDownLatch(1);
        try {
            Future<String> first = workers.submit(() -> deferral.await("a",
                unit("a", order, "a"), 5_000L, firstAccepted::countDown));
            assertTrue(firstAccepted.await(5, TimeUnit.SECONDS));
            Future<String> second = workers.submit(() -> deferral.await("b",
                unit("b", order, "b"), 5_000L, null));
            awaitTrue(() -> deferral.queued() == 2);
            deferral.pumpOwner();
            assertEquals("a", first.get(5, TimeUnit.SECONDS));
            assertEquals("b", second.get(5, TimeUnit.SECONDS));
            assertEquals("prepare:a", order.get(0));
            assertEquals("prepare:b", order.get(3));
            assertFalse(order.isEmpty());
        } finally {
            workers.shutdownNow();
        }
    }
}
