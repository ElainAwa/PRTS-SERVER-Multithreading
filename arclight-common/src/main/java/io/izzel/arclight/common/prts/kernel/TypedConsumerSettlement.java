/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Writes the typed lines one landed batch supports, and none at all when one of their inputs is
 * missing. The batch arrives as the plain report plus the cells its writes were attributed to; every
 * cell is checked before any line is written: the seal of its seat and cell has to be readable and
 * well formed, the node key and the plan have to be the report's, the target status has to be the
 * seal's, the effect reference is minted here and the identities the write path named have to
 * describe exactly the rows the batch counted. A batch that fails one check writes no typed line.
 *
 * <p>Both namespace columns are the adopted chain's controller namespace, read as one current
 * snapshot; a chain with no controller, or a settlement point with no effect store, writes nothing.
 * The effect references come from one counter that is never reset, so a reference is never reused. */
public final class TypedConsumerSettlement {

    private static final String OPERATION_KIND = "block-write";
    private static final String OUTCOME = "granted";

    private final Supplier<CommitLog> log;
    private final RangeSealTable seals;
    private final Supplier<CommitNamespaces.Namespace> controller;
    private final EffectRefs refs = new EffectRefs();
    private long batches;
    private long refused;
    private long lines;
    private long written;
    private long rejected;

    /** Creates the settlement point: the log the lines are reached on, the table the range seals are
     * looked up in and the controller namespace both identity columns are read from. */
    public TypedConsumerSettlement(Supplier<CommitLog> log, RangeSealTable seals,
                                   Supplier<CommitNamespaces.Namespace> controller) {
        this.log = log;
        this.seals = seals;
        this.controller = controller;
    }

    /** Settles one attributed batch: all inputs of every cell are validated first, and only a batch
     * that passes writes its typed lines. A refused batch leaves the plain path as it was. */
    public void settle(PipelineWriteClaims.Attributed payload) {
        batches++;
        CommitLog target = log == null ? null : log.get();
        CommitNamespaces.Namespace namespace = controller == null ? null : controller.get();
        List<Line> prepared = prepare(payload, target, namespace);
        if (prepared == null) {
            return;
        }
        for (Line line : prepared) {
            write(target, payload.report(), namespace, line);
        }
    }

    /** What the settlement point has done since the counters were cleared. */
    public record Stats(long batches, long refused, long lines, long written, long rejected) {
    }

    /** The counters of this settlement point. */
    public Stats stats() {
        return new Stats(batches, refused, lines, written, rejected);
    }

    /** Clears the counters. The effect reference counter is not cleared, so a reference stays spent. */
    public void reset() {
        batches = 0L;
        refused = 0L;
        lines = 0L;
        written = 0L;
        rejected = 0L;
    }

    private List<Line> prepare(PipelineWriteClaims.Attributed payload, CommitLog target,
                               CommitNamespaces.Namespace namespace) {
        if (payload == null || payload.report() == null) {
            return refuse();
        }
        PipelineWriteClaims.Report report = payload.report();
        if (!payload.attributed() || !payload.rowsNamed() || payload.cells().isEmpty()) {
            // A batch that could not be attributed, or whose writes named no identity, has no typed
            // form: the plain report stands for it and no cell is guessed.
            return refuse();
        }
        if (target == null || target.effects() == null) {
            // No adopted chain means no effect store and no controller: nothing can be written here.
            return refuse();
        }
        if (namespace == null || !namespace.declared()) {
            return refuse();
        }
        if (report.expectedVersion() == CommitLog.NO_AUTHORIZATION_GENERATION
            || report.worldId() == null || report.worldId().isEmpty()
            || report.domainId() == null || report.domainId().isEmpty()
            || report.nodeKey() == null || report.nodeKey().isEmpty()) {
            return refuse();
        }
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(report.worldId(),
            report.domainId(), report.planSequence(), report.position());
        long rows = 0L;
        List<Line> prepared = new ArrayList<>(payload.cells().size());
        for (PipelineWriteClaims.CellRows cell : payload.cells()) {
            if (cell == null || cell.rows() <= 0) {
                return refuse();
            }
            rows += cell.rows();
            FrozenDeclarations.RangeSeal seal =
                seals == null ? null : seals.locate(seat, cell.cell());
            if (seal == null || !seal.wellFormed() || !seal.nodeKey().equals(report.nodeKey())
                || seal.planSequence() != report.planSequence()) {
                return refuse();
            }
            FrozenDeclarations.CellStatus status = seal.statusFor(cell.cell());
            if (status == null || status.targetStatus().isEmpty()) {
                return refuse();
            }
            List<String> content = EffectStore.normalizedRows(cell.rowContent());
            if (content.size() != cell.rows() || content.size() != cell.rowContent().size()) {
                // The record has to carry one distinct row per counted row; a short list is a batch
                // whose writes were not all named, and a longer one is not the batch it reports.
                return refuse();
            }
            for (String row : content) {
                if (!WorldWriteGuard.LandedRow.rowIdentity(row)) {
                    return refuse();
                }
            }
            prepared.add(new Line(cell, seal, status.targetStatus(), content));
        }
        if (rows != report.rows()) {
            return refuse();
        }
        return prepared;
    }

    private void write(CommitLog target, PipelineWriteClaims.Report report,
                       CommitNamespaces.Namespace namespace, Line line) {
        long effectRef = refs.mintEffectRef();
        CommitLog.Batch batch = new CommitLog.Batch(report.worldId(), report.domainId(),
            report.nodeKey(), CommitLog.Batch.Kind.APPLY, report.expectedVersion(),
            report.writeSetDigest(), line.cell().rows());
        CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED,
            line.seal().instanceId(), line.seal().worldEpoch(), line.cell().cell(), line.status(),
            report.expectedVersion(), effectRef, namespace, namespace);
        EffectStore.Effect effect = new EffectStore.Effect(effectRef, OPERATION_KIND, OUTCOME,
            line.content());
        lines++;
        CommitLog.Verdict verdict =
            target.reachTyped(new CommitLog.TypedBatch(batch, identity, effect,
                report.planSequence(), report.lifecycle()));
        if (verdict.logged()) {
            written++;
        } else {
            // A runtime refusal of the log or the store; the lines already written are not rolled back.
            rejected++;
        }
    }

    private List<Line> refuse() {
        refused++;
        return null;
    }

    private record Line(PipelineWriteClaims.CellRows cell, FrozenDeclarations.RangeSeal seal,
                        String status, List<String> content) {
    }

    /** The one point an effect reference is minted. The counter starts at one and is never reset, so
     * a reference that a record or a line still carries is not handed out again. */
    private static final class EffectRefs {

        private final AtomicLong next = new AtomicLong(1L);

        private long mintEffectRef() {
            return next.getAndIncrement();
        }
    }
}
