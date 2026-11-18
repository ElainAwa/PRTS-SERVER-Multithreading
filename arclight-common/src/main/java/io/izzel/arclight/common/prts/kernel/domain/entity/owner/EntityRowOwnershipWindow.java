/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The internal row ownership window: the main thread opens it and hands exactly one owner thread
 * the rows it may touch. After the handoff main is outside; an access from main is a violation and
 * the owner may touch only the carried rows. A wait timeout quarantines the window without releasing
 * the slot; only the owner closes a handed-off/quarantined window, and only main closes a new one. */
public final class EntityRowOwnershipWindow {

    /** The internal property; not a configuration key and off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.entityRowOwner";

    /** The lifecycle of one window. */
    public enum State {
        NEW,
        HANDED_OFF,
        QUARANTINED,
        CLOSED,
        FAILED
    }

    private static final AtomicReference<EntityRowOwnershipWindow> ACTIVE =
        new AtomicReference<>();

    private final Thread mainThread;
    private final Set<Integer> rows;
    private final AtomicInteger violations = new AtomicInteger();
    private final AtomicBoolean fallbackUsed = new AtomicBoolean();
    private volatile Thread ownerThread;
    private volatile State state = State.NEW;

    private EntityRowOwnershipWindow(Thread mainThread, int[] rows) {
        this.mainThread = mainThread;
        Set<Integer> unique = new LinkedHashSet<>();
        for (int row : rows) {
            unique.add(row);
        }
        this.rows = Set.copyOf(unique);
    }

    /** Whether the row ownership property is declared; false is the only shipped value. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Opens one window for the named main thread; only that thread may open, hand off or close a
     * NEW window, and only one window is open per process. */
    public static EntityRowOwnershipWindow open(Thread mainThread, int... rows) {
        if (!enabled()) {
            throw new IllegalStateException("entity row owner path is disabled");
        }
        if (mainThread == null) {
            throw new IllegalArgumentException("a window needs its main thread");
        }
        if (Thread.currentThread() != mainThread) {
            throw new IllegalStateException("only the named main thread opens a window");
        }
        if (rows == null || rows.length == 0) {
            throw new IllegalArgumentException("a window needs at least one row");
        }
        EntityRowOwnershipWindow window = new EntityRowOwnershipWindow(mainThread,
            Arrays.copyOf(rows, rows.length));
        if (!ACTIVE.compareAndSet(null, window)) {
            throw new IllegalStateException("another row ownership window is open");
        }
        return window;
    }

    /** Hands the window to one owner thread; only the named main thread may hand it off. */
    public synchronized void handOff(Thread owner) {
        requireEnabled();
        if (Thread.currentThread() != mainThread) {
            throw new IllegalStateException("only the main thread hands off a window");
        }
        if (owner == null || owner == mainThread) {
            throw new IllegalArgumentException("the owner must be another thread");
        }
        if (state != State.NEW) {
            throw new IllegalStateException("the window was already handed off");
        }
        ownerThread = owner;
        state = State.HANDED_OFF;
    }

    /** Checks that the current thread may touch one row; any other access is a violation. */
    public void assertAccess(int row) {
        if (!rows.contains(row)) {
            violation("row " + row + " is not in this window");
        }
        State snapshot = state;
        Thread current = Thread.currentThread();
        if (snapshot == State.NEW) {
            if (current != mainThread) {
                violation("owner touched the row before the handoff");
            }
            return;
        }
        if (snapshot != State.HANDED_OFF && snapshot != State.QUARANTINED) {
            violation("row touched after the window closed");
        }
        if (current != ownerThread) {
            violation("row touched by a thread outside the owner window");
        }
    }

    /** Checks that the calling thread is the owner of one row in a handed-off or quarantined
     * window. */
    public void assertOwnerRow(int row) {
        assertAccess(row);
        if (Thread.currentThread() != ownerThread
            || (state != State.HANDED_OFF && state != State.QUARANTINED)) {
            violation("owner row check outside the owner window");
        }
    }

