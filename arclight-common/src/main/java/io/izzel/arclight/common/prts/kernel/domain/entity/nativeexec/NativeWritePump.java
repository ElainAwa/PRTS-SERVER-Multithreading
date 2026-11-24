/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.ArrayList;
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
