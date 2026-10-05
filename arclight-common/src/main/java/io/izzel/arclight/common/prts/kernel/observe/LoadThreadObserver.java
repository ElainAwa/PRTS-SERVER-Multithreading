/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsLoadProbe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Watches the thread states of a loaded server: how long the server thread waits inside a tick and
 * how long it waits between two ticks, what it waits on, and how much of the worker pool is running.
 *
 * <p>A sampler thread of its own reads the states; the server thread is never instrumented, so the
 * observation cannot slow a wait down or hold a lock. Sampling cannot see a wait shorter than its
 * interval and classifies a wait by the frames on the stack when the wait started, so the class is a
 * signature heuristic and not an attribution. The interval and the stall threshold are declared
 * values, not measured ones.
 */
public final class LoadThreadObserver implements PrtsLoadProbe.Probe {

    /** Why a wait was entered; UNKNOWN covers a stack the signatures do not name. */
    public static final String DEPENDENCY = "dependency";
    public static final String LOCK = "lock";
    public static final String QUEUE = "queue";
    public static final String OTHER = "other";

    public static final long SAMPLE_INTERVAL_MILLIS = 4L;
    public static final long SAMPLE_INTERVAL_NANOS = SAMPLE_INTERVAL_MILLIS * 1_000_000L;
    public static final long STALL_TICK_NANOS = 50_000_000L;
    public static final int STACK_DEPTH = 16;
    private static final int WORKER_EVERY = 25;

    private static final String[] DEPENDENCY_FRAMES = {
        "net.minecraft.server.level.ServerChunkCache",
        "net.minecraft.server.level.ChunkMap",
        "net.minecraft.server.level.ChunkHolder",
        "net.minecraft.server.level.ChunkTaskPriorityQueue",
        "net.minecraft.server.level.ChunkGenerationTask",
        "net.minecraft.server.level.DistanceManager",
        "net.minecraft.world.level.chunk.status.ChunkStatus",
        "net.minecraft.world.level.chunk.ChunkAccess",
        "net.minecraft.world.level.chunk.ChunkGenerator",
        "net.minecraft.world.level.chunk.storage.ChunkStorage"
    };
    private static final String[] QUEUE_FRAMES = {
        "net.minecraft.util.thread.ProcessorMailbox",
        "net.minecraft.util.thread.BlockableEventLoop",
        "net.minecraft.util.thread.ProcessorHandle",
        "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor",
        "java.util.concurrent.ForkJoinPool",
        "java.util.concurrent.ThreadPoolExecutor",
        "java.util.concurrent.CompletableFuture",
        "java.util.concurrent.CountDownLatch"
    };

    private static final String WORKER_NAME = "Worker";

    private final LongAdder samples = new LongAdder();
    private final LongAdder parkFrames = new LongAdder();
    private final LongAdder parkInTickFrames = new LongAdder();
    private final LongAdder parkBetweenTickFrames = new LongAdder();
    private final LongAdder parkStartupFrames = new LongAdder();
    private final LongAdder episodes = new LongAdder();
    private final LongAdder dependencyFrames = new LongAdder();
    private final LongAdder queueFrames = new LongAdder();
    private final LongAdder lockFrames = new LongAdder();
    private final LongAdder otherFrames = new LongAdder();
    private final LongAdder workerSamples = new LongAdder();
    private final LongAdder workerBusyFrames = new LongAdder();

    private volatile Thread thread;
    private volatile String threadName = "-";
    private volatile boolean running;
    private volatile boolean installed;
    private volatile boolean inTick;
    private volatile boolean firstTickSeen;
    private volatile String episodeClass;
    private volatile long episodeFrames;
    private volatile long maxEpisodeFrames;
    private volatile long startedAtNanos;
    private volatile long firstTickAtNanos;
    private volatile int workerThreadsPeak;
    private volatile int workerThreads;
    /** Written by the sampler thread, drained by the server thread at the tick boundary. */
    private final AtomicLong tickWaitNanos = new AtomicLong();
    private volatile long startupParkNanos;
    private volatile long betweenParkNanos;
    private long stallWindows;
    private long stallTicks;
    private long stallNanos;
    private long stallMaxNanos;
    private long stallFrames;
    private long stallEnteredTick;
    private boolean stallOpen;

