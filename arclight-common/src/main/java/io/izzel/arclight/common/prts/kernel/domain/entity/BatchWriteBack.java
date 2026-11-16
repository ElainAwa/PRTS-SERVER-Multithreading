/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** The payload carries plain numbers and entity identities only. A payload that carries the frozen
 * write-back identities runs the identity gate before every write and refuses a row whose live
 * object, lifecycle generation or state revision no longer matches; a payload without them keeps
 * the historical behaviour exactly. */
public final class BatchWriteBack implements PrtsWorldWriteTaps.DeferredWrite {

    /** What one payload did; only {@link #written} rows reached the world. */
    public record Result(int written, int gone, int identical, int kept, int refused) {
    }

    /** The move admission one gated write asks before the row reaches the world: the action writes
     * the row, and only an admitted answer counts it as written. It is a package-private wiring
     * point: the production write-back passes none, so the gated path keeps the historical order. */
    @FunctionalInterface
    interface MoveGate {

        EntityMoveAuthority.Verdict beforeWrite(EntityWriteBackIdentity expected,
                                                StateHasher.Slice row, Runnable write);
    }

    /** The live world as the write-back sees it: read for the gate, write for the row. */
    public interface LiveAccess extends EntityWriteBackGate.LiveSource {

        void write(String worldId, int entityId, double x, double y, double z, double yaw,
                   double pitch, double velX, double velY, double velZ);

        boolean identical(String worldId, int entityId, StateHasher.Slice row);
    }

    private final List<StateHasher.Slice> rows;
    private final List<EntityWriteBackIdentity> identities;
    private final LiveAccess live;
    private final Counters readings;
    private final MoveGate moveGate;
    private Result last = new Result(0, 0, 0, 0, 0);
    private boolean partialFault;

    /** Creates the payload of one batch that carries no write-back identity. */
    public BatchWriteBack(List<StateHasher.Slice> rows, Counters readings) {
        this(rows, null, null, readings, null);
    }

    /** Creates the payload of one batch whose rows carry their frozen write-back identity. */
    public BatchWriteBack(List<StateHasher.Slice> rows, List<EntityWriteBackIdentity> identities,
                          LiveAccess live, Counters readings) {
        this(rows, identities, live, readings, null);
    }

    /** Creates one gated payload that asks a move admission before every row write; the admission
     * is an internal evidence wiring point and no production path passes one. */
    BatchWriteBack(List<StateHasher.Slice> rows, List<EntityWriteBackIdentity> identities,
                   LiveAccess live, Counters readings, MoveGate moveGate) {
        this.rows = List.copyOf(rows);
        this.identities = identities == null ? null : List.copyOf(identities);
        this.live = live;
        this.readings = readings;
        this.moveGate = moveGate;
        if (this.identities != null && this.identities.size() != this.rows.size()) {
            throw new IllegalArgumentException("one identity per row is required");
        }
    }

    /** Whether the last {@link #apply()} stopped on a fault after keeping its written prefix; no
     * row after the fault was attempted and nothing is re-dispatched. */
    public boolean partialFault() {
        return partialFault;
    }

    public List<StateHasher.Slice> rows() {
        return rows;
    }

    /** What the last {@link #apply()} did. */
    public Result result() {
        return last;
    }

    @Override
    public boolean apply() {
        if (identities != null) {
            last = gated();
            return true;
        }
        last = legacy();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        return LiveEntityAccess.level(worldId) != null;
    }

