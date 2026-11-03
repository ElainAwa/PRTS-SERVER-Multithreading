/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The internal bounded FIFO backlog of one event stream: an event is accepted for a world bound to
 * its epoch, while the queue has room and only once while queued. A full backlog refuses explicitly
 * and raises the event queue rung; unload, rebind and reset drop what they cover without reordering
 * the rest. Nothing dispatches through it and no readout key names it. */
public final class EventBacklog {

    /** How many worlds may be bound at once; a new world past the bound is refused. */
    public static final int MAX_WORLDS = 64;

    /** The rung a full backlog signals: the event queue rung of the existing ladder. */
    public static DegradeLevel b4Rung() {
        return DegradeLevel.B4;
    }

    /** The refusal code the event queue rung of the existing ladder carries. */
    public static RejectCode b4Code() {
        return RejectCode.QUEUE_CAP_EXCEEDED;
    }

    /** One queued event: its acceptance sequence, the world and epoch it belongs to and the caller
     * identity it is deduplicated by. */
    public record Event(long sequence, String worldId, long epoch, String key) {
    }

    /** Why one enqueue did not become a queued event; {@link #ACCEPTED} is not a refusal. */
    public enum Rejection {
        ACCEPTED,
        FULL,
        WRONG_WORLD,
        STALE_EPOCH,
        WRONG_EPOCH,
        DUPLICATE,
        INVALID
    }

    /** What one enqueue did: the answer, the accepted sequence and whether the rung was raised. */
    public record Result(Rejection rejection, long sequence, boolean b4Signal) {

        /** Whether the event entered the queue. */
        public boolean accepted() {
            return rejection == Rejection.ACCEPTED;
        }
    }

    /** A bounded internal reading: depth, bindings and the accepted, drained and refused counts. */
    public record Snapshot(int capacity, int depth, int boundWorlds, long accepted, long drained,
                           long rejectedFull, long rejectedWrongWorld, long rejectedStaleEpoch,
                           long rejectedWrongEpoch, long rejectedDuplicate, long rejectedInvalid,
                           long rejectedBindings, long droppedByForget, long droppedByRebind,
                           long b4Signals, long firstSequence, long lastSequence) {

        /** Whether the queue holds no room for one more event. */
        public boolean atCapacity() {
            return depth >= capacity;
        }

        /** Whether a full backlog raised the event queue rung at least once. */
        public boolean b4Raised() {
            return b4Signals > 0L;
        }
    }

    private final int capacity;
    private final DegradeLadder ladder;
    private final Deque<Event> queue = new ArrayDeque<>();
    private final Map<String, Long> worlds = new LinkedHashMap<>();
    private long nextSequence = 1L;
    private long accepted;
    private long drained;
    private long rejectedFull;
    private long rejectedWrongWorld;
    private long rejectedStaleEpoch;
    private long rejectedWrongEpoch;
    private long rejectedDuplicate;
    private long rejectedInvalid;
    private long rejectedBindings;
    private long droppedByForget;
    private long droppedByRebind;
    private long b4Signals;

    /** Opens a backlog of one capacity and no ladder: a full queue is refused and counted, and no
     * rung is raised. */
    public EventBacklog(int capacity) {
        this(capacity, null);
    }

    /** Opens a backlog of one capacity that raises the event queue rung of the given ladder when the
     * queue is full. */
    public EventBacklog(int capacity, DegradeLadder ladder) {
        if (capacity < 1) {
            throw new IllegalArgumentException("an event backlog needs a positive capacity");
        }
        this.capacity = capacity;
        this.ladder = ladder;
    }

    /** Binds one world to its epoch. A world already bound to an older epoch is rebound to the newer
     * one and the events of the older one are dropped; a bind at an older epoch is refused. */
    public synchronized boolean bindWorld(String worldId, long epoch) {
        if (worldId == null || worldId.isBlank()) {
            return false;
        }
        Long held = worlds.get(worldId);
        if (held == null && worlds.size() >= MAX_WORLDS) {
            rejectedBindings++;
            return false;
        }
        if (held != null && epoch < held) {
            rejectedBindings++;
            return false;
        }
        if (held != null && epoch > held) {
            droppedByRebind += removeWorldEvents(worldId);
        }
        worlds.put(worldId, epoch);
        return true;
    }

