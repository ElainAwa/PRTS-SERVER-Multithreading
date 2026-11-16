/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The death-drop transaction: the host event decides the drops, then one controlled node adds each
 * drop exactly once, an exception keeps the prefix and never re-adds it, and a pickup can consume
 * one added drop once. The player pickup and the network boundary are outside this contract and are
 * marked for the player work; the transaction writes no world object.
 */
public final class DropTransaction {

    /** Where the player pickup boundary sits; J03 owns it and this contract does not run it. */
    public enum PlayerPickupBoundary {
        HOST_PARITY_ONLY,
        PLAYER_BOUNDARY_J03
    }

    /** The phases of one drop transaction. */
    public enum Phase {
        PLANNED,
        EVENT_DECIDED,
        ADDED,
        FAULT
    }

    /** One drop, named by its stable key. */
    public record Drop(String itemKey) {

        public Drop {
            Objects.requireNonNull(itemKey, "itemKey");
            if (itemKey.isEmpty()) {
                throw new IllegalArgumentException("a drop needs its key");
            }
        }
    }

    /** What one drop transaction did; the added count is the kept prefix. */
    public record Result(int decided, int added, int picked, boolean faulted, String detail) {
    }

    private final List<Drop> planned;
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> picked = new LinkedHashSet<>();
    private Phase phase = Phase.PLANNED;
    private int decided;
    private boolean faulted;
    private String detail = "";

    private DropTransaction(List<Drop> planned) {
        this.planned = List.copyOf(planned);
    }

    /** Plans one transaction over the drops the caller expects from the event. */
    public static DropTransaction plan(List<Drop> planned) {
        Objects.requireNonNull(planned, "planned");
        return new DropTransaction(planned);
    }

    public Phase phase() {
        return phase;
    }

    /** Consumes the host event's decided count; the transaction may then add. */
    public DropTransaction decide(int eventCount) {
        if (phase != Phase.PLANNED) {
            throw new IllegalStateException("the event decision arrives once");
        }
        if (eventCount != planned.size()) {
            throw new IllegalArgumentException("the event decided another drop set");
        }
        decided = eventCount;
        phase = Phase.EVENT_DECIDED;
        return this;
    }

    /** Adds every decided drop once; an adder failure keeps the added prefix and never re-adds it. */
    public Result addOnce(Consumer<Drop> adder) {
        Objects.requireNonNull(adder, "adder");
        if (phase == Phase.PLANNED) {
            throw new IllegalStateException("no drop is added before the event decided");
        }
        if (phase == Phase.ADDED || faulted) {
            throw new IllegalStateException("a drop set is added once");
        }
        for (Drop drop : planned) {
            if (added.contains(drop.itemKey())) {
                continue;
            }
            try {
                adder.accept(drop);
            } catch (RuntimeException fault) {
                faulted = true;
                detail = "add-fault:" + fault.getClass().getSimpleName();
                phase = Phase.FAULT;
                return result();
            }
            added.add(drop.itemKey());
        }
        phase = Phase.ADDED;
        return result();
    }

    /** Picks one added drop exactly once; a second pickup of the same drop is refused. */
    public boolean pickOnce(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey");
        if (phase != Phase.ADDED || !added.contains(itemKey)) {
            throw new IllegalStateException("a drop is picked after it was added");
        }
        return picked.add(itemKey);
    }

    /** The drops added so far, in the planned order; the kept prefix after a fault. */
    public List<Drop> addedPrefix() {
        List<Drop> prefix = new ArrayList<>(added.size());
        for (Drop drop : planned) {
            if (added.contains(drop.itemKey())) {
                prefix.add(drop);
            }
        }
        return List.copyOf(prefix);
    }

    public Result result() {
        return new Result(decided, added.size(), picked.size(), faulted, detail);
    }

    /** The boundary the host still owns: player pickup is not run by this contract. */
    public static PlayerPickupBoundary playerBoundary() {
        return PlayerPickupBoundary.PLAYER_BOUNDARY_J03;
    }
}
