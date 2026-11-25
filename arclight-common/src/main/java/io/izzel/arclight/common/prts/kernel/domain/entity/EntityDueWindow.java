/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.support.EntityEligibility;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The frozen due rows of one tick and the identity ledger they produce. A row beyond the declared
 * bound is retained in the ledger as an explicit capacity refusal; the bound is a memory guard, not
 * a per-tick selection cap, so no due identity is silently dropped.
 */
public final class EntityDueWindow {

    /** Memory guard of one tick's due window; rows beyond it keep their identity as a refusal. */
    public static final int ROW_BOUND = 1 << 20;

    /**
     * One due row, read on the tick thread before the host iteration. {@code yBodyRot} is the body
     * rotation frozen with the other pose fields; {@link Double#NaN} marks a row frozen without one,
     * so the reader must fall back to the live entity or refuse by name.
     */
    public record Row(int entityId, long uuidHigh, long uuidLow, int tickCount, boolean passenger,
                      boolean removed, double x, double y, double z, double yaw, double pitch,
                      double vx, double vy, double vz, long hostStep,
                      EntityEligibility.Reason reason, String classTag, double yBodyRot) {

        public Row {
            Objects.requireNonNull(reason, "reason");
        }

        /** A row frozen before body rotation joined the checkpoint; the value stays unknown. */
        public Row(int entityId, long uuidHigh, long uuidLow, int tickCount, boolean passenger,
                   boolean removed, double x, double y, double z, double yaw, double pitch,
                   double vx, double vy, double vz, long hostStep,
                   EntityEligibility.Reason reason, String classTag) {
            this(entityId, uuidHigh, uuidLow, tickCount, passenger, removed, x, y, z, yaw, pitch,
                vx, vy, vz, hostStep, reason, classTag, Double.NaN);
        }

        /** The same row with its internal class tag, written by the tick thread that froze it. */
        public Row withClassTag(String className) {
            return new Row(entityId, uuidHigh, uuidLow, tickCount, passenger, removed, x, y, z,
                yaw, pitch, vx, vy, vz, hostStep, reason, className, yBodyRot);
        }
    }

    /** One level's frozen tick list: the loaded total and the rows it carried. */
    public record World(String worldId, int loaded, List<Row> rows) {

        public World {
            Objects.requireNonNull(worldId, "worldId");
            if (worldId.isEmpty()) {
                throw new IllegalArgumentException("a due world needs a name");
            }
            if (loaded < 0) {
                throw new IllegalArgumentException("a loaded total cannot be negative");
            }
            rows = List.copyOf(rows);
        }
    }

    /** The views of one tick and the ledger that accounts for their rows. */
    public record Result(List<EntityCandidateView> views, EntityDueLedger ledger) {

        public Result {
            views = List.copyOf(views);
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    private EntityDueWindow() {
    }

    /**
     * Compiles one frozen window into the views of the tick and the identity ledger: every due row
     * is booked with its passenger split and either admitted to its world's view or explicitly
     * refused, and the ledger closes with the accounting {@code due == admitted + removed + over
     * capacity} when nothing else went wrong.
     */
    public static Result compile(long tick, List<World> worlds,
                                 DispatchSnapshot.WorldEpochSource worldEpochs, int maxRows) {
        Objects.requireNonNull(worlds, "worlds");
        Objects.requireNonNull(worldEpochs, "worldEpochs");
        EntityDueLedger ledger = EntityDueLedger.open(tick, maxRows);
        List<EntityCandidateView> views = new ArrayList<>(worlds.size());
        for (World world : worlds) {
            long epoch = Math.max(0L, worldEpochs.epochOf(world.worldId()));
            ledger.noteLoaded(world.worldId(), world.loaded());
            EntityCandidateView.Builder builder =
                EntityCandidateView.builder(world.worldId(), epoch, maxRows);
            for (Row row : world.rows()) {
                EntityRowIdentity identity = new EntityRowIdentity(world.worldId(), epoch,
                    row.uuidHigh(), row.uuidLow(), row.entityId(), row.tickCount());
                boolean tracked = ledger.due(identity, row.passenger(), row.removed(),
                    row.reason());
                if (row.removed()) {
                    // The host skips a removed row before its tick; the identity stays refused.
                    continue;
                }
                if (!tracked) {
                    PrtsEntityCapability.noteCapDropped();
                    continue;
                }
                int chunkX = (int) Math.floor(row.x()) >> 4;
                int chunkZ = (int) Math.floor(row.z()) >> 4;
                // Only a process that declares the takeover face names the witnesses at all. The
                // window then promises this row one host tick: the write-back lands after that tick,
                // so the state revision it must find is the row's next logical update ordinal.
                boolean gated = KernelSettings.dispatchTakeover();
                long generation = gated
                    ? EntityObjectGenerations.shared().observe(world.worldId(), epoch,
                        row.uuidHigh(), row.uuidLow()) : 0L;
                long revision = gated ? row.tickCount() + 1L : 0L;
                if (builder.add(identity, row.reason(), generation, revision, chunkX, chunkZ,
                    row.x(), row.y(), row.z(), row.yaw(), row.pitch(), row.vx(), row.vy(),
                    row.vz(), row.hostStep())) {
                    ledger.admit(identity);
                    if (row.classTag() != null) {
                        builder.tagLast(row.classTag());
                    }
                }
            }
            views.add(builder.build());
        }
        long total = 0L;
        for (EntityCandidateView view : views) {
            total += view.count();
        }
        EntityQueryReadings.noteEntityCount(total);
        ledger.close();
        return new Result(views, ledger);
    }
}
