/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.LiveRowSnapshot;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.ReadPermit;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.ReadScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One read wave of the native entity program: it opens a read scope, issues one permit per row,
 * runs each row's single read node on the calling thread or on the pool, and closes into a witness.
 * The host pumps nothing while the wave is open, so a node that asks for a host service is refused by
 * the guard instead of waiting; a node that never answered is refused at close, never skipped. */
public final class NativeReadWave<R> {

    /** One row's read node: a pure read of the frozen state that answers the row's output. */
    @FunctionalInterface
    public interface NativeRead<R> {

        R read();
    }

    /** The pool side of a wave: null means the task was refused before it ran. */
    @FunctionalInterface
    public interface Dispatch {

        CompletableFuture<Void> submit(Runnable task);
    }

    /** What one closed wave witnessed: its permits, the slots that answered and every refusal. */
    public record Witness(long waveId, int issued, int ready, int refused, int foreignWrites, int denied,
                          Map<String, String> refusals) {

        public Witness {
            refusals = Map.copyOf(refusals);
        }

        public boolean clean() {
            return refused == 0 && foreignWrites == 0;
        }
    }

    private final NativeEntityPhase.Mode mode;
    private final long waveId;
    private final long tick;
    private final Set<ReadPermit.FieldGroup> groups;
    private final ReadScope scope;
    private final Dispatch dispatch;
    private final long deadlineNanos;
    private final Map<String, NativeRowProgram<R>> programs = new LinkedHashMap<>();
    private boolean ran;
    /** Set once the deadline is missed: a late node must refuse, never stage into a later tick. */
    private volatile boolean timedOut;
    private Witness witness;

    private NativeReadWave(NativeEntityPhase.Mode mode, long waveId, long tick,
                           Set<ReadPermit.FieldGroup> groups, Dispatch dispatch,
                           long deadlineNanos) {
        this.mode = mode;
        this.waveId = waveId;
        this.tick = tick;
        this.groups = groups;
        this.scope = ReadScope.open(waveId, groups);
        this.dispatch = dispatch;
        this.deadlineNanos = deadlineNanos;
    }