    /** Starts the sampler on the thread that calls it; the server reports its own tick boundary so a
     * later caller is corrected on the first tick. */
    public synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        running = true;
        thread = Thread.currentThread();
        threadName = thread.getName();
        startedAtNanos = System.nanoTime();
        PrtsLoadProbe.install(this);
        Thread sampler = new Thread(this::loop, "prts-load-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    public synchronized void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        running = false;
        PrtsLoadProbe.install(null);
        inTick = false;
        episodeClass = null;
    }

    public boolean installed() {
        return installed;
    }

    @Override
    public void tickEntered() {
        Thread current = Thread.currentThread();
        if (current != thread) {
            thread = current;
            threadName = current.getName();
        }
        if (!firstTickSeen) {
            firstTickSeen = true;
            firstTickAtNanos = System.nanoTime();
        }
        tickWaitNanos.set(0L);
        inTick = true;
    }

    @Override
    public void tickLeft() {
        inTick = false;
    }

    /** Closes the wait window of the tick that just ended and opens a stall window if it was long
     * enough to be one; consecutive stalled ticks are one window. */
    public synchronized void noteTick(long tickIndex) {
        long nanos = tickWaitNanos.getAndSet(0L);
        if (nanos >= STALL_TICK_NANOS) {
            if (!stallOpen) {
                stallOpen = true;
                stallEnteredTick = tickIndex;
                stallWindows++;
            }
            stallTicks++;
            stallNanos += nanos;
            stallFrames += nanos / SAMPLE_INTERVAL_NANOS;
            if (nanos > stallMaxNanos) {
                stallMaxNanos = nanos;
            }
        } else {
            stallOpen = false;
        }
    }

    /** Adds one sampled wait to the tick in progress; the sampler and the tests both call it. */
    void noteTickWaitNanos(long nanos) {
        tickWaitNanos.addAndGet(nanos);
    }

    /** Classifies a wait by the frames on the stack; dependency wins over lock, lock over queue. */
    static String classify(StackTraceElement[] frames) {
        boolean lock = false;
        boolean queue = false;
        for (StackTraceElement frame : frames) {
            String name = frame.getClassName();
            if (matches(name, DEPENDENCY_FRAMES)) {
                return DEPENDENCY;
            }
            if (name.startsWith("java.util.concurrent.locks.")) {
                lock = true;
            }
            if (matches(name, QUEUE_FRAMES)) {
                queue = true;
            }
        }
        if (lock) {
            return LOCK;
        }
        return queue ? QUEUE : OTHER;
    }