    private Result legacy() {
        long startedAt = System.nanoTime();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        String regionId = rows.isEmpty() ? "" : rows.get(0).regionId();
        ServerLevel level = LiveEntityAccess.level(worldId);
        boolean identicalOnly = KernelSettings.dispatchIdenticalOnly();
        int written = 0;
        int gone = 0;
        int identical = 0;
        int kept = 0;
        if (level == null) {
            gone = rows.size();
        } else {
            for (StateHasher.Slice row : rows) {
                if (EntityTickOwnership.live()
                    && EntityTickOwnership.claimedForDispatch((int) row.entitySeq())) {
                    continue;
                }
                Entity entity = LiveEntityAccess.entity(level, (int) row.entitySeq());
                if (entity == null) {
                    gone++;
                    continue;
                }
                if (identicalOnly) {
                    if (LiveEntityAccess.identical(entity, row)) {
                        identical++;
                    } else {
                        kept++;
                    }
                    continue;
                }
                LiveEntityAccess.write(entity, entity.getX(), entity.getY(), entity.getZ(),
                    row.yaw(), row.pitch(), row.velX(), row.velY(), row.velZ());
                written++;
            }
        }
        long nanos = System.nanoTime() - startedAt;
        readings.noteWriteBack(written, gone, nanos);
        if (identicalOnly) {
            readings.noteWriteBackIdentity(identical, kept);
        }
        SelfTimers.note(SelfClass.ENTITY, worldId, regionId, nanos);
        if (noRowsLanded(level != null, identicalOnly, written, identical, kept)) {
            readings.noteWriteBackNoRows();
        }
        return new Result(written, gone, identical, kept, 0);
    }

    private Result gated() {
        long startedAt = System.nanoTime();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        String regionId = rows.isEmpty() ? "" : rows.get(0).regionId();
        boolean identicalOnly = KernelSettings.dispatchIdenticalOnly();
        int written = 0;
        int gone = 0;
        int identical = 0;
        int kept = 0;
        int refused = 0;
        for (int index = 0; index < rows.size(); index++) {
            StateHasher.Slice row = rows.get(index);
            if (EntityTickOwnership.live()
                && EntityTickOwnership.claimedForDispatch((int) row.entitySeq())) {
                continue;
            }
            EntityWriteBackIdentity expected = identities.get(index);
            EntityWriteBackGate.Verdict verdict = EntityWriteBackGate.check(expected, live);
            if (verdict != EntityWriteBackGate.Verdict.APPLIED) {
                // The gate is asked before the write: a refused row never reaches a setter, so the
                // world keeps the state the mismatch was found in.
                if (verdict == EntityWriteBackGate.Verdict.REFUSED_NO_ENTITY) {
                    gone++;
                }
                refused++;
                continue;
            }
            if (identicalOnly) {
                if (live.identical(worldId, (int) row.entitySeq(), row)) {
                    identical++;
                } else {
                    kept++;
                }
                continue;
            }
            if (moveGate == null) {
                live.write(worldId, (int) row.entitySeq(), row.x(), row.y(), row.z(), row.yaw(),
                    row.pitch(), row.velX(), row.velY(), row.velZ());
                written++;
                continue;
            }
            try {
                EntityMoveAuthority.Verdict move = moveGate.beforeWrite(expected, row,
                    () -> live.write(worldId, (int) row.entitySeq(), row.x(), row.y(), row.z(),
                        row.yaw(), row.pitch(), row.velX(), row.velY(), row.velZ()));
                if (move == EntityMoveAuthority.Verdict.ADMITTED) {
                    written++;
                } else {
                    refused++;
                }
            } catch (RuntimeException fault) {
                // The written prefix is kept, the faulted row is not counted as applied, and no
                // row after it is attempted; the observation flag reports the partial fault.
                partialFault = true;
                break;
            }
        }
        long nanos = System.nanoTime() - startedAt;
        readings.noteWriteBack(written, gone, nanos);
        if (identicalOnly) {
            readings.noteWriteBackIdentity(identical, kept);
        }
        SelfTimers.note(SelfClass.ENTITY, worldId, regionId, nanos);
        return new Result(written, gone, identical, kept, refused);
    }

    /** The counters a write-back feeds; the dispatch readings implement it, so the payload names
     * the counters it writes and never the class that holds them. */
    public interface Counters {

        void noteWriteBack(int written, int gone, long nanos);

        void noteWriteBackIdentity(int identical, int kept);

        void noteWriteBackNoRows();
    }

    /** Tells a settlement that landed nothing from one that landed no row: the world being there is
     * still what "applied" means, so only a present world with zero rows counts. */
    public static boolean noRowsLanded(boolean worldPresent, boolean identicalOnly, int written,
                                       int identical, int kept) {
        if (!worldPresent) {
            return false;
        }
        return identicalOnly ? identical + kept == 0 : written == 0;
    }
}
