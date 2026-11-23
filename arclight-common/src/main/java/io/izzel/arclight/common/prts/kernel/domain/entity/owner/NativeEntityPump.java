/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeEntityPhase;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The first native slice on one batch of deferred walk rows: the host freezes one path search per
 * row, the wave searches them, and the seat pump applies every row on the server thread in seat
 * order with the staged search bound. Rows without a frozen search, and rows whose read slot was
 * refused, pump as their whole-body C form on the same seat; the counters only feed the evidence
 * line of this diagnostic mode. */
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
        long tick = rows.get(0).phase().tick;
        long readStarted = System.nanoTime();
        NativeReadWave<Path> wave = NativeReadWave.open(mode, WAVE_IDS.incrementAndGet(), tick,
            READ_GROUPS, mode == NativeEntityPhase.Mode.PARALLEL ? dispatch(runtime) : null,
            WAVE_DEADLINE_NANOS);
        Map<ServerThreadOwnerPhase.DeferredRow, NativePathNode.Plan> plans =
            new IdentityHashMap<>();
        int planned = 0;
        for (ServerThreadOwnerPhase.DeferredRow row : rows) {
            NativePathNode.Plan attempt;
            try {
                attempt = NativePathNode.plan(row.level(), row.villager());
            } catch (RuntimeException absent) {
                // A row whose plan cannot be frozen keeps its whole body and counts as plan-refused.
                attempt = null;
            }
            NativePathNode.Plan plan = attempt;
            if (plan == null) {
                continue;
            }
            OwnerControlledWrite.Admission admission = admissions.get(row);
            plans.put(row, plan);
            planned++;
            wave.issue(rowKey(row, admission), admission.entry().seat().position(),
                identity(row), generation(row), snapshot(row, identity(row), generation(row)),
                () -> NativePathNode.search(plan));
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
            pumpRows.add(new NativePumpRow(row, admission,
                plan == null ? null : plan.request(), ready ? program.result() : null, ready));
        }
        NativeWritePump.Result result = NativeWritePump.run(pumpRows);
        record(TOTALS, witness, result, rows.size(), planned, readNanos, planNanos, tick);
        return result.faulted();
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
                       NativeWritePump.Result result, int rows, int planned, long readNanos,
                       long planNanos, long tick) {
        int[] refusals = classify(witness);
        NativeEntityEvidence.Batch batch = new NativeEntityEvidence.Batch(tick, rows, planned,
            witness.ready(), rows - planned, refusals[0], refusals[1], refusals[2], refusals[3],
            witness.foreignWrites(), readNanos, result.pumped(), result.nanos(), planNanos,
            result.faulted());
        totals.add(batch);
        System.out.println(totals.sample(NativePathStage.hits())
            .line(result.faulted(), refusalSummary(witness)));
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
