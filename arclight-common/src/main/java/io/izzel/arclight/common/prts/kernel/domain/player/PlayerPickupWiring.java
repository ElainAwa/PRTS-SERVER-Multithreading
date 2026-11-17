/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.player;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/** The production pickup admission: it keeps one session generation per player, asks the existing
 * pickup conservation contract whether one pickup may proceed, and remembers consumed pickup ids so
 * a duplicate (or a reconnect of an old generation) is refused before vanilla writes. It mirrors
 * stack counts only, never writes a world or inventory, and does nothing while the kernel is off. */
public final class PlayerPickupWiring {

    /** What one pickup admission did. */
    public enum Admission {
        COMMITTED,
        DUPLICATE,
        STALE_SESSION,
        REFUSED_TARGET,
        DISABLED
    }

    private static final int CONSUMED_CAP = 4096;
    private static final Map<String, Long> GENERATIONS = new LinkedHashMap<>();
    private static final Map<String, Boolean> CONSUMED = new LinkedHashMap<>(16, 0.75F, true);

    private PlayerPickupWiring() {
    }

    /** Whether the kernel observation is on; a disabled kernel never builds state. */
    public static boolean enabled() {
        return KernelSettings.enabled();
    }

    /** The current session generation of one player; a disabled kernel answers zero. */
    public static synchronized long generation(String playerKey) {
        if (!enabled()) {
            return 0L;
        }
        return GENERATIONS.getOrDefault(playerKey, 0L);
    }

    /** Advances the session generation; the previous generation becomes stale. */
    public static synchronized void advance(String playerKey) {
        if (!enabled() || playerKey == null) {
            return;
        }
        GENERATIONS.merge(playerKey, 1L, Long::sum);
    }

    /** Admits one pickup for the captured generation; a duplicate or stale one is refused. */
    public static synchronized Admission admit(String playerKey, long capturedGeneration,
                                               PlayerPickupConservation.Kind kind,
                                               String pickupId, long quantity, boolean canHold) {
        if (!enabled()) {
            return Admission.DISABLED;
        }
        if (playerKey == null || pickupId == null || quantity <= 0L) {
            return Admission.REFUSED_TARGET;
        }
        long current = GENERATIONS.getOrDefault(playerKey, 0L);
        if (capturedGeneration != current) {
            return Admission.STALE_SESSION;
        }
        if (CONSUMED.containsKey(pickupId)) {
            return Admission.DUPLICATE;
        }
        if (!canHold) {
            return Admission.REFUSED_TARGET;
        }
        PlayerPickupConservation.MirrorLedger ledger =
            new PlayerPickupConservation.MirrorLedger(canHold);
        PlayerPickupConservation contract = new PlayerPickupConservation(ledger);
        PlayerPickupConservation.Pickup pickup = new PlayerPickupConservation.Pickup(pickupId,
            kind, "source:" + pickupId, "target:" + playerKey, quantity);
        PlayerPickupConservation.Outcome outcome = contract.commit(pickup, capturedGeneration,
            current, true);
        if (outcome.result() != PlayerPickupConservation.Result.COMMITTED) {
            return Admission.REFUSED_TARGET;
        }
        CONSUMED.put(pickupId, Boolean.TRUE);
        while (CONSUMED.size() > CONSUMED_CAP) {
            java.util.Iterator<String> eldest = CONSUMED.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return Admission.COMMITTED;
    }

    /** The number of consumed pickup ids retained; test and evidence code only. */
    public static synchronized int consumed() {
        return CONSUMED.size();
    }

    /** Drops every generation and consumed id; test and evidence code only. */
    public static synchronized void reset() {
        GENERATIONS.clear();
        CONSUMED.clear();
    }
}
