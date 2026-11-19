/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.EnumSet;
import java.util.Set;

/** What one owner unit may do on the thread that runs its body: the world it was admitted for and
 * the native surfaces its admitted resources cover. The host is not told the thread is its main
 * thread; each guarded surface asks this permit for the capability it needs and a unit without the
 * capability is refused. */
public final class OwnerUnitPermit {

    public enum Capability {
        ENTITY_STATE,
        CHUNK_DEMAND,
        EVENT_HOST
    }

    private record Unit(String worldId, EnumSet<Capability> capabilities, boolean authorized) {
    }

    private static final ThreadLocal<Unit> CURRENT = new ThreadLocal<>();

    /** The guarded native surfaces an entity-state ownership covers in this build. Block mutations
     * and GUI opens are deliberately absent: an object-domain unit is not a section right. */
    private static final Set<String> ENTITY_REASONS = Set.of(
        "entity add", "chunk entity get", "entity register", "entity unregister",
        "player tracker clear", "player tracker update", "effect add", "entity track",
        "entity untrack", "loot generate");

    private OwnerUnitPermit() {
    }

    /** Binds the calling thread as one admitted owner unit. */
    public static void enter(String worldId, boolean authorized) {
        CURRENT.set(new Unit(worldId, EnumSet.of(Capability.ENTITY_STATE,
            Capability.CHUNK_DEMAND, Capability.EVENT_HOST), authorized));
    }

    public static void exit() {
        CURRENT.remove();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Upgrades the current unit after the registry lease and the authority decision were granted. */
    public static void authorize() {
        Unit unit = CURRENT.get();
        if (unit != null && !unit.authorized()) {
            CURRENT.set(new Unit(unit.worldId(), unit.capabilities(), true));
        }
    }

    public static boolean authorized() {
        Unit unit = CURRENT.get();
        return unit != null && unit.authorized();
    }

    public static String worldId() {
        Unit unit = CURRENT.get();
        return unit == null ? "" : unit.worldId();
    }

    /** Whether the calling unit's admitted resources cover one guarded native surface. */
    public static boolean allows(String reason) {
        Unit unit = CURRENT.get();
        if (unit == null) {
            return false;
        }
        return unit.authorized() && ENTITY_REASONS.contains(reason)
            && unit.capabilities().contains(Capability.ENTITY_STATE);
    }

    /** The host-served chunk demand bridge is only for an admitted owner unit. */
    public static boolean chunkDemand() {
        Unit unit = CURRENT.get();
        return unit != null && unit.capabilities().contains(Capability.CHUNK_DEMAND);
    }

    /** Events an owner unit constructs carry the host dispatch shape; this is not a main-thread
     * claim: only the admitted unit window asks for it. */
    public static boolean eventShape() {
        Unit unit = CURRENT.get();
        return unit != null && unit.capabilities().contains(Capability.EVENT_HOST);
    }
}