    /** Main after the handoff is outside the window; this is the explicit detector path. */
    public void assertMainOutside() {
        if (Thread.currentThread() == mainThread
            && (state == State.HANDED_OFF || state == State.QUARANTINED)) {
            violation("main touched the level while the owner window was open");
        }
    }

    /** Quarantines without releasing the slot; owner-only and idempotent after a main timeout. */
    public synchronized void quarantine(String reason) {
        requireEnabled();
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("only the owner quarantines a window");
        }
        if (state == State.QUARANTINED) {
            return;
        }
        if (state != State.HANDED_OFF) {
            throw new IllegalStateException("the window cannot be quarantined in state " + state);
        }
        state = State.QUARANTINED;
        notifyAll();
    }

    /** Fails a NEW window from main or a HANDED_OFF window from its owner, consuming one ticket.
     * The terminal state is written before the slot is released, so an open window always observes
     * a terminal predecessor. */
    public synchronized boolean failOnce(String reason) {
        requireEnabled();
        Thread current = Thread.currentThread();
        if (state == State.NEW) {
            if (current != mainThread) {
                throw new IllegalStateException("only main fails a new window");
            }
        } else if (state == State.HANDED_OFF) {
            if (current != ownerThread) {
                throw new IllegalStateException("only the owner fails a handed-off window");
            }
        } else {
            throw new IllegalStateException("the window cannot fail in state " + state);
        }
        if (!fallbackUsed.compareAndSet(false, true)) {
            return false;
        }
        state = State.FAILED;
        ACTIVE.compareAndSet(this, null);
        notifyAll();
        return true;
    }

    /** Blocks main until the owner closes or fails; a timeout quarantines without releasing the
     * slot and returns false, so the caller must not fall back while the owner may still write. */
    public synchronized boolean awaitOwner(long timeoutMillis) {
        requireEnabled();
        if (Thread.currentThread() != mainThread) {
            throw new IllegalStateException("only the main thread awaits the owner");
        }
        if (state == State.NEW) {
            throw new IllegalStateException("the window was not handed off");
        }
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.FAILED) {
            return false;
        }
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        try {
            while (state == State.HANDED_OFF) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    state = State.QUARANTINED;
                    notifyAll();
                    return false;
                }
                wait(Math.max(1L, remaining / 1_000_000L));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state = State.QUARANTINED;
            notifyAll();
            return false;
        }
        return state == State.CLOSED;
    }

    /** Closes the window and releases the slot. A NEW window closes from main; a handed-off or
     * quarantined window closes only from its owner. Closing a closed/failed window is a no-op.
     * The CLOSED state is written before the slot release: ACTIVE empty implies a terminal window. */
    public synchronized void close() {
        if (state == State.CLOSED || state == State.FAILED) {
            return;
        }
        Thread current = Thread.currentThread();
        if (state == State.NEW) {
            if (current != mainThread) {
                throw new IllegalStateException("only main closes a new window");
            }
        } else if (current != ownerThread) {
            throw new IllegalStateException("only the owner closes a handed-off window");
        }
        ownerThread = null;
        state = State.CLOSED;
        ACTIVE.compareAndSet(this, null);
        notifyAll();
    }

    /** The current lifecycle state; a closed window never reopens. */
    public State state() {
        return state;
    }

    /** Whether the window carries one row; the row set is fixed at open time. */
    public boolean owns(int row) {
        return rows.contains(row);
    }

    /** The owner thread after the handoff, or null before it and after the close. */
    public Thread ownerThread() {
        return ownerThread;
    }

    /** The number of access/ownership violations this window detected. */
    public int violations() {
        return violations.get();
    }

    private void violation(String message) {
        violations.incrementAndGet();
        throw new IllegalStateException(message);
    }

    private static void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("entity row owner path is disabled");
        }
    }
}
