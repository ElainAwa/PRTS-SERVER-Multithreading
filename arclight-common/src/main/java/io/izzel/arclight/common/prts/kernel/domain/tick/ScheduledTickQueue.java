/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.tick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The causal queue of scheduled block and fluid ticks: a tick is named by its world incarnation,
 * block position, trigger tick, priority and sub-tick order; the due order is trigger tick, then
 * priority, then sub-tick order and never the order a worker thread happened to submit; a cancel or
 * reschedule is a final state, a replaced target block is a legal no-op, and a due tick executes
 * exactly once. */
public final class ScheduledTickQueue {

    /** One scheduled tick; the comparable order is the causal order of the queue. */
    public record Key(long worldIncarnation, long blockPos, long triggerTick, int priority,
                      long subTickOrder) implements Comparable<Key> {

        public Key {
            if (worldIncarnation < 0L || triggerTick < 0L || subTickOrder < 0L) {
                throw new IllegalArgumentException("a scheduled tick cannot name a negative lineage");
            }
        }

        @Override
        public int compareTo(Key other) {
            int tick = Long.compare(triggerTick, other.triggerTick);
            if (tick != 0) {
                return tick;
            }
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(subTickOrder, other.subTickOrder);
        }
    }

    /** The terminal state of one scheduled tick. */
    public enum State {
        PENDING,
        EXECUTED,
        CANCELLED,
        RESCHEDULED,
        TARGET_REPLACED
    }

    /** One scheduled entry; the target type and generation decide a replaced-block no-op. */
    public record Entry(Key key, String targetTypeId, long targetGeneration, State state) {
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private long executed;
    private long cancelled;
    private long rescheduled;
    private long replaced;

    /** Schedules one tick; a duplicate key is idempotent and never replaces the live entry. */
    public synchronized boolean schedule(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        Entry existing = entries.get(entry.key());
        if (existing != null && existing.state() == State.PENDING) {
            return false;
        }
        entries.put(entry.key(), new Entry(entry.key(), entry.targetTypeId(),
            entry.targetGeneration(), State.PENDING));
        return true;
    }

    /** Cancels a pending tick; an executed tick is a final state and is not cancelled. */
    public synchronized boolean cancel(Key key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.state() != State.PENDING) {
            return false;
        }
        entries.put(key, new Entry(entry.key(), entry.targetTypeId(), entry.targetGeneration(),
            State.CANCELLED));
        cancelled++;
        return true;
    }

    /** Moves a pending tick to the final rescheduled state; the new entry is scheduled separately. */
    public synchronized boolean reschedule(Key oldKey, Entry replacement) {
        Entry entry = entries.get(oldKey);
        if (entry == null || entry.state() != State.PENDING) {
            return false;
        }
        entries.put(oldKey, new Entry(entry.key(), entry.targetTypeId(), entry.targetGeneration(),
            State.RESCHEDULED));
        rescheduled++;
        return schedule(replacement);
    }

    /** The pending entries due at one tick, in causal order, independent of submission order. */
    public synchronized List<Entry> due(long currentTick) {
        List<Entry> due = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.state() == State.PENDING && entry.key().triggerTick() <= currentTick) {
                due.add(entry);
            }
        }
        due.sort(Comparator.comparing(Entry::key));
        return List.copyOf(due);
    }

    /** Executes the due ticks exactly once in causal order; a replaced target is a legal no-op. */
    public synchronized int executeDue(long currentTick, java.util.function.Predicate<Entry> targetAlive,
                                       java.util.function.Consumer<Entry> action) {
        Objects.requireNonNull(targetAlive, "targetAlive");
        Objects.requireNonNull(action, "action");
        int ran = 0;
        for (Entry entry : due(currentTick)) {
            if (!targetAlive.test(entry)) {
                entries.put(entry.key(), new Entry(entry.key(), entry.targetTypeId(),
                    entry.targetGeneration(), State.TARGET_REPLACED));
                replaced++;
                continue;
            }
            entries.put(entry.key(), new Entry(entry.key(), entry.targetTypeId(),
                entry.targetGeneration(), State.EXECUTED));
            executed++;
            ran++;
            action.accept(entry);
        }
        return ran;
    }

    public synchronized State state(Key key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.state();
    }

    public synchronized long executed() {
        return executed;
    }

    public synchronized long cancelled() {
        return cancelled;
    }

    public synchronized long rescheduled() {
        return rescheduled;
    }

    public synchronized long replaced() {
        return replaced;
    }
}
