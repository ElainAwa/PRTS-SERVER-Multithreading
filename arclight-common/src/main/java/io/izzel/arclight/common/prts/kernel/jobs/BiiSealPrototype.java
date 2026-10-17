/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Prototype of the two-level declaration seal for one chunk-pipeline batch: the freeze point names
 * the node, the run start seals the cells of that batch before the first write, and a line is judged
 * by whether its own cell falls inside the sealed range. The class is package-private and has no
 * production call site; the freeze, the plan and the commit path never read it.
 *
 * <p>The range is the one the ledger cut, never a cell of it: the second level refuses a partial or
 * borrowed range, and the witness refuses a line outside the range even when its other columns
 * agree. A missing target status or a dead world generation leaves the range readable but seals no
 * witness entry, because the range and the artefact are different halves of a declaration. */
final class BiiSealPrototype {

    static final int MAX_CELLS = PipelineBatchLedger.MAX_CELLS;

    enum Code {
        DECLARED,
        SEALED,
        IDENTITY_MISSING,
        PLAN_INVALID,
        FREEZE_MISSING,
        SEAT_MISSING,
        NODE_KEY_MISMATCH,
        DUPLICATE_NODE,
        RANGE_EMPTY,
        RANGE_TOO_WIDE,
        RANGE_NOT_OF_BATCH,
        OWN_CELL_OUTSIDE,
        LATE,
        DUPLICATE_RANGE,
        STATUS_MISSING,
        WORLD_NOT_LIVE,
        DECLARATION_MISSING,
        SEAT_MISMATCH,
        WRITE_OUTSIDE_RANGE,
        STATUS_MISMATCH,
        INSTANCE_MISMATCH,
        EPOCH_MISMATCH,
        SPACE_MISMATCH
    }

    record Node(PipelineBatchLedger.BatchId batch, String domainId, String nodeKey,
                FrozenDeclarations.FreezeKey freeze, long planSequence, int position) {
    }

    record Sealed(Node node, JobDeclaration.WriteRange range, WriteSpace space,
                  String targetStatus, long instanceId, long worldEpoch) {
    }

    record Outcome(Code code, Node node, JobDeclaration.WriteRange range, Sealed sealed) {

        boolean declared() {
            return node != null;
        }

        boolean rangeDeclared() {
            return range != null;
        }
    }

    record Verdict(boolean accepted, String reason) {

        static Verdict accept() {
            return new Verdict(true, "");
        }

        static Verdict refuse(Code code) {
            return new Verdict(false, code.name().toLowerCase(Locale.ROOT));
        }
    }

    /** The freeze point of the prototype: the only source of the instance number and of the world
     * generation a seal may wear. A producer number handed to the seal is ignored. */
    static final class FreezePoint {

        private final ToLongFunction<String> epochs;
        private long minted;

        FreezePoint(ToLongFunction<String> epochs) {
            this.epochs = epochs;
        }

        long minted() {
            return minted;
        }

        private long mint() {
            return ++minted;
        }

        private long epochOf(String worldId) {
            long epoch = epochs == null ? CommitLog.NO_WORLD_EPOCH : epochs.applyAsLong(worldId);
            return CommitLog.liveWorldEpoch(epoch) ? epoch : CommitLog.NO_WORLD_EPOCH;
        }
    }

    /** One freeze's prototype table: the nodes named at the freeze and the ranges sealed at run
     * start. The first write of a node closes its second level; a later range is late. */
    static final class Seal {

        private final FreezePoint point;
        private final FrozenDeclarations.FreezeKey freeze;
        private final long planSequence;
        private final List<FrozenDeclarations.Assignment> places;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Sealed> sealed = new LinkedHashMap<>();
        private final Set<String> written = new LinkedHashSet<>();

        Seal(FreezePoint point, FrozenDeclarations.FreezeKey freeze, long planSequence,
             List<FrozenDeclarations.Assignment> places) {
            this.point = point;
            this.freeze = freeze;
            this.planSequence = planSequence;
            this.places = places == null ? List.of() : List.copyOf(places);
        }

        /** The first level: seals the node identity of one batch at the freeze, if the plan gave
         * that node a seat and the same node was not declared twice in this freeze. */
        Outcome identity(PipelineBatchLedger.BatchId batch, String domainId, String nodeKey,
                         int position) {
            if (batch == null || batch.worldId().isEmpty()) {
                return refuse(Code.IDENTITY_MISSING);
            }
            if (domainId == null || domainId.isEmpty() || nodeKey == null || nodeKey.isEmpty()) {
                return refuse(Code.IDENTITY_MISSING);
            }
            if (planSequence <= 0L || batch.planSequence() != planSequence) {
                return refuse(Code.PLAN_INVALID);
            }
            if (freeze == null || !freeze.declared()) {
                return refuse(Code.FREEZE_MISSING);
            }
            if (!nodeKey.equals(PipelineRoundJobs.batchKeyOf(batch))) {
                return refuse(Code.NODE_KEY_MISMATCH);
            }
            if (position < 0 || !hasSeat(batch.worldId(), domainId, nodeKey, position)) {
                return refuse(Code.SEAT_MISSING);
            }
            Node node = new Node(batch, domainId, nodeKey, freeze, planSequence, position);
            if (nodes.putIfAbsent(key(node), node) != null) {
                return refuse(Code.DUPLICATE_NODE);
            }
            return new Outcome(Code.DECLARED, node, null, null);
        }