    /** Opens one wave; a parallel wave needs its dispatch and an off mode has no wave at all. */
    public static <R> NativeReadWave<R> open(NativeEntityPhase.Mode mode, long waveId, long tick,
                                             Set<ReadPermit.FieldGroup> groups, Dispatch dispatch,
                                             long deadlineNanos) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(groups, "groups");
        if (mode == NativeEntityPhase.Mode.OFF) {
            throw new IllegalArgumentException("an off mode has no read wave");
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("a read wave declares at least one field group");
        }
        if (mode == NativeEntityPhase.Mode.PARALLEL && dispatch == null) {
            throw new IllegalArgumentException("a parallel read wave needs a dispatch");
        }
        return new NativeReadWave<>(mode, waveId, tick, Set.copyOf(groups), dispatch, deadlineNanos);
    }

    /** Issues one row's permit and read node; a row is issued once per wave. */
    public NativeRowProgram<R> issue(String rowKey, int seat, EntityRowIdentity identity,
                                     long objectGeneration, LiveRowSnapshot snapshot,
                                     NativeRead<R> action) {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        if (ran) {
            throw new IllegalStateException("the read wave already ran");
        }
        if (programs.containsKey(rowKey)) {
            throw new IllegalArgumentException("a row is issued twice: " + rowKey);
        }
        ReadPermit permit = scope.issue(new ReadPermit(identity, objectGeneration, 0L, groups, waveId,
            tick, tick));
        NativeRowProgram<R> program = new NativeRowProgram<>(rowKey, seat, identity, objectGeneration,
            snapshot, permit, action);
        programs.put(rowKey, program);
        return program;
    }

    /** Runs every issued node in issue order; a serial or shadow wave runs them on this thread, a
     * parallel one hands each to the pool and waits without pumping any host queue. */
    public void run() {
        if (ran) {
            throw new IllegalStateException("the read wave ran twice");
        }
        ran = true;
        if (mode != NativeEntityPhase.Mode.PARALLEL) {
            for (NativeRowProgram<R> program : programs.values()) {
                execute(program);
            }
            return;
        }
        List<NativeRowProgram<R>> submitted = new ArrayList<>(programs.size());
        List<CompletableFuture<Void>> futures = new ArrayList<>(programs.size());
        for (NativeRowProgram<R> program : programs.values()) {
            CompletableFuture<Void> future = dispatch.submit(() -> execute(program));
            if (future == null) {
                program.refuse(NativeRowProgram.Refusal.DISPATCH_REFUSED);
                program.clearAction();
                continue;
            }
            submitted.add(program);
            futures.add(future);
        }
        long deadline = System.nanoTime() + deadlineNanos;
        for (int index = 0; index < futures.size(); index++) {
            CompletableFuture<Void> future = futures.get(index);
            while (!future.isDone() && System.nanoTime() < deadline) {
                Thread.yield();
            }
            if (!future.isDone()) {
                // A wave that misses its deadline still has to retire its lifecycle: cancel the
                // pending futures, refuse every open slot and close the read scope before the
                // exception leaves. A late node that still runs sees timedOut and refuses instead
                // of staging a value a later tick could bind.
                timedOut = true;
                for (CompletableFuture<Void> pending : futures) {
                    pending.cancel(false);
                }
                // The deadline invalidates every slot of this wave, including one that answered
                // while the wave was already waiting: the write pump must not apply this wave.
                for (NativeRowProgram<R> program : submitted) {
                    program.refuse(NativeRowProgram.Refusal.NOT_RUN);
                }
                close();
                throw new IllegalStateException(
                    "the read wave did not close before its deadline");
            }
            try {
                future.join();
            } catch (java.util.concurrent.CompletionException failure) {
                NativeRowProgram<R> program = submitted.get(index);
                program.refuse(NativeRowProgram.Refusal.ACTION_FAILED);
                program.failure(failure.getCause());
            }
        }
    }

    /** Closes the wave: unsettled slots are refused, the permits retire and the witness is fixed. */
    public Witness close() {
        if (witness != null) {
            return witness;
        }
        for (NativeRowProgram<R> program : programs.values()) {
            if (program.state() == NativeRowProgram.State.OPEN) {
                program.refuse(NativeRowProgram.Refusal.NOT_RUN);
            }
            program.clearAction();
        }
        ReadScope.Result result = scope.closeReadPhase();
        Map<String, String> refusals = new LinkedHashMap<>();
        int ready = 0;
        int refused = 0;
        for (NativeRowProgram<R> program : programs.values()) {
            if (program.state() == NativeRowProgram.State.READY) {
                ready++;
            } else {
                refused++;
                refusals.put(program.rowKey(),
                    program.refusal() == null ? "UNKNOWN" : program.refusal().name());
            }
        }
        witness = new Witness(waveId, programs.size(), ready, refused, result.foreignWrites(),
            result.refused(), refusals);
        return witness;
    }

    public NativeRowProgram<R> program(String rowKey) {
        return programs.get(rowKey);
    }

    private void execute(NativeRowProgram<R> program) {
        if (timedOut) {
            program.refuse(NativeRowProgram.Refusal.NOT_RUN);
            program.clearAction();
            return;
        }
        ReadScope.Read read = scope.read(program.permit(), program.snapshot(), tick);
        if (!read.allowed()) {
            program.refuse(permitRefusal(read.verdict()));
            return;
        }
        NativeReadGuard.enter(program.rowKey());
        try {
            NativeRead<R> action = program.action();
            if (action == null) {
                program.refuse(NativeRowProgram.Refusal.NOT_RUN);
                return;
            }
            R value = action.read();
            if (timedOut) {
                program.refuse(NativeRowProgram.Refusal.NOT_RUN);
                return;
            }
            program.stage(value);
        } catch (Throwable failure) {
            NativeReadGuard.Refusal guard = NativeReadGuard.refusal();
            program.refuse(guard == null
                ? NativeRowProgram.Refusal.ACTION_FAILED : guardRefusal(guard));
            program.failure(failure);
        } finally {
            NativeReadGuard.Refusal guard = NativeReadGuard.refusal();
            NativeReadGuard.exit();
            if (guard != null) {
                program.refuse(guardRefusal(guard));
            }
        }
    }

    private static NativeRowProgram.Refusal permitRefusal(ReadScope.Verdict verdict) {
        return switch (verdict) {
            case ALLOWED -> throw new IllegalArgumentException("an allowed read has no refusal");
            case REFUSED_WAVE_CLOSED -> NativeRowProgram.Refusal.PERMIT_WAVE_CLOSED;
            case REFUSED_EXPIRED -> NativeRowProgram.Refusal.PERMIT_EXPIRED;
            case REFUSED_IDENTITY -> NativeRowProgram.Refusal.PERMIT_IDENTITY;
            case REFUSED_GENERATION -> NativeRowProgram.Refusal.PERMIT_GENERATION;
            case REFUSED_VERSION -> NativeRowProgram.Refusal.PERMIT_VERSION;
            case REFUSED_WRITER -> NativeRowProgram.Refusal.PERMIT_WRITER;
            case REFUSED_FOREIGN_WRITE -> NativeRowProgram.Refusal.PERMIT_FOREIGN_WRITE;
            case REFUSED_GROUP_NOT_DECLARED -> NativeRowProgram.Refusal.PERMIT_GROUP;
        };
    }

    private static NativeRowProgram.Refusal guardRefusal(NativeReadGuard.Refusal refusal) {
        return switch (refusal) {
            case WRITE -> NativeRowProgram.Refusal.GUARD_WRITE;
            case EVENT -> NativeRowProgram.Refusal.GUARD_EVENT;
            case HOST_CALLBACK -> NativeRowProgram.Refusal.GUARD_HOST_CALLBACK;
            case DRAW -> NativeRowProgram.Refusal.GUARD_DRAW;
            case CHUNK_DEMAND -> NativeRowProgram.Refusal.GUARD_CHUNK_DEMAND;
        };
    }
}
