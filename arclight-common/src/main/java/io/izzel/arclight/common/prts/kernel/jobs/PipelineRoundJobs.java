/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Declares what the chunk pipeline itself ran as jobs of the planning period.
 *
 * <p>A job is one drain round that carried work - the unit the batch interface of the chunk pipeline
 * merges. The rounds of one mailbox of one world are read from the owner aware face of the mailbox
 * seam, which names the world the mailbox belongs to. A round whose mailbox no world was read for
 * declares nothing and is counted apart: a batch job without a world is not a job.
 *
 * <p>The declarations are handed to the intake when the next plan is frozen, so the plan orders the
 * rounds the pipeline already ran. A window in which no mailbox carried a round hands in nothing, so
 * no node is ever created for work that did not happen.
 */
public final class PipelineRoundJobs implements PrtsPipelineRows.MailboxOwnerTap {

    /** The domain the declared jobs belong to: the batch interface of the chunk pipeline. */
    public static final String DOMAIN = "chunk-pipeline";

    /** The holder a declared job asks its write right for; a demand, not a credential. */
    public static final String OWNER_SITE = "domain:" + DOMAIN;

    /** How many ticks a grant for one declared job would hold; the declaration names it, nothing
     * grants it while the grant switch is off. */
    public static final int OWNER_HOLD_TICKS = 40;

    /** One batch job: the rounds one mailbox of one world carried, and the handle they were
     * declared under. */
    public record Trace(String key, String worldId, String mailbox, long rounds, long handle) {
    }

    private static final class Counter {

        private final String worldId;
        private final String mailbox;
        private final LongAdder rounds = new LongAdder();

        private Counter(String worldId, String mailbox) {
            this.worldId = worldId;
            this.mailbox = mailbox;
        }
    }

    private final Map<String, Counter> open = new ConcurrentHashMap<>();
    private final LongAdder roundsSeen = new LongAdder();
    private final LongAdder roundsUnplaced = new LongAdder();
    private final LongAdder tasksSeen = new LongAdder();
    private volatile Map<String, Trace> last = Map.of();
    private long windows;
    private long declared;
    private long refused;
    private long coveredRounds;
    private long lastDeclared;
    private long lastRounds;
    private long handles;

    @Override
    public void ownerTask(String world, String mailbox) {
        tasksSeen.increment();
    }

    @Override
    public void ownerRound(String world, String mailbox) {
        if (mailbox == null || mailbox.isEmpty() || world == null
            || PrtsPipelineRows.UNPLACED_WORLD.equals(world)) {
            roundsUnplaced.increment();
            return;
        }
        roundsSeen.increment();
        open.computeIfAbsent(world + "|" + mailbox, key -> new Counter(world, mailbox)).rounds.increment();
    }

    /** Takes the rounds read since the last plan and hands one declaration per mailbox and world
     * pair to the intake. A pair whose rounds the drain did not read declares nothing.
     *
     * @return how many declarations were handed to the intake, taken or refused */
    public int declareInto(JobIntake intake) {
        windows++;
        List<Counter> drained = new ArrayList<>();
        for (String key : new TreeMap<>(open).keySet()) {
            Counter counter = open.remove(key);
            if (counter != null) {
                drained.add(counter);
            }
        }
        drained.sort(Comparator.comparing((Counter counter) -> counter.worldId)
            .thenComparing(counter -> counter.mailbox));
        Map<String, Trace> traces = new LinkedHashMap<>();
        long covered = 0L;
        long declaredNow = 0L;
        for (Counter counter : drained) {
            long rounds = counter.rounds.sum();
            if (rounds <= 0L) {
                continue;
            }
            long handle = ++handles;
            JobDeclaration declaration = declarationOf(counter.worldId, counter.mailbox, handle, rounds);
            if (intake.submit(declaration).accepted()) {
                declared++;
                declaredNow++;
                covered += rounds;
                traces.put(declaration.key(), new Trace(declaration.key(), counter.worldId,
                    counter.mailbox, rounds, handle));
            } else {
                refused++;
            }
        }
        coveredRounds += covered;
        lastRounds = covered;
        lastDeclared = declaredNow;
        last = Map.copyOf(traces);
        return drained.size();
    }

    /** The declaration of one round group: the world and the domain of the batch, the read and write
     * domains it declares, the affinity that keeps the rounds of one mailbox in one order, the bound
     * of the group and the write right it asks to hold while it runs. */
    private static JobDeclaration declarationOf(String worldId, String mailbox, long handle, long rounds) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(worldId, DOMAIN, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(worldId, WriteLevel.REGION,
            DOMAIN, HolderKind.REGISTERED, OWNER_SITE, OWNER_HOLD_TICKS);
        int bound = (int) Math.min(Integer.MAX_VALUE, rounds);
        return new JobDeclaration(keyOf(worldId, mailbox), handle, worldId, DOMAIN, 0, List.of(), 0,
            mailbox, "world:" + worldId, List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, bound, List.of(demand));
    }

    /** The key a batch job is declared and matched by: its domain, its world and its mailbox. */
    public static String keyOf(String worldId, String mailbox) {
        return DOMAIN + "/" + worldId + "/" + mailbox;
    }

    /** The declaration one plan node came from, or null when the drain did not read that key. */
    public Trace traceOf(String key) {
        return last.get(key);
    }

    public long roundsSeen() {
        return roundsSeen.sum();
    }

    public long roundsUnplaced() {
        return roundsUnplaced.sum();
    }

    public long tasksSeen() {
        return tasksSeen.sum();
    }

    public long windows() {
        return windows;
    }

    public long declared() {
        return declared;
    }

    public long refused() {
        return refused;
    }

    public long coveredRounds() {
        return coveredRounds;
    }

    public long lastDeclared() {
        return lastDeclared;
    }

    public long lastRounds() {
        return lastRounds;
    }

    public void reset() {
        open.clear();
        last = Map.of();
        roundsSeen.reset();
        roundsUnplaced.reset();
        tasksSeen.reset();
        windows = 0L;
        declared = 0L;
        refused = 0L;
        coveredRounds = 0L;
        lastDeclared = 0L;
        lastRounds = 0L;
        handles = 0L;
    }
}
