/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The settlement obligations one range-seal factory holds on the seals it published. A settlement
 * names the place of a seal and the registry settles and drops that entry; a reset that ends the
 * table ends the registry with it, so nothing here can revive a seal of a previous lifecycle. */
public final class RangeSettlementObligations {

    private final Map<RangeSealTable.Key, IdentityWitness.Obligation> held = new LinkedHashMap<>();
    private long heldTotal;
    private long settledTotal;
    private long discardedTotal;
    private long unbackedTotal;
    private long settleFailures;

    /** Whether the place of one published seal is held already. A seal that names no place is
     * answered false rather than keyed by a guess. */
    public synchronized boolean holds(FrozenDeclarations.RangeSeal seal) {
        return seal != null && seal.wellFormed() && held.containsKey(keyOf(seal));
    }

    /** Registers the pin one published seal owes. The first hold of a place keeps the obligation;
     * a repeated hold is refused, and an obligation that pins nothing is counted and not kept. */
    public synchronized boolean hold(FrozenDeclarations.RangeSeal seal,
                                     IdentityWitness.Obligation obligation) {
        if (seal == null || !seal.wellFormed() || obligation == null) {
            return false;
        }
        RangeSealTable.Key key = keyOf(seal);
        if (held.containsKey(key)) {
            return false;
        }
        if (obligation == IdentityWitness.Obligation.NONE) {
            unbackedTotal++;
            return false;
        }
        held.put(key, obligation);
        heldTotal++;
        return true;
    }

    /** Settles every obligation of one seat and drops those entries. An unknown seat settles
     * nothing; an obligation that throws keeps its entry and is counted for a later retry. */
    public synchronized int settleSeat(FrozenDeclarations.Seat seat) {
        if (seat == null) {
            return 0;
        }
        List<RangeSealTable.Key> matching = new ArrayList<>();
        for (RangeSealTable.Key key : held.keySet()) {
            if (key.seat().equals(seat)) {
                matching.add(key);
            }
        }
        return settle(matching);
    }

    /** Settles every obligation of a plan older than the given one and drops those entries: a
     * settlement period that has moved past that plan can no longer name its seals. */
    public synchronized int settleBefore(long planSequence) {
        List<RangeSealTable.Key> older = new ArrayList<>();
        for (RangeSealTable.Key key : held.keySet()) {
            if (key.seat().planSequence() < planSequence) {
                older.add(key);
            }
        }
        return settle(older);
    }

    /** Drops every entry without settling it: it runs after the table reset has cleared the pins
     * the entries named, so a settlement would be a no-op on a table that no longer holds them. */
    public synchronized void reset() {
        discardedTotal += held.size();
        held.clear();
    }

    /** How many places still hold a pin. */
    public synchronized int heldCount() {
        return held.size();
    }

    /** How many holds this registry registered. */
    public synchronized long heldTotal() {
        return heldTotal;
    }

    /** How many obligations were settled and dropped. */
    public synchronized long settled() {
        return settledTotal;
    }

    /** How many entries a reset dropped unsettled. */
    public synchronized long discarded() {
        return discardedTotal;
    }

    /** Holds that found no pin to register. */
    public synchronized long unbacked() {
        return unbackedTotal;
    }

    /** Settlements an obligation refused by throwing; the entry is still held. */
    public synchronized long settleFailures() {
        return settleFailures;
    }

    /** The places this registry holds, in the order they were first held. */
    public synchronized List<RangeSealTable.Key> heldKeys() {
        return List.copyOf(new ArrayList<>(held.keySet()));
    }

    private int settle(List<RangeSealTable.Key> keys) {
        int settled = 0;
        for (RangeSealTable.Key key : keys) {
            IdentityWitness.Obligation obligation = held.get(key);
            if (obligation == null) {
                continue;
            }
            try {
                obligation.settle();
            } catch (RuntimeException refused) {
                settleFailures++;
                continue;
            }
            held.remove(key);
            settledTotal++;
            settled++;
        }
        return settled;
    }

    private static RangeSealTable.Key keyOf(FrozenDeclarations.RangeSeal seal) {
        long cell = seal.statuses().size() == 1 ? seal.statuses().keySet().iterator().next()
            : Long.MIN_VALUE;
        return new RangeSealTable.Key(seal.freeze(), seal.seat(), cell);
    }
}