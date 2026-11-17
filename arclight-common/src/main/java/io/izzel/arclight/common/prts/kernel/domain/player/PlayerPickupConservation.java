/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The pickup transaction of one player session: the source stack is deducted and the target
 * inventory is increased together with the event result, a cancelled or failed pickup leaves both
 * sides untouched, one pickup id consumes once, and a result of an older session generation is
 * refused. Input, session and world state keep separate owner keys so a read-only input parse never
 * holds the world-state owner. */
public final class PlayerPickupConservation {

    /** The player resource families that must stay separate owners. */
    public enum Resource {
        PLAYER_INPUT,
        PLAYER_SESSION,
        PLAYER_WORLD_STATE,
        INVENTORY,
        LIFE,
        POSITION
    }

    /** What one pickup moves. */
    public enum Kind {
        ITEM,
        EXPERIENCE,
        DROP
    }

    /** One pickup: its id, kind, source key, target key and quantity. */
    public record Pickup(String pickupId, Kind kind, String sourceKey, String targetKey,
                         long quantity) {

        public Pickup {
            Objects.requireNonNull(pickupId, "pickupId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(targetKey, "targetKey");
            if (pickupId.isEmpty() || sourceKey.isEmpty() || targetKey.isEmpty()) {
                throw new IllegalArgumentException("a pickup names itself and both sides");
            }
            if (quantity <= 0L) {
                throw new IllegalArgumentException("a pickup quantity is positive");
            }
        }
    }

    /** The balances one pickup transaction reads and writes. */
    public interface Ledger {

        long balance(Kind kind, String key);

        boolean canAccept(Kind kind, String key, long quantity);

        void apply(Kind kind, String key, long delta);
    }

    /** What one pickup did. */
    public enum Result {
        COMMITTED,
        DUPLICATE,
        REFUSED_EVENT,
        REFUSED_SOURCE,
        REFUSED_TARGET,
        STALE_SESSION,
        ROLLED_BACK
    }

    /** The answer of one pickup with its retained-prefix fault flag. */
    public record Outcome(Result result, boolean faulted) {
    }

    private final Ledger ledger;
    private final Map<String, Result> consumed = new LinkedHashMap<>();
    private long committed;
    private long refused;
    private long rolledBack;
    private long staleSessions;

    public PlayerPickupConservation(Ledger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** The stable owner keys of one player session; a chunk or a world never merges them. */
    public static List<String> ownerKeys(String playerKey, long sessionGeneration) {
        Objects.requireNonNull(playerKey, "playerKey");
        return List.of("player-input:" + playerKey, "player-session:" + playerKey + ":"
            + sessionGeneration, "player-world:" + playerKey,
            "player-inventory:" + playerKey, "player-life:" + playerKey,
            "player-position:" + playerKey);
    }

    /** Commits one pickup only for the current session generation; a duplicate consumes nothing. */
    public synchronized Outcome commit(Pickup pickup, long sessionGeneration,
                                       long currentSessionGeneration, boolean eventAllowed) {
        Objects.requireNonNull(pickup, "pickup");
        if (sessionGeneration != currentSessionGeneration) {
            staleSessions++;
            return new Outcome(Result.STALE_SESSION, false);
        }
        if (consumed.containsKey(pickup.pickupId())) {
            return new Outcome(Result.DUPLICATE, false);
        }
        if (!eventAllowed) {
            refused++;
            return new Outcome(Result.REFUSED_EVENT, false);
        }
        if (ledger.balance(pickup.kind(), pickup.sourceKey()) < pickup.quantity()) {
            refused++;
            return new Outcome(Result.REFUSED_SOURCE, false);
        }
        if (!ledger.canAccept(pickup.kind(), pickup.targetKey(), pickup.quantity())) {
            refused++;
            return new Outcome(Result.REFUSED_TARGET, false);
        }
        boolean deducted = false;
        try {
            ledger.apply(pickup.kind(), pickup.sourceKey(), -pickup.quantity());
            deducted = true;
            ledger.apply(pickup.kind(), pickup.targetKey(), pickup.quantity());
        } catch (RuntimeException failure) {
            if (deducted) {
                ledger.apply(pickup.kind(), pickup.sourceKey(), pickup.quantity());
            }
            rolledBack++;
            consumed.put(pickup.pickupId(), Result.ROLLED_BACK);
            return new Outcome(Result.ROLLED_BACK, true);
        }
        consumed.put(pickup.pickupId(), Result.COMMITTED);
        committed++;
        return new Outcome(Result.COMMITTED, false);
    }

    public synchronized long committed() {
        return committed;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long rolledBack() {
        return rolledBack;
    }

    public synchronized long staleSessions() {
        return staleSessions;
    }
}
