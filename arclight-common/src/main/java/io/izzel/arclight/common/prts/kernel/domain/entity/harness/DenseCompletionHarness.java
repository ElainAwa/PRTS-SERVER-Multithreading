/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The dense-completion harness: it synthesizes the due input of every tick without a live level,
 * runs the rows through deterministic quotas, books one outcome per offered row and proves the
 * O_valid conservation of the run. Two independent references fold the same rows: the tick readings
 * and the per-row outcome ledger, so a drift in either side is named instead of averaged away.
 */
public final class DenseCompletionHarness {

    /** The terminal outcome of one offered row. */
    public enum RowOutcome {
        COMPLETED,
        REFUSED_ADMITTED,
        PARTIAL,
        REFUSED_BEFORE_ADMISSION,
        PENDING
    }

    /** One synthetic row: its identity and whether its AI is on; the row does not read a world. */
    public record Row(long tick, int entitySeq, long uuidHigh, long uuidLow, boolean aiOn) {

        public Row {
            if (tick < 0L || entitySeq < 0) {
                throw new IllegalArgumentException("a row cannot name a negative tick or index");
            }
        }
    }

    /** What one tick offered and how the outcomes split; pending is zero while the run is live. */
    public record TickReading(long tick, int offered, int admitted, int completed, int refused,
                              int partial) {

        public TickReading {
            if (offered < 0 || admitted < 0 || completed < 0 || refused < 0 || partial < 0) {
                throw new IllegalArgumentException("a tick reading cannot be negative");
            }
            if (completed + refused + partial != admitted) {
                throw new IllegalArgumentException("admitted rows are one terminal outcome each");
            }
            if (admitted > offered) {
                throw new IllegalArgumentException("a tick cannot admit more rows than it offered");
            }
        }
    }

    /** The whole run: every tick reading and the two references that must agree. */
    public record Summary(long ticks, long offered, long admitted, long completed, long refused,
                          long partial, boolean conserved, boolean referencesAgree,
                          Map<Long, TickReading> readings) {

        public Summary {
            readings = Map.copyOf(readings);
        }
    }

    /** One row the harness actually booked; the per-row reference is not derived from the ticks. */
    public record Booked(Row row, RowOutcome outcome) {
    }

    private final List<Booked> booked = new ArrayList<>();
    private final Map<Long, TickReading> readings = new LinkedHashMap<>();
    private final NoAiCompletionGuard guard = new NoAiCompletionGuard();

    /**
     * Runs one dense window: every tick offers the same number of synthetic rows, the quota decides
     * how many are admitted, and the outcome plan splits the admitted rows. A row refused before
     * admission or left pending never counts as completed.
     */
    public Summary run(long ticks, int rowsPerTick, Quota quota, OutcomePlan plan) {
        Objects.requireNonNull(quota, "quota");
        Objects.requireNonNull(plan, "plan");
        if (ticks < 0L || rowsPerTick < 0) {
            throw new IllegalArgumentException("a dense window cannot be negative");
        }
        for (long tick = 0L; tick < ticks; tick++) {
            int offered = rowsPerTick;
            int admitted = Math.min(offered, quota.admitted(tick, offered));
            int completed = Math.min(admitted, plan.completed(tick, admitted));
            int partial = Math.min(admitted - completed, plan.partial(tick, admitted - completed));
            int refusedAdmitted = admitted - completed - partial;
            for (int index = 0; index < offered; index++) {
                Row row = new Row(tick, index, tick, index, plan.aiOn(tick, index));
                RowOutcome outcome;
                if (index >= admitted) {
                    outcome = RowOutcome.REFUSED_BEFORE_ADMISSION;
                } else if (index < completed) {
                    outcome = RowOutcome.COMPLETED;
                } else if (index < completed + partial) {
                    outcome = RowOutcome.PARTIAL;
                } else if (index < admitted) {
                    outcome = RowOutcome.REFUSED_ADMITTED;
                } else {
                    outcome = RowOutcome.PENDING;
                }
                booked.add(new Booked(row, outcome));
                guard.noteRow(row.aiOn(), outcome);
            }
            readings.put(tick, new TickReading(tick, offered, admitted, completed, refusedAdmitted,
                partial));
        }
        return summary();
    }

    /** The per-row reference: folds every booked row into one outcome count. */
    public Map<RowOutcome, Long> rowReference() {
        Map<RowOutcome, Long> fold = new LinkedHashMap<>();
        for (RowOutcome outcome : RowOutcome.values()) {
            fold.put(outcome, 0L);
        }
        for (Booked row : booked) {
            fold.merge(row.outcome(), 1L, Long::sum);
        }
        return Map.copyOf(fold);
    }

    /** The tick reference: folds every tick reading into one outcome count. */
    public Map<RowOutcome, Long> tickReference() {
        Map<RowOutcome, Long> fold = new LinkedHashMap<>();
        for (RowOutcome outcome : RowOutcome.values()) {
            fold.put(outcome, 0L);
        }
        for (TickReading reading : readings.values()) {
            fold.merge(RowOutcome.COMPLETED, (long) reading.completed(), Long::sum);
            fold.merge(RowOutcome.REFUSED_ADMITTED, (long) reading.refused(), Long::sum);
            fold.merge(RowOutcome.PARTIAL, (long) reading.partial(), Long::sum);
            fold.merge(RowOutcome.REFUSED_BEFORE_ADMISSION,
                (long) (reading.offered() - reading.admitted()), Long::sum);
        }
        return Map.copyOf(fold);
    }

    /** The two references agree when every outcome count is the same on both sides. */
    public boolean referencesAgree() {
        return rowReference().equals(tickReference());
    }

    /** The NoAI guard the run fed; rows without AI never enter the AI completion denominator. */
    public NoAiCompletionGuard guard() {
        return guard;
    }

    /** The summary of the run: O_valid conservation and the two references are checked here. */
    public Summary summary() {
        long offered = 0L;
        long admitted = 0L;
        long completed = 0L;
        long refused = 0L;
        long partial = 0L;
        for (TickReading reading : readings.values()) {
            offered += reading.offered();
            admitted += reading.admitted();
            completed += reading.completed();
            refused += reading.refused();
            partial += reading.partial();
        }
        long refusedBefore = offered - admitted;
        boolean conserved = completed + refused + partial + refusedBefore == offered
            && completed + refused + partial == admitted;
        return new Summary(readings.size(), offered, admitted, completed, refused, partial,
            conserved, referencesAgree(), readings);
    }

    /** How many rows one tick admits. */
    @FunctionalInterface
    public interface Quota {

        int admitted(long tick, int offered);
    }

    /** How the admitted rows of one tick split into completed, partial and refused. */
    public interface OutcomePlan {

        default int completed(long tick, int admitted) {
            return admitted;
        }

        default int partial(long tick, int admitted) {
            return 0;
        }

        default boolean aiOn(long tick, int index) {
            return true;
        }
    }
}
