/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.DispatchSnapshot;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.LiveEntityAccess;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The compute-only entity program body: it reads the live rows of one frozen batch under read
 * permits, folds what it read into its checksum and writes the result into the caller's scratch
 * only. It never writes a world object, never waits and never runs the whole entity tick; its read
 * wave closes before the result leaves the body, and a refused permit leaves the batch to the
 * caller's fallback instead of pretending a value.
 */
public final class EntityProgramBody implements WorkBody {

    /** The live rows the body reads; a source that cannot answer returns null, never a zero row. */
    @FunctionalInterface
    public interface LiveSource {

        LiveRowSnapshot read(String worldId, int entityId);
    }

    /** One closed read wave, handed to an optional observer for evidence; it changes no decision. */
    @FunctionalInterface
    public interface Observer {

        void onWave(long batchId, ReadScope.Result result, long checksum);
    }

    /** The field groups the compute-only body reads of one row. */
    public static final Set<ReadPermit.FieldGroup> COMPUTE_GROUPS = Set.copyOf(EnumSet.of(
        ReadPermit.FieldGroup.IDENTITY, ReadPermit.FieldGroup.POSITION,
        ReadPermit.FieldGroup.ROTATION, ReadPermit.FieldGroup.MOTION));

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final int CANCEL_CHECKPOINT = 32;

    private final LiveSource live;
    private final Observer observer;

    /** Opens the body over one live source; the observer may be null when no evidence is needed. */
    public EntityProgramBody(LiveSource live, Observer observer) {
        this.live = Objects.requireNonNull(live, "live");
        this.observer = observer;
    }

    /** The body the dormant internal switch selects; its live source is the real entity lookup. */
    public static EntityProgramBody production(DispatchSnapshot.WorldEpochSource worldEpochs) {
        Objects.requireNonNull(worldEpochs, "worldEpochs");
        return new EntityProgramBody((worldId, entityId) -> {
            ServerLevel level = LiveEntityAccess.level(worldId);
            Entity entity = LiveEntityAccess.entity(level, entityId);
            if (entity == null) {
                return null;
            }
            long uuidHigh = entity.getUUID().getMostSignificantBits();
            long uuidLow = entity.getUUID().getLeastSignificantBits();
            long epoch = worldEpochs.epochOf(worldId);
            EntityRowIdentity identity = new EntityRowIdentity(worldId, Math.max(0L, epoch),
                uuidHigh, uuidLow, entity.getId(), entity.tickCount);
            long generation = EntityObjectGenerations.shared().generationOf(worldId, uuidHigh,
                uuidLow);
            return LiveRowSnapshot.full(identity, generation, entity.tickCount,
                LiveEntityAccess.posX(entity), LiveEntityAccess.posY(entity),
                LiveEntityAccess.posZ(entity), LiveEntityAccess.yaw(entity),
                LiveEntityAccess.pitch(entity), LiveEntityAccess.velX(entity),
                LiveEntityAccess.velY(entity), LiveEntityAccess.velZ(entity));
        }, null);
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
        EntityUpdateProgram program = dormantProgram(view, from);
        long waveTick = program.input().logicalUpdateOrdinal();
        ReadWave wave = ReadWave.open(program, batch.batchId(), waveTick, waveTick, COMPUTE_GROUPS);
        long checksum = OFFSET_BASIS;
        try {
            for (int local = 0; local < count; local++) {
                if ((local & (CANCEL_CHECKPOINT - 1)) == 0 && token.cancelled()) {
                    throw new CancelledFault();
                }
                int row = from + local;
                EntityRowIdentity identity = view.identity(row);
                ReadPermit permit = wave.permit(identity, view.objectGeneration(row),
                    view.expectedStateRevision(row), COMPUTE_GROUPS);
                LiveRowSnapshot snapshot = live.read(view.worldId(), (int) view.entitySeq(row));
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
                // The only write of this body is the caller's scratch: the world object is read
                // under its permit and never written here.
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
        return checksum;
    }

    private static EntityUpdateProgram dormantProgram(EntityCandidateView view, int row) {
        EntityRowIdentity identity = view.identity(row);
        EntityUpdateProgram.Input input = new EntityUpdateProgram.Input(view.worldId(),
            view.worldEpoch(), identity, view.objectGeneration(row),
            view.expectedStateRevision(row), identity.tickCount());
        return EntityUpdateProgram.dormant(EntityUpdateProgram.ProgramKind.BASE_TICK, input);
    }

    private static long mix(long checksum, long value) {
        return (checksum ^ value) * PRIME;
    }
}
