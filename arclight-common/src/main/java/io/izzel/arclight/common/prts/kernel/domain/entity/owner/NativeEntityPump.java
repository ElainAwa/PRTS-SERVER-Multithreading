/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeEntityPhase;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathNode;
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
import java.util.concurrent.atomic.LongAdder;

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
    private static final long SUMMARY_TICK = 400L;
    private static final long ANOMALY_TICK = 20L;
    /** Wave identity: only the server thread runs a batch, so the counter has one writer. */
    private static final AtomicLong WAVE_IDS = new AtomicLong();
    private static final LongAdder WAVES = new LongAdder();
    private static final LongAdder ROWS = new LongAdder();
    private static final LongAdder PLANNED = new LongAdder();
    private static final LongAdder READY = new LongAdder();
    private static final LongAdder REFUSED = new LongAdder();
    private static final LongAdder FOREIGN = new LongAdder();
    private static final LongAdder PUMPED = new LongAdder();
    private static final LongAdder PUMP_NANOS = new LongAdder();
    private static final LongAdder PLAN_NANOS = new LongAdder();

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
        long planStarted = System.nanoTime();
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
                // A row whose plan cannot be frozen keeps its whole body; it is not a new refusal.
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
        PLAN_NANOS.add(System.nanoTime() - planStarted);
        wave.run();
        NativeReadWave.Witness witness = wave.close();
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
        record(witness, result, rows.size(), planned, tick);
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

    private static void record(NativeReadWave.Witness witness, NativeWritePump.Result result,
                               int rows, int planned, long tick) {
        WAVES.increment();
        ROWS.add(rows);
        PLANNED.add(planned);
        READY.add(witness.ready());
        REFUSED.add(witness.refused());
        FOREIGN.add(witness.foreignWrites());
        PUMPED.add(result.pumped());
        PUMP_NANOS.add(result.nanos());
        boolean anomaly = witness.refused() > 0 || witness.foreignWrites() > 0 || result.faulted();
        if (!(anomaly ? tick % ANOMALY_TICK == 0L : tick % SUMMARY_TICK == 0L)) {
            return;
        }
        System.out.println("[PRTS] native-entity tick=" + tick + " waves=" + WAVES.sum()
            + " rows=" + ROWS.sum() + " planned=" + PLANNED.sum() + " ready=" + READY.sum()
            + " refused=" + REFUSED.sum() + " foreign=" + FOREIGN.sum() + " pumped=" + PUMPED.sum()
            + " pumpNanos=" + PUMP_NANOS.sum() + " planNanos=" + PLAN_NANOS.sum()
            + " waveRefusals=" + refusalSummary(witness) + " faulted=" + result.faulted());
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
