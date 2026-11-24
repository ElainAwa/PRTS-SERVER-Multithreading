/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeEntityPhase;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeNeighborStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeGoalStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.MoveCollisionResult;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeMoveCompute;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeMoveRead;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeMoveStage;
import io.izzel.arclight.common.prts.fixes.nativepath.PrtsBrainSensorsAccessor;
import io.izzel.arclight.common.prts.fixes.nativepath.PrtsSensorTickAccessor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathNode;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadWave;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeRowProgram;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeWritePump;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.LiveRowSnapshot;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.ReadPermit;
import io.izzel.arclight.common.prts.kernel.generic.GenericCancel;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The first native slice on one batch of deferred walk rows: the host freezes one path search per
 * row, the wave searches them, and the seat pump applies every row on the server thread in seat
 * order with the staged search bound. The shadow mode also plans the ordinary start search of the
 * move-to-target behavior and recomputes each hit inline; it publishes no staged result. Rows without
 * a frozen search, and rows whose read slot was refused, pump as their whole-body C form on the same
 * seat; the counters only feed the evidence line of this diagnostic mode. */
final class NativeEntityPump {

    private static final Set<ReadPermit.FieldGroup> READ_GROUPS = Set.of(
        ReadPermit.FieldGroup.IDENTITY, ReadPermit.FieldGroup.POSITION,
        ReadPermit.FieldGroup.ROTATION, ReadPermit.FieldGroup.MOTION,
        ReadPermit.FieldGroup.NAVIGATION);
    private static final long WAVE_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5L);
    /** Wave identity: only the server thread runs a batch, so the counter has one writer. */
    private static final AtomicLong WAVE_IDS = new AtomicLong();
    private static final NativeEntityEvidence TOTALS = new NativeEntityEvidence();

    private NativeEntityPump() {
    }

    /** Runs one native batch; false means every pumped row published, true means one did not. */
    static boolean run(SharedRuntime runtime, List<ServerThreadOwnerPhase.DeferredRow> rows,
                       Map<ServerThreadOwnerPhase.DeferredRow, OwnerControlledWrite.Admission> admissions) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        NativeEntityPhase.Mode mode = NativeEntityPhase.mode();
        boolean shadow = mode == NativeEntityPhase.Mode.SHADOW;
        long tick = rows.get(0).phase().tick;
        long readStarted = System.nanoTime();
        NativeReadWave<Path> wave = NativeReadWave.open(mode, WAVE_IDS.incrementAndGet(), tick,
            READ_GROUPS, mode == NativeEntityPhase.Mode.PARALLEL ? dispatch(runtime) : null,
            WAVE_DEADLINE_NANOS);
        Map<ServerThreadOwnerPhase.DeferredRow, NativePathNode.Plan> plans =
            new IdentityHashMap<>();
        Set<ServerThreadOwnerPhase.DeferredRow> ordinaryRows =
            Collections.newSetFromMap(new IdentityHashMap<>());
        int planned = 0;
        int plannedOrdinary = 0;
        for (ServerThreadOwnerPhase.DeferredRow row : rows) {
            NativePathNode.Plan attempt;
            try {
                attempt = NativePathNode.plan(row.level(), row.villager());
            } catch (RuntimeException absent) {
                // A row whose plan cannot be frozen keeps its whole body and counts as plan-refused.
                attempt = null;
            }
            boolean ordinary = false;
            if (attempt == null && mode != NativeEntityPhase.Mode.OFF) {
                // A normal start prediction is a real read demand: delayed recomputation alone is
                // almost never flagged in a live world, which left the wave with planned=0. In the
                // parallel mode the staged answer is consumed by the reuse mixin, so predicting it
                // here both drives the wave and removes the inline search; shadow still compares.
                try {
                    attempt = NativePathNode.planStartSearch(row.level(), row.villager());
                } catch (RuntimeException absent) {
                    attempt = null;
                }
                ordinary = attempt != null;
            }
            NativePathNode.Plan plan = attempt;
            OwnerControlledWrite.Admission admission = admissions.get(row);
            String key = rowKey(row, admission);
            // Only stage a neighbour scan when the sensor will actually tick this turn: an idle
            // sensor would pay the scan with no consumer. The host freezes the set; a worker sorts it.
            List<net.minecraft.world.entity.LivingEntity> rawNeighbours = null;
            if (mode == NativeEntityPhase.Mode.PARALLEL && neighbourSensorDue(row)) {
                net.minecraft.world.phys.AABB bounds = row.villager().getBoundingBox()
                    .inflate(16.0D, 16.0D, 16.0D);
                rawNeighbours = row.level().getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class, bounds,
                    candidate -> candidate != row.villager() && candidate.isAlive());
            }
            boolean hasNeighbours = rawNeighbours != null && !rawNeighbours.isEmpty();
            if (plan == null && !hasNeighbours) {
                continue;
            }
            final NativePathNode.Plan frozenPlan = plan;
            final List<net.minecraft.world.entity.LivingEntity> frozenNeighbours = rawNeighbours;
            plans.put(row, plan);
            if (ordinary) {
                ordinaryRows.add(row);
                plannedOrdinary++;
            }
            planned++;
            wave.issue(key, admission.entry().seat().position(),
                identity(row), generation(row), snapshot(row, identity(row), generation(row)),
                () -> {
                    Path path = frozenPlan == null ? null : NativePathNode.search(frozenPlan);
                    if (frozenNeighbours != null) {
                        java.util.List<net.minecraft.world.entity.LivingEntity> sorted =
                            NativeNeighborStage.order(frozenNeighbours, row.villager());
                        NativeNeighborStage.stage(key, sorted);
                        // The villager's look-and-interact goal only targets nearby players within
                        // four blocks; prefilter that predicate here so the seat pump runs the
                        // remaining line-of-sight test on a tiny set instead of every neighbour.
                        java.util.List<net.minecraft.world.entity.LivingEntity> players =
                            new java.util.ArrayList<>();
                        for (net.minecraft.world.entity.LivingEntity candidate : sorted) {
                            if (candidate.getType() == net.minecraft.world.entity.EntityType.PLAYER
                                && candidate.distanceToSqr(row.villager()) <= 16.0D) {
                                players.add(candidate);
                            }
                        }
                        NativeGoalStage.stage(key, players);
                    }
                    return path;
                });
        }
        long planNanos = System.nanoTime() - readStarted;
        wave.run();
        NativeReadWave.Witness witness = wave.close();
        long readNanos = System.nanoTime() - readStarted;
        List<NativePumpRow> pumpRows = new ArrayList<>(rows.size());
        for (ServerThreadOwnerPhase.DeferredRow row : rows) {
            OwnerControlledWrite.Admission admission = admissions.get(row);
            NativeRowProgram<Path> program = wave.program(rowKey(row, admission));
            boolean ready = program != null && program.state() == NativeRowProgram.State.READY;
            NativePathNode.Plan plan = ready ? plans.get(row) : null;
            boolean ordinary = plan != null && ordinaryRows.contains(row);
            pumpRows.add(new NativePumpRow(row, admission,
                plan == null ? null : plan.request(), ready ? program.result() : null, ready,
                ordinary));
        }
        long comparedBefore = shadow ? NativePathStage.comparedOrdinary() : 0L;
        long mismatchedBefore = shadow ? NativePathStage.mismatchedOrdinary() : 0L;
        // E8a: all phase-A bodies, the frozen collision wave, then all phase-B bodies. The seat
        // order and every ticket are unchanged; the host tails run after phase B on the same seat.
        java.util.List<NativePumpRow> ordered = new java.util.ArrayList<>(pumpRows);
        ordered.sort(java.util.Comparator.comparingInt(NativePumpRow::seat));
        for (NativePumpRow pumpRow : ordered) {
            if (!pumpRow.begin()) continue;
            pumpRow.applyPhaseA();
        }
        runMoveWave(runtime, rows, admissions, tick);
        int moved = 0;
        boolean moveFaulted = false;
        long moveStarted = System.nanoTime();
        for (NativePumpRow pumpRow : ordered) {
            if (pumpRow.begun()) {
                if (!pumpRow.applyPhaseB()) moveFaulted = true;
                pumpRow.complete();
                moved++;
            }
        }
        NativeWritePump.Result result = new NativeWritePump.Result(
            moveFaulted, moved, 0, System.nanoTime() - moveStarted);
        int comparedOrdinary = shadow
            ? (int) (NativePathStage.comparedOrdinary() - comparedBefore) : 0;
        int mismatchOrdinary = shadow
            ? (int) (NativePathStage.mismatchedOrdinary() - mismatchedBefore) : 0;
        int unusedOrdinary = unusedOrdinary(plannedOrdinary, comparedOrdinary);
        record(TOTALS, witness, result, rows.size(), planned, plannedOrdinary, comparedOrdinary,
            mismatchOrdinary, unusedOrdinary, readNanos, planNanos, tick);
        System.out.println("[PRTS] native-neighbor staged="
            + io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeNeighborStage.staged()
            + " consumed="
            + io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeNeighborStage.consumed());
        System.out.println("[PRTS] native-goal staged="
            + io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeGoalStage.staged()
            + " consumed="
            + io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeGoalStage.consumed());
        return result.faulted();
    }

    /** Opens the collision-query wave: the host freezes one read per row after phase A, a worker runs
     * the vanilla collision equation on that frozen input, and the result is staged for the seat
     * pump. A row whose permit is refused keeps the whole-line C form at its move call site. */
    private static void runMoveWave(SharedRuntime runtime,
                                    List<ServerThreadOwnerPhase.DeferredRow> rows,
                                    Map<ServerThreadOwnerPhase.DeferredRow,
                                        OwnerControlledWrite.Admission> admissions, long tick) {
        NativeEntityPhase.Mode mode = NativeEntityPhase.mode();
        if (mode == NativeEntityPhase.Mode.OFF || rows.isEmpty()) {
            return;
        }
        NativeReadWave<MoveCollisionResult> wave = NativeReadWave.open(mode,
            WAVE_IDS.incrementAndGet(), tick, READ_GROUPS,
            mode == NativeEntityPhase.Mode.PARALLEL ? dispatch(runtime) : null,
            WAVE_DEADLINE_NANOS);
        java.util.Map<String, NativeMoveRead> frozen = new java.util.LinkedHashMap<>();
        for (ServerThreadOwnerPhase.DeferredRow row : rows) {
            String key = rowKey(row, admissions.get(row));
            net.minecraft.world.entity.npc.Villager villager = row.villager();
            net.minecraft.world.phys.Vec3 requested = villager.getDeltaMovement();
            net.minecraft.core.BlockPos center = villager.blockPosition();
            int radius = 8;
            net.minecraft.world.level.PathNavigationRegion region =
                new net.minecraft.world.level.PathNavigationRegion(row.level(),
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius));
            java.util.List<net.minecraft.world.phys.shapes.VoxelShape> collisions =
                row.level().getEntityCollisions(villager,
                    villager.getBoundingBox().expandTowards(requested));
            // The host freezes a conservative expansion: a real move argument may differ from
            // the predicted motion by up to the declared margin and still be answered from this
            // frozen read; a request outside it keeps the whole row C and never reads live Level.
            NativeMoveRead read = NativeMoveRead.of(key, requested, villager.getBoundingBox(),
                region, collisions, villager.maxUpStep(), villager.onGround());
            frozen.put(key, read);
            NativeMoveStage.stageRead(key, read);
            TargetEntityDiagnostic.noteRead(tick, villager.getUUID(), key, read);
            wave.issue(key, admissions.get(row).entry().seat().position(),
                identity(row), generation(row), snapshot(row, identity(row), generation(row)),
                () -> NativeMoveCompute.compute(villager, read));
        }
        wave.run();
        NativeReadWave.Witness witness = wave.close();
        System.out.println("[PRTS] native-move staged=" + NativeMoveStage.staged()
            + " consumed=" + NativeMoveStage.consumed()
            + " planned=" + witness.issued()
            + " ready=" + witness.ready()
            + " refused=" + witness.refused()
            + " foreignWrites=" + witness.foreignWrites()
            + " denied=" + witness.denied());
        for (java.util.Map.Entry<String, NativeMoveRead> entry : frozen.entrySet()) {
            NativeRowProgram<MoveCollisionResult> program = wave.program(entry.getKey());
            if (program != null && program.state() == NativeRowProgram.State.READY) {
                NativeMoveStage.stage(entry.getKey(), program.result());
            }
        }
    }

    private static NativeReadWave.Dispatch dispatch(SharedRuntime runtime) {
        return task -> {
            WorkerPool pool = runtime == null ? null : runtime.pool();
            if (pool == null || !pool.accepting()) {
                return null;
            }
            WorkerPool.GenericHandle handle = pool.submitGeneric(token -> task.run(),
                new NativeReadToken());
            return handle == null ? null : handle.future();
        };
    }

    /** Whether the villager's nearest-living sensor will actually tick this turn; staging an idle
     * sensor pays the scan with no consumer. Reads the frozen brain's sensor table on this thread. */
    private static boolean neighbourSensorDue(ServerThreadOwnerPhase.DeferredRow row) {
        net.minecraft.world.entity.ai.Brain<?> brain = row.villager().getBrain();
        if (!(brain instanceof PrtsBrainSensorsAccessor access)) {
            return false;
        }
        for (java.util.Map.Entry<SensorType<? extends net.minecraft.world.entity.ai.sensing.Sensor<?>>,
            net.minecraft.world.entity.ai.sensing.Sensor<?>> entry : access.prts$sensors().entrySet()) {
            if (entry.getKey() == SensorType.NEAREST_LIVING_ENTITIES
                && entry.getValue() instanceof PrtsSensorTickAccessor sensor
                // Sensor.tick decrements first, then fires and resets to scanRate; a stable sensor
                // therefore reads in [1, scanRate]. Due next tick means <= 1, not <= 0.
                && sensor.prts$timeToTick() <= 1L) {
                return true;
            }
        }
        return false;
    }

    private static String rowKey(ServerThreadOwnerPhase.DeferredRow row,
                                 OwnerControlledWrite.Admission admission) {
        return row.level().dimension().location() + "|" + admission.entry().nodeKey() + "|"
            + admission.entry().seat().position();
    }

    private static EntityRowIdentity identity(ServerThreadOwnerPhase.DeferredRow row) {
        ServerThreadOwnerPhase.RowIdentity known = row.phase().identities.get(row.entity().getId());
        String worldId = row.level().dimension().location().toString();
        if (known == null) {
            return new EntityRowIdentity(worldId, 0L, 0L, 0L, row.entity().getId(),
                row.villager().tickCount);
        }
        return new EntityRowIdentity(known.worldId(), known.worldEpoch(), known.uuidHigh(),
            known.uuidLow(), row.entity().getId(), known.updateTick());
    }

    private static long generation(ServerThreadOwnerPhase.DeferredRow row) {
        ServerThreadOwnerPhase.RowIdentity known = row.phase().identities.get(row.entity().getId());
        return known == null ? 0L : known.generation();
    }

    private static LiveRowSnapshot snapshot(ServerThreadOwnerPhase.DeferredRow row,
                                            EntityRowIdentity identity, long generation) {
        var mob = row.villager();
        var motion = mob.getDeltaMovement();
        return new LiveRowSnapshot(identity, generation, 0L, mob.getX(), mob.getY(), mob.getZ(),
            mob.getYRot(), mob.getXRot(), motion.x, motion.y, motion.z, READ_GROUPS);
    }

    static void record(NativeEntityEvidence totals, NativeReadWave.Witness witness,
                       NativeWritePump.Result result, int rows, int planned, int plannedOrdinary,
                       int comparedOrdinary, int mismatchOrdinary, int unusedOrdinary, long readNanos,
                       long planNanos, long tick) {
        int[] refusals = classify(witness);
        NativeEntityEvidence.Batch batch = new NativeEntityEvidence.Batch(tick, rows, planned,
            witness.ready(), rows - planned, refusals[0], refusals[1], refusals[2], refusals[3],
            witness.foreignWrites(), readNanos, result.pumped(), result.nanos(), planNanos,
            result.faulted(), plannedOrdinary, comparedOrdinary, mismatchOrdinary, unusedOrdinary);
        totals.add(batch);
        System.out.println(totals.sample(NativePathStage.hits())
            .line(result.faulted(), refusalSummary(witness)));
    }

    /** The planned ordinary searches the body never took: no search, a different request, or the
     * delayed guard. It is the part of the prediction that bought nothing. */
    static int unusedOrdinary(int plannedOrdinary, int comparedOrdinary) {
        return Math.max(0, plannedOrdinary - comparedOrdinary);
    }

    /** Splits the wave's per-row refusals into the evidence categories; no refusal code is added. */
    static int[] classify(NativeReadWave.Witness witness) {
        int fence = 0;
        int dispatch = 0;
        int notRun = 0;
        int failed = 0;
        for (String name : witness.refusals().values()) {
            NativeRowProgram.Refusal refusal;
            try {
                refusal = NativeRowProgram.Refusal.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                failed++;
                continue;
            }
            switch (refusal) {
                case GUARD_WRITE, GUARD_EVENT, GUARD_HOST_CALLBACK, GUARD_DRAW, GUARD_CHUNK_DEMAND ->
                    fence++;
                case DISPATCH_REFUSED -> dispatch++;
                case NOT_RUN -> notRun++;
                default -> failed++;
            }
        }
        return new int[]{fence, dispatch, notRun, failed};
    }

    /** Writes the one stop summary; a process that never enabled the native path writes nothing. */
    static void stopSummary() {
        if (!NativeEntityPhase.enabled()) {
            return;
        }
        System.out.println(TOTALS.sample(NativePathStage.hits()).summary());
    }

    /** At most a few refusal reasons per line: a whole batch of refused rows must not print a wall. */
    private static String refusalSummary(NativeReadWave.Witness witness) {
        StringBuilder line = new StringBuilder();
        int printed = 0;
        for (Map.Entry<String, String> entry : witness.refusals().entrySet()) {
            if (printed == 8) {
                line.append(",(+").append(witness.refusals().size() - printed).append(")");
                break;
            }
            if (printed > 0) {
                line.append(',');
            }
            line.append(entry.getKey()).append('=').append(entry.getValue());
            printed++;
        }
        return line.toString();
    }

    /** The read node takes no effect and is never cancelled by a stop: it only reads a frozen region. */
    private static final class NativeReadToken implements GenericCancel {

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }
}