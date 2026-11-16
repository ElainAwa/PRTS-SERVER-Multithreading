/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The compute-only entity program body: it reads the rows of one frozen batch under read permits,
 * dispatches one program per row from the row's own host step instead of letting the first row
 * speak for the batch, folds what it read into its checksum and writes the result into the caller's
 * scratch only. A frozen stable read source is indexed by row and never asks a world for an entity;
 * a miss is refused instead of being answered with a made-up row. It never writes a world object
 * and never waits.
 */
public final class EntityProgramBody implements WorkBody {

    /** The live rows a fixture source reads; a source that cannot answer returns null. */
    @FunctionalInterface
    public interface LiveSource {

        LiveRowSnapshot read(String worldId, int entityId);
    }

    /** One closed read wave, handed to an optional observer; the program of a row is reported too. */
    @FunctionalInterface
    public interface Observer {

        void onWave(long batchId, ReadScope.Result result, long checksum);

        /** Reports the program one row dispatched to; the default keeps the wave-only observer. */
        default void onProgram(long batchId, int row, EntityUpdateProgram program) {
        }
    }

    /** The field groups the compute-only body reads of one row. */
    public static final Set<ReadPermit.FieldGroup> COMPUTE_GROUPS = Set.copyOf(EnumSet.of(
        ReadPermit.FieldGroup.IDENTITY, ReadPermit.FieldGroup.POSITION,
        ReadPermit.FieldGroup.ROTATION, ReadPermit.FieldGroup.MOTION));

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final int CANCEL_CHECKPOINT = 32;

    private final LiveSource live;
    private final Map<String, StableReadSource> frozen;
    private final Observer observer;

    /** Opens the body over one fixture live source; the observer may be null. */
    public EntityProgramBody(LiveSource live, Observer observer) {
        this.live = Objects.requireNonNull(live, "live");
        this.frozen = null;
        this.observer = observer;
    }

    /** Opens the body over the frozen per-world sources captured on the owner thread. */
    public EntityProgramBody(Map<String, StableReadSource> frozen, Observer observer) {
        this.live = null;
        this.frozen = Map.copyOf(Objects.requireNonNull(frozen, "frozen"));
        this.observer = observer;
    }

    @Override
    public long run(WorkBatch batch, ArenaScratch target, CancelToken token)
        throws RetryableFault, NonRetryableFault, CancelledFault {
        EntityCandidateView view = batch.view();
        int from = batch.rangeStart();
        int to = Math.min(batch.rangeEnd(), view.count());
        int count = Math.max(0, to - from);
        target.reset(count);
        if (count == 0) {
            return OFFSET_BASIS;
        }
        StableReadSource source = frozen == null ? null : frozen.get(view.worldId());
        if (frozen != null && source == null) {
            throw new NonRetryableFault("stable read source missing for " + view.worldId());
        }
        long checksum = OFFSET_BASIS;
        int groupOrdinal = 0;
        int index = from;
        while (index < to) {
            int groupStart = index;
            EntityUpdateProgram.ProgramKind kind = RowProgramDispatch.kindOf(view.flags(groupStart));
            int groupEnd = groupStart + 1;
            while (groupEnd < to && RowProgramDispatch.kindOf(view.flags(groupEnd)) == kind) {
                groupEnd++;
            }
            EntityUpdateProgram program = RowProgramDispatch.forRow(view, groupStart,
                inputOf(view, groupStart));
            long waveId = batch.batchId() * 100L + groupOrdinal;
            long waveTick = program.input().logicalUpdateOrdinal();
            ReadWave wave = ReadWave.open(program, waveId, waveTick, waveTick, COMPUTE_GROUPS);
            try {
                for (int row = groupStart; row < groupEnd; row++) {
                    int local = row - from;
                    if ((local & (CANCEL_CHECKPOINT - 1)) == 0 && token.cancelled()) {
                        throw new CancelledFault();
                    }
                    EntityRowIdentity identity = view.identity(row);
                    EntityUpdateProgram rowProgram = groupStart == row ? program
                        : RowProgramDispatch.forRow(view, row, inputOf(view, row));
                    if (observer != null) {
                        observer.onProgram(batch.batchId(), row, rowProgram);
                    }
                    ReadPermit permit = wave.permit(identity, view.objectGeneration(row),
                        view.expectedStateRevision(row), COMPUTE_GROUPS);
                    LiveRowSnapshot snapshot = snapshotOf(source, view, row);
                    ReadScope.Read read = wave.read(permit, snapshot);
                    if (!read.allowed()) {
                        throw new NonRetryableFault("read permit refused: " + read.verdict());
                    }
                    checksum = mix(checksum, read.revision());
                    checksum = mix(checksum, Double.doubleToRawLongBits(snapshot.x()));
                    checksum = mix(checksum, Double.doubleToRawLongBits(snapshot.y()));
                    checksum = mix(checksum, Double.doubleToRawLongBits(snapshot.z()));
                    checksum = mix(checksum, Double.doubleToRawLongBits(snapshot.yaw()));
                    checksum = mix(checksum, Double.doubleToRawLongBits(snapshot.pitch()));
                    target.write(local, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(),
                        snapshot.pitch(), snapshot.velX(), snapshot.velY(), snapshot.velZ(),
                        view.flags(row));
                }
            } catch (NonRetryableFault | CancelledFault fault) {
                wave.close();
                throw fault;
            }
            ReadScope.Result result = wave.close();
            wave.stage(Long.toString(batch.batchId()), checksum);
            if (observer != null) {
                observer.onWave(batch.batchId(), result, checksum);
            }
            groupOrdinal++;
            index = groupEnd;
        }
        return checksum;
    }

    private LiveRowSnapshot snapshotOf(StableReadSource source, EntityCandidateView view, int row)
        throws NonRetryableFault {
        if (source == null) {
            return live.read(view.worldId(), (int) view.entitySeq(row));
        }
        StableReadSource.Read read = source.read(row);
        if (!read.hit()) {
            throw new NonRetryableFault("stable read miss: " + read.miss());
        }
        return read.snapshot();
    }

    private static EntityUpdateProgram.Input inputOf(EntityCandidateView view, int row) {
        EntityRowIdentity identity = view.identity(row);
        return new EntityUpdateProgram.Input(view.worldId(), view.worldEpoch(), identity,
            view.objectGeneration(row), view.expectedStateRevision(row), identity.tickCount());
    }

    private static long mix(long checksum, long value) {
        return (checksum ^ value) * PRIME;
    }
}