    /** Drops every queued event of one world, as an unload does; the relative order of the events
     * that stay is unchanged. */
    public synchronized int forgetWorld(String worldId) {
        if (worldId == null) {
            return 0;
        }
        worlds.remove(worldId);
        int dropped = removeWorldEvents(worldId);
        droppedByForget += dropped;
        return dropped;
    }

    /** Clears the queue, every binding and every counter. */
    public synchronized void reset() {
        queue.clear();
        worlds.clear();
        nextSequence = 1L;
        accepted = 0L;
        drained = 0L;
        rejectedFull = 0L;
        rejectedWrongWorld = 0L;
        rejectedStaleEpoch = 0L;
        rejectedWrongEpoch = 0L;
        rejectedDuplicate = 0L;
        rejectedInvalid = 0L;
        rejectedBindings = 0L;
        droppedByForget = 0L;
        droppedByRebind = 0L;
        b4Signals = 0L;
    }

    /** Queues one event of a bound world and epoch. A full queue refuses instead of growing and
     * raises the event queue rung. */
    public synchronized Result enqueue(String worldId, long epoch, String key, long tickIndex) {
        if (worldId == null || worldId.isBlank() || key == null || key.isBlank()) {
            rejectedInvalid++;
            return new Result(Rejection.INVALID, -1L, false);
        }
        Long held = worlds.get(worldId);
        if (held == null) {
            rejectedWrongWorld++;
            return new Result(Rejection.WRONG_WORLD, -1L, false);
        }
        if (epoch < held) {
            rejectedStaleEpoch++;
            return new Result(Rejection.STALE_EPOCH, -1L, false);
        }
        if (epoch > held) {
            rejectedWrongEpoch++;
            return new Result(Rejection.WRONG_EPOCH, -1L, false);
        }
        if (queue.size() >= capacity) {
            rejectedFull++;
            b4Signals++;
            if (ladder != null) {
                ladder.noteEntered(b4Rung(), tickIndex);
            }
            return new Result(Rejection.FULL, -1L, true);
        }
        if (queued(worldId, epoch, key)) {
            rejectedDuplicate++;
            return new Result(Rejection.DUPLICATE, -1L, false);
        }
        Event event = new Event(nextSequence++, worldId, epoch, key);
        queue.addLast(event);
        accepted++;
        return new Result(Rejection.ACCEPTED, event.sequence(), false);
    }

    /** Takes up to the given number of events from the front of the queue, in acceptance order. */
    public synchronized List<Event> drain(int limit) {
        int count = Math.min(Math.max(0, limit), queue.size());
        List<Event> drainedEvents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            drainedEvents.add(queue.removeFirst());
        }
        drained += count;
        return drainedEvents;
    }

    /** Copies up to the given number of events from the front of the queue without removing them;
     * the bound is the queue depth, never more. */
    public synchronized List<Event> peek(int limit) {
        int count = Math.min(Math.max(0, limit), queue.size());
        List<Event> events = new ArrayList<>(count);
        Iterator<Event> iterator = queue.iterator();
        for (int index = 0; index < count && iterator.hasNext(); index++) {
            events.add(iterator.next());
        }
        return events;
    }

    /** Reads the bounded internal snapshot of the backlog. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(capacity, queue.size(), worlds.size(), accepted, drained,
            rejectedFull, rejectedWrongWorld, rejectedStaleEpoch, rejectedWrongEpoch,
            rejectedDuplicate, rejectedInvalid, rejectedBindings, droppedByForget,
            droppedByRebind, b4Signals, queue.isEmpty() ? -1L : queue.peekFirst().sequence(),
            queue.isEmpty() ? -1L : queue.peekLast().sequence());
    }

    /** Whether one event with the same world, epoch and identity is still queued; the scan is
     * bounded by the queue capacity. */
    private boolean queued(String worldId, long epoch, String key) {
        for (Event event : queue) {
            if (event.epoch() == epoch && event.worldId().equals(worldId)
                && event.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private int removeWorldEvents(String worldId) {
        int dropped = 0;
        Iterator<Event> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().worldId().equals(worldId)) {
                iterator.remove();
                dropped++;
            }
        }
        return dropped;
    }
}
