/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsCpuClock;
import io.izzel.arclight.common.prts.support.PrtsLoadProbe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
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

    /** One finished tick as the boundary read it: how long its body took, how much of that the
     * thread was parked, how much cpu it used, and whether the body was a wait stall or a busy one. */
    public record Boundary(long tick, long bodyWallNanos, long bodyCpuNanos, long bodyParkNanos,
                           long betweenWallNanos, long periodCpuNanos, boolean parkStall,
                           boolean busyStall, long gcCollections, long gcMillis) {
    }

    /** Told about every finished tick, on the thread the tick ran on. */
    public interface BoundaryListener {

        void tickBoundary(Boundary boundary);
    }

    /** One sampled stack of a main thread that stayed runnable inside a tick. */
    public record Watchdog(long atNanos, long bodyNanos, String top) {
    }

    /** Why a wait was entered; UNKNOWN covers a stack the signatures do not name. */
    public static final String DEPENDENCY = "dependency";
    public static final String LOCK = "lock";
    public static final String QUEUE = "queue";
    public static final String OTHER = "other";

    public static final long SAMPLE_INTERVAL_MILLIS = 4L;
    public static final long SAMPLE_INTERVAL_NANOS = SAMPLE_INTERVAL_MILLIS * 1_000_000L;
    public static final long STALL_TICK_NANOS = 50_000_000L;
    public static final int STACK_DEPTH = 16;
    /** A tick whose body is long and whose parked share is below this is a busy stall, not a wait. */
    public static final long BUSY_PARK_SHARE_PERCENT = 50L;
    /** A main thread runnable this long inside one tick is sampled for its stack. */
    public static final long WATCHDOG_AFTER_NANOS = 5_000_000_000L;
    /** Two watchdog samples of the same tick are at least this far apart. */
    public static final long WATCHDOG_EVERY_NANOS = 10_000_000_000L;
    public static final int WATCHDOG_DEPTH = 24;
    public static final int WATCHDOG_RING = 8;
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
    private volatile BoundaryListener boundaryListener;
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final LongAdder watchdogSamples = new LongAdder();
    private final ConcurrentLinkedDeque<Watchdog> watchdogs = new ConcurrentLinkedDeque<>();
    private volatile long gcCollections;
    private volatile long gcMillis;
    private long boundaryTick;
    private boolean boundarySeen;
    private long lastBoundaryNanos;
    private long lastBoundaryCpuNanos;
    private long bodyStartNanos;
    private long bodyEndNanos;
    private long bodyCpuStartNanos;
    private long bodyCpuEndNanos;
    private long lastBodyParkNanos;
    private long lastWatchdogNanos;
    private long busyWindows;
    private long busyTicks;
    private long busyNanos;
    private long busyCpuNanos;
    private long busyParkNanos;
    private long busyMaxNanos;
    private long busyEnteredTick;
    private boolean busyOpen;
    private long tickCount;
    private long tickBodyNanos;
    private long tickCpuNanos;
    private long tickParkNanos;
    private long tickBetweenNanos;
    private long tickPeriodNanos;
    private long tickPeriodCpuNanos;
    private long tickUnaccountedNanos;
    private long lastPeriodWallNanos;
    private long lastPeriodCpuNanos;
    private long lastUnaccountedNanos;
    private long lastBodyWallNanos;
    private long lastBodyCpuNanos;
    private long lastBodyParkNanosReading;
    private long lastBetweenNanos;
    private volatile String watchdogTop = "-";
    private volatile long watchdogAtNanos;
    private volatile long watchdogBodyNanos;

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
        PrtsCpuClock.enable();
        noteGc();
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
        long now = System.nanoTime();
        Thread current = Thread.currentThread();
        if (current != thread) {
            thread = current;
            threadName = current.getName();
        }
        if (!firstTickSeen) {
            firstTickSeen = true;
            firstTickAtNanos = now;
        }
        if (boundarySeen) {
            closeBoundary(now);
        } else {
            boundarySeen = true;
            lastBoundaryCpuNanos = PrtsCpuClock.now();
        }
        boundaryTick++;
        noteGc();
        bodyStartNanos = now;
        lastBoundaryNanos = now;
        bodyCpuStartNanos = PrtsCpuClock.now();
        lastWatchdogNanos = 0L;
        tickWaitNanos.set(0L);
        inTick = true;
    }

    /** Closes the tick that just ended and publishes it to the listener; the wait window of that
     * tick was already read by {@link #noteTick(long)}. */
    private void closeBoundary(long now) {
        long periodWall = now - lastBoundaryNanos;
        long bodyWall = bodyEndNanos - bodyStartNanos;
        long betweenWall = Math.max(0L, periodWall - bodyWall);
        long bodyCpu = bodyCpuStartNanos < 0L || bodyCpuEndNanos < 0L ? -1L
            : bodyCpuEndNanos - bodyCpuStartNanos;
        long cpuNow = PrtsCpuClock.now();
        long periodCpu = cpuNow < 0L || lastBoundaryCpuNanos < 0L ? -1L
            : cpuNow - lastBoundaryCpuNanos;
        lastBoundaryCpuNanos = cpuNow;
        long parked = lastBodyParkNanos;
        long busy = bodyWall - parked;
        boolean parkStall = parked >= STALL_TICK_NANOS;
        boolean busyStall = bodyWall >= STALL_TICK_NANOS
            && busy * 100L >= bodyWall * BUSY_PARK_SHARE_PERCENT;
        if (busyStall) {
            if (!busyOpen) {
                busyOpen = true;
                busyEnteredTick = boundaryTick;
                busyWindows++;
            }
            busyTicks++;
            busyNanos += bodyWall;
            busyParkNanos += parked;
            if (bodyCpu > 0L) {
                busyCpuNanos += bodyCpu;
            }
            if (bodyWall > busyMaxNanos) {
                busyMaxNanos = bodyWall;
            }
        } else {
            busyOpen = false;
        }
        long unaccounted = Math.max(0L, periodWall - bodyWall - betweenWall);
        tickCount++;
        tickBodyNanos += bodyWall;
        tickParkNanos += parked;
        tickBetweenNanos += betweenWall;
        tickPeriodNanos += periodWall;
        tickUnaccountedNanos += unaccounted;
        if (bodyCpu > 0L) {
            tickCpuNanos += bodyCpu;
        }
        if (periodCpu > 0L) {
            tickPeriodCpuNanos += periodCpu;
        }
        lastBodyWallNanos = bodyWall;
        lastBodyCpuNanos = bodyCpu;
        lastBodyParkNanosReading = parked;
        lastBetweenNanos = betweenWall;
        lastPeriodWallNanos = periodWall;
        lastPeriodCpuNanos = periodCpu;
        lastUnaccountedNanos = unaccounted;
        BoundaryListener listener = boundaryListener;
        if (listener != null) {
            try {
                listener.tickBoundary(new Boundary(boundaryTick, bodyWall, bodyCpu, parked,
                    betweenWall, periodCpu, parkStall, busyStall, gcCollections, gcMillis));
            } catch (Throwable ignored) {
                // A listener that throws must not end the observation of the process it watches.
            }
        }
    }

    @Override
    public void tickLeft() {
        long now = System.nanoTime();
        bodyEndNanos = now;
        bodyCpuEndNanos = PrtsCpuClock.now();
        lastBodyParkNanos = tickWaitNanos.get();
        inTick = false;
    }

    private void noteGc() {
        long collections = 0L;
        long millis = 0L;
        for (GarbageCollectorMXBean bean : gcBeans) {
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            if (count > 0L) {
                collections += count;
            }
            if (time > 0L) {
                millis += time;
            }
        }
        gcCollections = collections;
        gcMillis = millis;
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
            sampleWatchdog(bean, watched, info);
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

    /** Samples the stack of a main thread that has been runnable inside one tick long enough to be a
     * busy stall. The interval and the threshold are declared values; the sample is a stack of the
     * moment, not an attribution of the span. */
    private void sampleWatchdog(ThreadMXBean bean, Thread watched, ThreadInfo info) {
        if (info == null || info.getThreadState() != Thread.State.RUNNABLE
            || !firstTickSeen || !inTick) {
            return;
        }
        long now = System.nanoTime();
        long inBody = now - bodyStartNanos;
        if (inBody < WATCHDOG_AFTER_NANOS) {
            return;
        }
        if (lastWatchdogNanos != 0L && now - lastWatchdogNanos < WATCHDOG_EVERY_NANOS) {
            return;
        }
        lastWatchdogNanos = now;
        ThreadInfo deep = bean.getThreadInfo(watched.threadId(), WATCHDOG_DEPTH);
        if (deep == null || deep.getStackTrace() == null) {
            return;
        }
        StackTraceElement[] frames = deep.getStackTrace();
        StringBuilder builder = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : frames) {
            if (kept == 6) {
                break;
            }
            if (kept > 0) {
                builder.append(" | ");
            }
            builder.append(frame.getClassName()).append('.').append(frame.getMethodName());
            kept++;
        }
        watchdogTop = builder.length() == 0 ? "-" : builder.toString();
        watchdogAtNanos = now;
        watchdogBodyNanos = inBody;
        watchdogSamples.increment();
        watchdogs.addLast(new Watchdog(now, inBody, watchdogTop));
        while (watchdogs.size() > WATCHDOG_RING) {
            watchdogs.pollFirst();
        }
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
        watchdogSamples.reset();
        watchdogs.clear();
        watchdogTop = "-";
        watchdogAtNanos = 0L;
        watchdogBodyNanos = 0L;
        boundaryTick = 0L;
        boundarySeen = false;
        lastBoundaryNanos = 0L;
        lastBoundaryCpuNanos = 0L;
        bodyStartNanos = 0L;
        bodyEndNanos = 0L;
        bodyCpuStartNanos = 0L;
        bodyCpuEndNanos = 0L;
        lastBodyParkNanos = 0L;
        lastWatchdogNanos = 0L;
        busyWindows = 0L;
        busyTicks = 0L;
        busyNanos = 0L;
        busyCpuNanos = 0L;
        busyParkNanos = 0L;
        busyMaxNanos = 0L;
        busyEnteredTick = 0L;
        busyOpen = false;
        tickCount = 0L;
        tickBodyNanos = 0L;
        tickCpuNanos = 0L;
        tickParkNanos = 0L;
        tickBetweenNanos = 0L;
        tickPeriodNanos = 0L;
        tickPeriodCpuNanos = 0L;
        tickUnaccountedNanos = 0L;
        lastBodyWallNanos = 0L;
        lastBodyCpuNanos = 0L;
        lastBodyParkNanosReading = 0L;
        lastBetweenNanos = 0L;
        lastPeriodWallNanos = 0L;
        lastPeriodCpuNanos = 0L;
        lastUnaccountedNanos = 0L;
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

    public void boundaryListener(BoundaryListener listener) {
        this.boundaryListener = listener;
    }

    public long boundaryTick() {
        return boundaryTick;
    }

    public long tickCount() {
        return tickCount;
    }

    public long tickBodyNanos() {
        return tickBodyNanos;
    }

    public long tickCpuNanos() {
        return tickCpuNanos;
    }

    public long tickParkNanos() {
        return tickParkNanos;
    }

    public long tickBetweenNanos() {
        return tickBetweenNanos;
    }

    public long tickPeriodNanos() {
        return tickPeriodNanos;
    }

    public long tickPeriodCpuNanos() {
        return tickPeriodCpuNanos;
    }

    public long tickUnaccountedNanos() {
        return tickUnaccountedNanos;
    }

    public long lastPeriodWallNanos() {
        return lastPeriodWallNanos;
    }

    public long lastUnaccountedNanos() {
        return lastUnaccountedNanos;
    }

    public long lastBodyWallNanos() {
        return lastBodyWallNanos;
    }

    public long lastBodyCpuNanos() {
        return lastBodyCpuNanos;
    }

    public long lastBodyParkNanos() {
        return lastBodyParkNanosReading;
    }

    public long lastBetweenNanos() {
        return lastBetweenNanos;
    }

    public long gcCollections() {
        return gcCollections;
    }

    public long gcMillis() {
        return gcMillis;
    }

    public long busyWindows() {
        return busyWindows;
    }

    public long busyTicks() {
        return busyTicks;
    }

    public long busyNanos() {
        return busyNanos;
    }

    public long busyCpuNanos() {
        return busyCpuNanos;
    }

    public long busyParkNanos() {
        return busyParkNanos;
    }

    public long busyMaxNanos() {
        return busyMaxNanos;
    }

    public long busyEnteredTick() {
        return busyEnteredTick;
    }

    public boolean busyInWindow() {
        return busyOpen;
    }

    public long watchdogSamples() {
        return watchdogSamples.sum();
    }

    public String watchdogTop() {
        return watchdogTop;
    }

    public long watchdogBodyNanos() {
        return watchdogBodyNanos;
    }

    public long watchdogAgeNanos() {
        return watchdogAtNanos == 0L ? -1L : System.nanoTime() - watchdogAtNanos;
    }

    /** @return the newest watchdog samples, oldest first */
    public List<Watchdog> watchdogs() {
        return List.copyOf(watchdogs);
    }

    public boolean cpuSupported() {
        return PrtsCpuClock.supported();
    }
}
