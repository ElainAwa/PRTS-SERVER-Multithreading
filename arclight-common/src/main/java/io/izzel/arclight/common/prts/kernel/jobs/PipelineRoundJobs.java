/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Declares what the chunk pipeline itself submitted as jobs of the planning period.
 *
 * <p>A job is one batch the ledger cut out of the tasks submitted before a freeze: its cells, its
 * tasks and the mailbox of one world. The batch key carries the freeze, so a later batch of the same
 * mailbox is its own node and can never overwrite an earlier one.
 */
public final class PipelineRoundJobs implements PrtsPipelineRows.MailboxOwnerTap {

    /** The domain the declared jobs belong to: the batch interface of the chunk pipeline. */
    public static final String DOMAIN = "chunk-pipeline";

    /** The holder a declared job asks its write right for; a demand, not a credential. */
    public static final String OWNER_SITE = "domain:" + DOMAIN;

    /** How many ticks a grant for one declared job would hold; the declaration names it, nothing
     * grants it while the grant switch is off. */
    public static final int OWNER_HOLD_TICKS = 40;

    /** One batch job: the tasks one mailbox of one world carried, and the handle they were
     * declared under. */
    public record Trace(String key, String worldId, String mailbox, long rounds, long handle) {
    }

    private volatile PipelineBatchLedger ledger;
    private final LongAdder roundsSeen = new LongAdder();
    private final LongAdder roundsUnplaced = new LongAdder();
    private final LongAdder tasksSeen = new LongAdder();
    private volatile Map<String, Trace> last = Map.of();
    private volatile List<FrozenDeclarations.Candidate> candidates = List.of();
    private long windows;
    private long candidateDeclarations;
    private long candidatesWithoutArtefact;
    private long declared;
    private long refused;
    private long coveredRounds;
    private long lastDeclared;
    private long lastRounds;
    private long handles;

    /** Binds the ledger whose cut this producer declares. A producer with no ledger declares
     * nothing; the kernel binds the one ledger it installs as the submit sink. */
    public void bind(PipelineBatchLedger ledger) {
        this.ledger = ledger;
    }

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
    }

    /** A round of a mailbox the placement walk classified as non-target is never a target seen
     * round, not even when a later placement would have given it a world. */
    @Override
    public void ownerRound(Object mailbox, String world, String mailboxName) {
        if (PrtsPipelineRows.isNonTargetMailbox(mailbox)) {
            roundsUnplaced.increment();
            return;
        }
        ownerRound(world, mailboxName);
    }

    @Override
    public void ownerTask(Object mailbox, String world, String mailboxName) {
        if (PrtsPipelineRows.isNonTargetMailbox(mailbox)) {
            tasksSeen.increment();
            return;
        }
        ownerTask(world, mailboxName);
    }

    /** Takes the batches the ledger cut at this freeze and hands one declaration per batch to the
     * intake. A batch whose tasks a competing freeze already claimed declares nothing; the intake's
     * capacity decides the rest.
     *
     * @return how many declarations were handed to the intake, taken or refused */
    public int declareInto(JobIntake intake, long planSequence, long tick) {
        windows++;
        PipelineBatchLedger source = ledger;
        List<PipelineBatchLedger.BatchId> batches = source == null
            ? List.of() : source.freezeAll(planSequence, tick);
        Map<PipelineBatchLedger.BatchId, long[]> cells = new LinkedHashMap<>();
        for (PipelineBatchLedger.BatchId batch : batches) {
            cells.put(batch, source.cellsOf(batch));
        }
        candidates = candidatesOf(batches, cells);
        Map<String, Trace> traces = new LinkedHashMap<>();
        long covered = 0L;
        long declaredNow = 0L;
        for (PipelineBatchLedger.BatchId batch : batches) {
            long tasks = source.tasksOf(batch).size();
            if (tasks <= 0L) {
                continue;
            }
            long handle = ++handles;
            JobDeclaration declaration = declarationOf(batch, handle, tasks, cells.get(batch));
            if (intake.submit(declaration).accepted()) {
                declared++;
                declaredNow++;
                covered += tasks;
                traces.put(declaration.key(), new Trace(declaration.key(), batch.worldId(),
                    batch.mailbox(), tasks, handle));
            } else {
                refused++;
            }
        }
        coveredRounds += covered;
        lastRounds = covered;
        lastDeclared = declaredNow;
        last = Map.copyOf(traces);
        return batches.size();
    }

    /** One candidate per batch. Its artefact is the cells the batch was cut over, read from the same
     * ledger binding the planning range is built from. The batch names no single chunk and the submit
     * carries no target state, so the entry table still writes no entry for it. */
    private List<FrozenDeclarations.Candidate> candidatesOf(
            List<PipelineBatchLedger.BatchId> batches,
            Map<PipelineBatchLedger.BatchId, long[]> cells) {
        List<FrozenDeclarations.Candidate> declared = new ArrayList<>();
        long missing = 0L;
        for (PipelineBatchLedger.BatchId batch : batches) {
            FrozenDeclarations.Candidate candidate = new FrozenDeclarations.Candidate(batch.worldId(),
                DOMAIN, batchKeyOf(batch), null, "",
                new FrozenDeclarations.DeclaredRange(cells.get(batch)));
            if (!candidate.artefactDeclared()) {
                missing++;
            }
            declared.add(candidate);
        }
        candidateDeclarations = declared.size();
        candidatesWithoutArtefact = missing;
        return List.copyOf(declared);
    }

    /** The declarations this producer handed over at the freeze that is running, in the order they
     * were handed over. Read-only: a caller never writes a table, and this producer has none. */
    public List<FrozenDeclarations.Candidate> candidateDeclarations() {
        return candidates;
    }

    public long candidateCount() {
        return candidateDeclarations;
    }

    public long candidatesWithoutArtefact() {
        return candidatesWithoutArtefact;
    }

    /** One batch's declaration: its world, its domains, the mailbox that keeps its tasks in order,
     * the cell range it was cut over, its task count and the write right it asks to hold. */
    private static JobDeclaration declarationOf(PipelineBatchLedger.BatchId batch, long handle,
                                                long tasks, long[] cells) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(batch.worldId(), DOMAIN, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(batch.worldId(),
            WriteLevel.REGION, DOMAIN, HolderKind.REGISTERED, OWNER_SITE, OWNER_HOLD_TICKS);
        int bound = (int) Math.min(Integer.MAX_VALUE, tasks);
        return new JobDeclaration(batchKeyOf(batch), handle, batch.worldId(), DOMAIN, 0, List.of(), 0,
            batch.mailbox(), "world:" + batch.worldId(), List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, bound, List.of(demand),
            new JobDeclaration.WriteRange(JobDeclaration.RangeGranularity.CHUNK, cells));
    }

    /** The key a batch job is declared and matched by: its domain, its world, its mailbox and the
     * freeze that cut it. */
    public static String keyOf(String worldId, String mailbox) {
        return DOMAIN + "/" + worldId + "/" + mailbox;
    }

    public static String batchKeyOf(PipelineBatchLedger.BatchId batch) {
        return keyOf(batch.worldId(), batch.mailbox()) + "#" + batch.freezeOrdinal();
    }

    /** The declaration one plan node came from, or null when the drain did not declare that key. */
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
        last = Map.of();
        candidates = List.of();
        candidateDeclarations = 0L;
        candidatesWithoutArtefact = 0L;
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
