/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whitelist of the controlled takeover and the per-class census of the rows it saw. Only the
 * classes whose whole tick is reproduced bit for bit may be claimed; a row of any other class is
 * watched and never claimed, and the census counts it under its own class so the evidence can show
 * that the takeover stayed inside the list. The plan point counts a row when it offers it, the host
 * entry counts the outcome it decided for the row, and both counts are written on the tick thread.
 *
 * <p>The counts are observation only: no decision of the fixture reads them, and every one of them
 * reads zero while the fixture is off.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** The classes one takeover may claim, and what every class the plan point saw ended in. */
public final class TakeoverWhitelist {

    /** How many classes the list holds; the slot of a row is its index in the model registry. */
    public static final int SLOTS = TickModels.SLOTS;
    /** How many classes outside the list the census names before it lumps the rest together. */
    private static final int NAMED_OUTSIDE = 16;

    private static final LongAdder[] SEEN = adders(SLOTS);
    private static final LongAdder[] CLAIMED = adders(SLOTS);
    private static final LongAdder[] SKIPPED = adders(SLOTS);
    private static final LongAdder[] EXECUTED = adders(SLOTS);
    private static final LongAdder[] PASSENGERS = adders(SLOTS);
    private static final LongAdder OUTSIDE_SEEN = new LongAdder();
    private static final LongAdder OUTSIDE_CLAIMED = new LongAdder();
    private static final LongAdder OUTSIDE_PASSENGERS = new LongAdder();
    // The outside census names the classes it saw; it is written and read on the tick thread only.
    private static final Map<Class<?>, LongAdder> OUTSIDE_BY_CLASS = new Object2ObjectOpenHashMap<>();
    private static final LongAdder OUTSIDE_OTHER = new LongAdder();
    private static int outsideNamed;

    private TakeoverWhitelist() {
    }

    /** The name the evidence line reports one slot under, or {@code outside} for a refused class. */
    public static String nameOf(int slot) {
        return slot >= 0 && slot < SLOTS ? TickModels.nameOf(slot) : "outside";
    }

    /** Counts one row the plan point offered, by its class slot; a refused class is named once. */
    static void noteSeen(int slot, Class<?> type) {
        if (slot >= 0 && slot < SLOTS) {
            SEEN[slot].increment();
            return;
        }
        OUTSIDE_SEEN.increment();
        LongAdder named = OUTSIDE_BY_CLASS.get(type);
        if (named != null) {
            named.increment();
        } else if (outsideNamed < NAMED_OUTSIDE) {
            outsideNamed++;
            LongAdder created = new LongAdder();
            created.increment();
            OUTSIDE_BY_CLASS.put(type, created);
        } else {
            OUTSIDE_OTHER.increment();
        }
    }

    /** Counts one row the plan point claimed. A row outside the list must never reach this: the
     * counter is the witness that the claim stayed inside the whitelist. */
    static void noteClaimed(int slot) {
        if (slot >= 0 && slot < SLOTS) {
            CLAIMED[slot].increment();
        } else {
            OUTSIDE_CLAIMED.increment();
        }
    }

    /** Counts one host entry of a claimed row: skipped, or handed back so the host runs it. */
    static void noteEntry(int slot, boolean skipped) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        if (skipped) {
            SKIPPED[slot].increment();
        } else {
            EXECUTED[slot].increment();
        }
    }

    /** Counts one row the predicate refused because it rides, is ridden or is leashed. */
    static void notePassenger(int slot) {
        if (slot >= 0 && slot < SLOTS) {
            PASSENGERS[slot].increment();
        } else {
            OUTSIDE_PASSENGERS.increment();
        }
    }

    /** Rows of one whitelisted class the plan point claimed. */
    public static long claimed(int slot) {
        return slot >= 0 && slot < SLOTS ? CLAIMED[slot].sum() : 0L;
    }

    /** Rows of one whitelisted class the host entry skipped under its ownership token. */
    public static long skipped(int slot) {
        return slot >= 0 && slot < SLOTS ? SKIPPED[slot].sum() : 0L;
    }

    /** One evidence line: every whitelisted class with what it ended in, then the outside rows. */
    public static String evidenceLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-whitelist:");
        for (int slot = 0; slot < SLOTS; slot++) {
            builder.append(' ').append(nameOf(slot)).append('=')
                .append(SEEN[slot].sum()).append(',')
                .append(CLAIMED[slot].sum()).append(',')
                .append(SKIPPED[slot].sum()).append(',')
                .append(EXECUTED[slot].sum()).append(',')
                .append(PASSENGERS[slot].sum());
        }
        builder.append(" outside_rows=").append(OUTSIDE_SEEN.sum());
        builder.append(" outside_claimed=").append(OUTSIDE_CLAIMED.sum());
        builder.append(" outside_passengers=").append(OUTSIDE_PASSENGERS.sum());
        builder.append(" outside_classes=");
        int named = 0;
        for (Map.Entry<Class<?>, LongAdder> entry : OUTSIDE_BY_CLASS.entrySet()) {
            if (named++ > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey().getSimpleName()).append(':').append(entry.getValue().sum());
        }
        if (named == 0) {
            builder.append("none");
        }
        builder.append(" outside_other=").append(OUTSIDE_OTHER.sum());
        return builder.toString();
    }

    /** Contributes the census of the whitelist and of the rows outside it. */
    public static void readings(DomainReadings sink) {
        for (int slot = 0; slot < SLOTS; slot++) {
            String prefix = "entity.whitelist_" + nameOf(slot) + "_";
            sink.add(prefix + "seen", SEEN[slot].sum());
            sink.add(prefix + "claimed", CLAIMED[slot].sum());
            sink.add(prefix + "skipped", SKIPPED[slot].sum());
        }
        sink.add("entity.outside_rows", OUTSIDE_SEEN.sum());
        sink.add("entity.outside_claimed", OUTSIDE_CLAIMED.sum());
        sink.add("entity.passenger_rows", passengerRows());
    }

    /** Rows the predicate refused because they ride, across the whitelisted classes. */
    public static long passengerRows() {
        long rows = 0L;
        for (LongAdder counter : PASSENGERS) {
            rows += counter.sum();
        }
        return rows;
    }

    /** Clears every counter; the readout reset uses it. */
    static void reset() {
        for (int slot = 0; slot < SLOTS; slot++) {
            SEEN[slot].reset();
            CLAIMED[slot].reset();
            SKIPPED[slot].reset();
            EXECUTED[slot].reset();
            PASSENGERS[slot].reset();
        }
        OUTSIDE_SEEN.reset();
        OUTSIDE_CLAIMED.reset();
        OUTSIDE_PASSENGERS.reset();
        OUTSIDE_BY_CLASS.clear();
        OUTSIDE_OTHER.reset();
        outsideNamed = 0;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int index = 0; index < count; index++) {
            adders[index] = new LongAdder();
        }
        return adders;
    }
}
