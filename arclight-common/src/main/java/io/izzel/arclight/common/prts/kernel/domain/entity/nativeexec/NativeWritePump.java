/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The seat pump of one write wave: ascending seats, one row at a time, begun, applied and
 * published before the next seat starts; a row whose read slot is not ready still runs. */
public final class NativeWritePump {

    /** One row the pump may write: its frozen seat, its read slot and the owner-side actions. */
    public interface Row {

        int seat();

        /** Whether the read wave staged a usable result for this row. */
        boolean readReady();

        /** Takes the reservation and starts the ticket; false is a pre-effect refusal. */
        boolean begin();

        boolean apply();

        void complete();
    }

    public record Result(boolean faulted, int pumped, int refused, long nanos) {
    }

    private NativeWritePump() {
    }

    /** Pumps every row in ascending seat order; the caller owns the wave and the batch account. */
    public static Result run(List<? extends Row> rows) {
        List<Row> ordered = seatOrder(rows);
        long started = System.nanoTime();
        int pumped = 0;
        int refused = 0;
        boolean faulted = false;
        for (Row row : ordered) {
            if (!row.begin()) {
                refused++;
                faulted = true;
                continue;
            }
            boolean applied;
            try {
                applied = row.apply();
            } finally {
                row.complete();
            }
            pumped++;
            if (!applied) {
                faulted = true;
            }
        }
        return new Result(faulted, pumped, refused, System.nanoTime() - started);
    }

    /** A windowed pump: terminals follow seat order while the bodies run on the shared pool. The
     * begin of one row may overlap an earlier body and a later terminal still waits for every
     * earlier one, which is the same contract the entity owner window already proves. */
    public static Result runWindowed(List<? extends Row> rows, NativeReadWave.Dispatch dispatch,
                                     int window, Runnable hostPump) {
        List<Row> ordered = seatOrder(rows);
        long started = System.nanoTime();
        int pumped = 0;
        int refused = 0;
        boolean faulted = false;
        int begun = 0;
        int finished = 0;
        java.util.ArrayDeque<WindowSlot> inFlight = new java.util.ArrayDeque<>();
        while (finished < ordered.size()) {
            while (begun < ordered.size() && inFlight.size() < window) {
                Row row = ordered.get(begun++);
                if (!row.begin()) {
                    refused++;
                    faulted = true;
                    continue;
                }
                CompletableFuture<Void> future = dispatch == null ? null
                    : dispatch.submit(row::apply);
                inFlight.addLast(new WindowSlot(row, future));
            }
            WindowSlot head = inFlight.peekFirst();
            if (head == null) continue;
            if (head.future() != null) {
                // The body may ask the host to dispatch a plugin event and park on its result; a
                // blocking join here would deadlock that worker against this thread. Pump the host
                // while waiting, exactly as the entity owner window does.
                while (!head.future().isDone()) {
                    if (hostPump != null) hostPump.run();
                    Thread.yield();
                }
                try { head.future().join(); } catch (Exception e) { faulted = true; }
            } else {
                head.row().apply();
            }
            inFlight.removeFirst();
            head.row().complete();
            pumped++;
            finished++;
        }
        return new Result(faulted, pumped, refused, System.nanoTime() - started);
    }

    private record WindowSlot(Row row, CompletableFuture<Void> future) {
    }
    /** The pump order: ascending seat, one row per seat; two rows on one seat is no order at all. */
    public static List<Row> seatOrder(List<? extends Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(Row::seat));
        Set<Integer> seats = new HashSet<>();
        for (Row row : ordered) {
            if (!seats.add(row.seat())) {
                throw new IllegalStateException(
                    "two rows name the same seat: " + row.seat());
            }
        }
        return List.copyOf(ordered);
    }
}
