/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects what the chunk pipeline's mailboxes were handed and what they took out, with the queue
 * depth the mailbox itself reported at those two points.
 *
 * <p>The counts are work items at mailbox granularity, not world changes: a submission is one item
 * handed to a named mailbox and a completion is one item that mailbox took out. The depth is the
 * sum of the mailbox queue sizes as they were last seen, so it is a sampled depth and not a maximum
 * over every instant.
 */
public final class ChunkFlowObserver implements PrtsChunkFlow.FlowTap {

    private static final class Counters {

        private final LongAdder submitted = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private volatile int depth;
        private volatile int depthPeak;
    }

    private final Map<String, Counters> mailboxes = new ConcurrentHashMap<>();
    private final LongAdder submitted = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private volatile long startedAtNanos;
    private volatile long observedNanos;
    private volatile int depth;
    private volatile int depthPeak;

    public synchronized void attach() {
        startedAtNanos = System.nanoTime();
        PrtsChunkFlow.install(this);
    }

    public synchronized void detach() {
        PrtsChunkFlow.install(null);
    }

    @Override
    public void submitted(String mailbox, int depth) {
        counters(mailbox).submitted.increment();
        submitted.increment();
        note(mailbox, depth);
    }

    @Override
    public void completed(String mailbox, int depth) {
        counters(mailbox).completed.increment();
        completed.increment();
        note(mailbox, depth);
    }

    private void note(String mailbox, int depth) {
        Counters counters = counters(mailbox);
        counters.depth = depth;
        if (depth > counters.depthPeak) {
            counters.depthPeak = depth;
        }
        int total = 0;
        for (Counters each : mailboxes.values()) {
            total += each.depth;
        }
        this.depth = total;
        if (total > depthPeak) {
            depthPeak = total;
        }
        observedNanos = System.nanoTime() - startedAtNanos;
    }

    private Counters counters(String mailbox) {
        return mailboxes.computeIfAbsent(mailbox == null ? "unnamed" : mailbox,
            key -> new Counters());
    }

    /** @return the mailbox names seen so far, in name order */
    public List<String> mailboxes() {
        return new ArrayList<>(new TreeMap<>(mailboxes).keySet());
    }

    public long submitted() {
        return submitted.sum();
    }

    public long completed() {
        return completed.sum();
    }

    public int depth() {
        return depth;
    }

    public int depthPeak() {
        return depthPeak;
    }

    public long observedNanos() {
        return observedNanos;
    }

    public long submitted(String mailbox) {
        Counters counters = mailboxes.get(mailbox);
        return counters == null ? 0L : counters.submitted.sum();
    }

    public long completed(String mailbox) {
        Counters counters = mailboxes.get(mailbox);
        return counters == null ? 0L : counters.completed.sum();
    }

    public int depth(String mailbox) {
        Counters counters = mailboxes.get(mailbox);
        return counters == null ? 0 : counters.depth;
    }

    public int depthPeak(String mailbox) {
        Counters counters = mailboxes.get(mailbox);
        return counters == null ? 0 : counters.depthPeak;
    }

    public double submittedPerSecond() {
        return rate(submitted.sum());
    }

    public double completedPerSecond() {
        return rate(completed.sum());
    }

    private double rate(long count) {
        long nanos = observedNanos;
        return nanos <= 0L ? 0.0 : count * 1_000_000_000.0 / nanos;
    }

    public String key(String mailbox) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < mailbox.length(); index++) {
            char character = mailbox.charAt(index);
            builder.append(Character.isLetterOrDigit(character) ? Character.toLowerCase(character) : '_');
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    public synchronized void reset() {
        mailboxes.clear();
        submitted.reset();
        completed.reset();
        depth = 0;
        depthPeak = 0;
        observedNanos = 0L;
        startedAtNanos = System.nanoTime();
    }
}