        /** The second level: seals the batch cut at run start. The producer number is accepted and
         * ignored; only the freeze point mints. A missing status or a dead generation leaves the
         * range readable and the seal absent. */
        Outcome range(Node node, PipelineBatchLedger ledger, long[] cells, long taskCell,
                      String targetStatus, long producerInstance) {
            if (node == null || nodes.get(key(node)) != node) {
                return refuse(Code.IDENTITY_MISSING);
            }
            WriteSpace space = WriteSpace.of(CellKind.CHUNK, cells);
            if (space.size() == 0) {
                return new Outcome(Code.RANGE_EMPTY, node, null, null);
            }
            if (space.size() > MAX_CELLS) {
                return new Outcome(Code.RANGE_TOO_WIDE, node, null, null);
            }
            long[] batchCells = ledger == null ? new long[0] : ledger.cellsOf(node.batch());
            if (batchCells.length == 0) {
                return new Outcome(Code.RANGE_EMPTY, node, null, null);
            }
            if (!Arrays.equals(space.cells(), batchCells)) {
                return new Outcome(Code.RANGE_NOT_OF_BATCH, node, null, null);
            }
            if (!space.contains(taskCell)) {
                return new Outcome(Code.OWN_CELL_OUTSIDE, node, null, null);
            }
            if (written.contains(key(node))) {
                return new Outcome(Code.LATE, node, null, null);
            }
            if (sealed.containsKey(key(node))) {
                return new Outcome(Code.DUPLICATE_RANGE, node, null, null);
            }
            JobDeclaration.WriteRange range = new JobDeclaration.WriteRange(
                JobDeclaration.RangeGranularity.CHUNK, space.cells());
            String status = targetStatus == null ? "" : targetStatus;
            if (status.isEmpty()) {
                return new Outcome(Code.STATUS_MISSING, node, range, null);
            }
            long epoch = point.epochOf(node.batch().worldId());
            if (epoch <= 0L) {
                return new Outcome(Code.WORLD_NOT_LIVE, node, range, null);
            }
            Sealed declaration = new Sealed(node, range, space, status, point.mint(), epoch);
            sealed.put(key(node), declaration);
            return new Outcome(Code.SEALED, node, range, declaration);
        }

        /** Marks the first write of one node: a range sealed from here on is late, never backdated. */
        void firstWrite(Node node) {
            if (node != null) {
                written.add(key(node));
            }
        }

        /** Judges one line against the sealed range of this freeze. The line has to be the sealed
         * entry itself and its own chunk has to fall inside that range; status, instance and
         * generation are equality, as before. */
        Verdict witness(Sealed declaration, long writeCell, String targetStatus, long instanceId,
                        long worldEpoch, long planSequence, int position, String nodeKey) {
            if (declaration == null || declaration.node() == null
                || sealed.get(key(declaration.node())) != declaration) {
                return Verdict.refuse(Code.DECLARATION_MISSING);
            }
            return verifyEntry(declaration, writeCell, targetStatus, instanceId, worldEpoch,
                planSequence, position, nodeKey);
        }

        private boolean hasSeat(String worldId, String domainId, String nodeKey, int position) {
            for (FrozenDeclarations.Assignment place : places) {
                if (place != null && place.worldId().equals(worldId)
                    && place.domainId().equals(domainId) && place.nodeKey().equals(nodeKey)
                    && place.position() == position) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The pure part of the witness: recomputes the range fold and refuses a store whose range and
     * space disagree, so a carried digest is never taken on its own. */
    static Verdict verifyEntry(Sealed declaration, long writeCell, String targetStatus,
                               long instanceId, long worldEpoch, long planSequence, int position,
                               String nodeKey) {
        if (declaration == null || declaration.node() == null) {
            return Verdict.refuse(Code.DECLARATION_MISSING);
        }
        Node node = declaration.node();
        if (planSequence != node.planSequence() || position != node.position()
            || !node.nodeKey().equals(nodeKey)) {
            return Verdict.refuse(Code.SEAT_MISMATCH);
        }
        if (!declaration.space().contains(writeCell)) {
            return Verdict.refuse(Code.WRITE_OUTSIDE_RANGE);
        }
        if (!declaration.targetStatus().equals(targetStatus == null ? "" : targetStatus)) {
            return Verdict.refuse(Code.STATUS_MISMATCH);
        }
        if (declaration.instanceId() != instanceId || !CommitLog.liveInstance(instanceId)) {
            return Verdict.refuse(Code.INSTANCE_MISMATCH);
        }
        if (declaration.worldEpoch() != worldEpoch || !CommitLog.liveWorldEpoch(worldEpoch)) {
            return Verdict.refuse(Code.EPOCH_MISMATCH);
        }
        WriteSpace recomputed = WriteSpace.of(CellKind.CHUNK, declaration.range().cells());
        if (declaration.range().granularity() != JobDeclaration.RangeGranularity.CHUNK
            || !recomputed.equals(declaration.space())
            || !recomputed.digest().equals(declaration.space().digest())) {
            return Verdict.refuse(Code.SPACE_MISMATCH);
        }
        return Verdict.accept();
    }

    private static String key(Node node) {
        return node.batch().worldId() + "|" + node.domainId() + "|" + node.nodeKey();
    }

    private static Outcome refuse(Code code) {
        return new Outcome(code, null, null, null);
    }

    private BiiSealPrototype() {
    }
}
