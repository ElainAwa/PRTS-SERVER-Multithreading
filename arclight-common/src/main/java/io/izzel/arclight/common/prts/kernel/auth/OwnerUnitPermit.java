/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

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

    private record Unit(String worldId, EnumSet<Capability> capabilities, boolean authorized,
                        String leasedDomain) {
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

    /** Binds the calling thread as one owner unit without any right: nothing passes until a real
     * lease receipt is bound by {@link #bindLease(String)}. */
    public static void enter(String worldId) {
        CURRENT.set(new Unit(worldId, EnumSet.of(Capability.ENTITY_STATE,
            Capability.CHUNK_DEMAND, Capability.EVENT_HOST), false, ""));
    }

    public static void exit() {
        CURRENT.remove();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Binds the admitted object domain after the registry lease and the authority decision were
     * granted; a unit without a bound domain cannot pass a guarded surface. */
    public static void bindLease(String domainId) {
        Unit unit = CURRENT.get();
        if (unit != null) {
            CURRENT.set(new Unit(unit.worldId(), unit.capabilities(), true,
                domainId == null ? "" : domainId));
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

    /** Whether the calling unit's admitted resources cover the native surface the guard protects:
     * the same object domain the row leased, or (for server-side bookkeeping) the same world. Block
     * and menu surfaces are never covered by an object-domain unit. */
    public static boolean allowsTarget(String ownerClass, String method, String descriptor,
                                       Object[] arguments) {
        Unit unit = CURRENT.get();
        if (unit == null || !unit.authorized() || unit.leasedDomain().isEmpty()) {
            return false;
        }
        String target = ownerClass + "#" + method;
        switch (target) {
            case "net/minecraft/server/level/ServerLevel#getEntities":
                return arguments.length > 0 && worldOf(arguments[0], unit);
            case "net/minecraft/server/level/ServerLevel#addEntity":
                return arguments.length > 1 && sameEntity(arguments[1], unit);
            case "net/minecraft/server/level/ServerLevel$EntityCallbacks#onTrackingStart":
            case "net/minecraft/server/level/ServerLevel$EntityCallbacks#onTrackingEnd":
                return arguments.length > 1 && worldOf(arguments[1], unit);
            case "net/minecraft/server/level/ChunkMap$TrackedEntity#removePlayer":
            case "net/minecraft/server/level/ChunkMap$TrackedEntity#updatePlayer":
                return arguments.length > 1 && worldOf(arguments[1], unit);
            case "net/minecraft/server/level/ChunkMap#addEntity":
            case "net/minecraft/server/level/ChunkMap#removeEntity":
                return arguments.length > 1 && (sameEntity(arguments[1], unit)
                    || worldOf(arguments[1], unit));
            case "net/minecraft/world/entity/LivingEntity#addEffect":
                return arguments.length > 0 && sameEntity(arguments[0], unit);
            default:
                return false;
        }
    }

    private static boolean sameEntity(Object candidate, Unit unit) {
        if (!(candidate instanceof Entity entity)) {
            return false;
        }
        String expected = "entity-state:" + entity.getUUID().getMostSignificantBits() + ":"
            + entity.getUUID().getLeastSignificantBits();
        return unit.leasedDomain().startsWith(expected);
    }

    private static boolean worldOf(Object candidate, Unit unit) {
        String worldId;
        if (candidate instanceof ServerLevel level) {
            worldId = level.dimension().location().toString();
        } else if (candidate instanceof Entity entity) {
            worldId = entity.level().dimension().location().toString();
        } else if (candidate instanceof ServerPlayer player) {
            worldId = player.level().dimension().location().toString();
        } else {
            return false;
        }
        return unit.worldId().equals(worldId);
    }

    /** The host-served chunk demand bridge is only for an admitted owner unit. */
    public static boolean chunkDemand() {
        Unit unit = CURRENT.get();
        return unit != null && unit.authorized() && !unit.leasedDomain().isEmpty()
            && unit.capabilities().contains(Capability.CHUNK_DEMAND);
    }

    /** Events an owner unit constructs carry the host dispatch shape; this is not a main-thread
     * claim: only the admitted unit window asks for it. */
    public static boolean eventShape() {
        Unit unit = CURRENT.get();
        return unit != null && unit.authorized() && !unit.leasedDomain().isEmpty()
            && unit.capabilities().contains(Capability.EVENT_HOST);
    }
}