    private static boolean matches(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void loop() {
        long ticks = 0L;
        while (running) {
            try {
                sample();
                if (++ticks % WORKER_EVERY == 0L) {
                    sampleWorkers();
                }
            } catch (Throwable ignored) {
                // A sampler that throws must not end the observation of the process it watches.
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    private void sample() {
        Thread watched = thread;
        if (watched == null) {
            return;
        }
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo info = bean.getThreadInfo(watched.threadId(), 0);
        samples.increment();
        if (info == null) {
            return;
        }
        Thread.State state = info.getThreadState();
        boolean waiting = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
            || state == Thread.State.BLOCKED;
        if (!waiting) {
            closeEpisode();
            return;
        }
        String waitClass = episodeClass;
        if (waitClass == null) {
            StackTraceElement[] frames = stackOf(bean, watched.threadId());
            waitClass = state == Thread.State.BLOCKED ? LOCK : classify(frames);
            episodeClass = waitClass;
            episodes.increment();
        }
        episodeFrames++;
        if (episodeFrames > maxEpisodeFrames) {
            maxEpisodeFrames = episodeFrames;
        }
        parkFrames.increment();
        if (!firstTickSeen) {
            parkStartupFrames.increment();
            startupParkNanos += SAMPLE_INTERVAL_NANOS;
            return;
        }
        if (inTick) {
            parkInTickFrames.increment();
            noteTickWaitNanos(SAMPLE_INTERVAL_NANOS);
            switch (waitClass) {
                case DEPENDENCY -> dependencyFrames.increment();
                case QUEUE -> queueFrames.increment();
                case LOCK -> lockFrames.increment();
                default -> otherFrames.increment();
            }
            return;
        }
        parkBetweenTickFrames.increment();
        betweenParkNanos += SAMPLE_INTERVAL_NANOS;
    }

    private StackTraceElement[] stackOf(ThreadMXBean bean, long threadId) {
        ThreadInfo deep = bean.getThreadInfo(threadId, STACK_DEPTH);
        return deep == null ? new StackTraceElement[0] : deep.getStackTrace();
    }

    private void closeEpisode() {
        episodeClass = null;
        episodeFrames = 0L;
    }

    private void sampleWorkers() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] ids = bean.getAllThreadIds();
        ThreadInfo[] infos = bean.getThreadInfo(ids, 0);
        int workers = 0;
        int busy = 0;
        for (ThreadInfo info : infos) {
            if (info == null || !info.getThreadName().contains(WORKER_NAME)) {
                continue;
            }
            workers++;
            if (info.getThreadState() == Thread.State.RUNNABLE) {
                busy++;
            }
        }
        workerSamples.add(workers);
        workerBusyFrames.add(busy);
        workerThreads = workers;
        if (workers > workerThreadsPeak) {
            workerThreadsPeak = workers;
        }
    }

    /** Clears the counters; a running sampler keeps sampling. */
    public synchronized void reset() {
        samples.reset();
        parkFrames.reset();
        parkInTickFrames.reset();
        parkBetweenTickFrames.reset();
        parkStartupFrames.reset();
        episodes.reset();
        dependencyFrames.reset();
        queueFrames.reset();
        lockFrames.reset();
        otherFrames.reset();
        workerSamples.reset();
        workerBusyFrames.reset();
        tickWaitNanos.set(0L);
        startupParkNanos = 0L;
        betweenParkNanos = 0L;
        stallWindows = 0L;
        stallTicks = 0L;
        stallNanos = 0L;
        stallMaxNanos = 0L;
        stallFrames = 0L;
        stallEnteredTick = 0L;
        stallOpen = false;
        episodeClass = null;
        episodeFrames = 0L;
        maxEpisodeFrames = 0L;
        startedAtNanos = System.nanoTime();
        firstTickAtNanos = 0L;
        firstTickSeen = false;
        workerThreadsPeak = 0;
        workerThreads = 0;
    }

    public long samples() {
        return samples.sum();
    }

    public long parkFrames() {
        return parkFrames.sum();
    }

    public long parkInTickFrames() {
        return parkInTickFrames.sum();
    }

    public long parkBetweenTickFrames() {
        return parkBetweenTickFrames.sum();
    }

    public long parkStartupFrames() {
        return parkStartupFrames.sum();
    }

    public long episodes() {
        return episodes.sum();
    }

    public long dependencyFrames() {
        return dependencyFrames.sum();
    }

    public long queueFrames() {
        return queueFrames.sum();
    }

    public long lockFrames() {
        return lockFrames.sum();
    }

    public long otherFrames() {
        return otherFrames.sum();
    }

    public long parkInTickNanos() {
        return parkInTickFrames.sum() * SAMPLE_INTERVAL_NANOS;
    }

    public long parkBetweenTickNanos() {
        return betweenParkNanos;
    }

    public long parkStartupNanos() {
        return startupParkNanos;
    }

    public long maxEpisodeNanos() {
        return maxEpisodeFrames * SAMPLE_INTERVAL_NANOS;
    }

    public long stallWindows() {
        return stallWindows;
    }

    public long stallTicks() {
        return stallTicks;
    }

    public long stallNanos() {
        return stallNanos;
    }

    public long stallMaxNanos() {
        return stallMaxNanos;
    }

    public long stallFrames() {
        return stallFrames;
    }

    public long stallEnteredTick() {
        return stallEnteredTick;
    }

    public boolean stallInWindow() {
        return stallOpen;
    }

    public boolean startupStalled() {
        return startupParkNanos >= STALL_TICK_NANOS;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    public long firstTickNanos() {
        return firstTickAtNanos;
    }

    public boolean firstTickSeen() {
        return firstTickSeen;
    }

    public String threadName() {
        return threadName;
    }

    public long workerSamples() {
        return workerSamples.sum();
    }

    public long workerBusyFrames() {
        return workerBusyFrames.sum();
    }

    public int workerThreads() {
        return workerThreads;
    }

    public int workerThreadsPeak() {
        return workerThreadsPeak;
    }
}
