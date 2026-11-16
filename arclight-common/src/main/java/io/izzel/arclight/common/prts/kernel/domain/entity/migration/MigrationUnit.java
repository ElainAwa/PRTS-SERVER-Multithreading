/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.migration;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One logical migration unit: the moved row with its passengers, leash, projectiles and target, the
 * two owner sites and the one state machine every member follows. The old owner first stops its
 * movable writes, then the applied prefix is recorded, the unit is published and only then the new
 * owner is granted; one logical update ticks the moved state once and the unit keeps a pending tick
 * until the grant, so the row is neither ticked twice nor dropped.
 */
public final class MigrationUnit {

    /** One member role carried by the same unit. */
    public enum Role {
        SELF,
        PASSENGER,
        LEASH,
        PROJECTILE,
        TARGET
    }

    /** The phases of one handoff; only forward moves are accepted. */
    public enum Phase {
        PLANNED,
        OLD_OWNER_STOPPED,
        PREFIX_RECORDED,
        PUBLISHED,
        NEW_OWNER_GRANTED,
        FAULT
    }

    private final long unitId;
    private final CompositeWorldIdentity source;
    private final CompositeWorldIdentity target;
    private final Set<Role> roles;
    private final long plannedTick;
    private final long logicalUpdateOrdinal;
    private Phase phase = Phase.PLANNED;
    private long appliedPrefix;
    private int ticks;
    private String fault;

    private MigrationUnit(long unitId, CompositeWorldIdentity source, CompositeWorldIdentity target,
                          Set<Role> roles, long plannedTick, long logicalUpdateOrdinal) {
        this.unitId = unitId;
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.roles = Set.copyOf(roles);
        this.plannedTick = plannedTick;
        this.logicalUpdateOrdinal = logicalUpdateOrdinal;
    }

    /** Plans one unit; the target identity must name a newer world incarnation of the same object. */
    public static MigrationUnit plan(long unitId, CompositeWorldIdentity source,
                                     CompositeWorldIdentity target, Set<Role> roles,
                                     long plannedTick, long logicalUpdateOrdinal) {
        Objects.requireNonNull(roles, "roles");
        if (!source.sameObject(new CompositeWorldIdentity(source.worldId(),
            source.worldIncarnation(), source.uuidHigh(), source.uuidLow()))) {
            throw new IllegalArgumentException("the source identity is not stable");
        }
        if (!source.crossesWorld(target)) {
            throw new IllegalArgumentException("a migration crosses a world incarnation");
        }
        if (!roles.contains(Role.SELF)) {
            throw new IllegalArgumentException("a migration unit carries its own row");
        }
        if (plannedTick < 0L || logicalUpdateOrdinal < 0L) {
            throw new IllegalArgumentException("a migration cannot name a negative tick");
        }
        return new MigrationUnit(unitId, source, target, roles, plannedTick, logicalUpdateOrdinal);
    }

    public long unitId() {
        return unitId;
    }

    public CompositeWorldIdentity source() {
        return source;
    }

    public CompositeWorldIdentity target() {
        return target;
    }

    public Set<Role> roles() {
        return roles;
    }

    public long logicalUpdateOrdinal() {
        return logicalUpdateOrdinal;
    }

    public Phase phase() {
        return phase;
    }

    public long appliedPrefix() {
        return appliedPrefix;
    }

    public String fault() {
        return fault;
    }

    /** Stops the old owner's movable writes; only the planned unit may stop. */
    public MigrationUnit stopOldOwner() {
        return transition(Phase.PLANNED, Phase.OLD_OWNER_STOPPED, "the old owner is already running");
    }

    /** Records the prefix the old owner applied; nothing may be published before it is recorded. */
    public MigrationUnit recordPrefix(long prefix) {
        if (prefix < 0L) {
            throw new IllegalArgumentException("an applied prefix cannot be negative");
        }
        transition(Phase.OLD_OWNER_STOPPED, Phase.PREFIX_RECORDED,
            "the prefix is recorded after the old owner stopped");
        appliedPrefix = prefix;
        return this;
    }

    /** Publishes the recorded prefix; a unit without a recorded prefix cannot be published. */
    public MigrationUnit publish() {
        return transition(Phase.PREFIX_RECORDED, Phase.PUBLISHED,
            "the prefix must be recorded before the unit is published");
    }

    /** Grants the new owner exactly once, after the publication. */
    public MigrationUnit grantNewOwner() {
        return transition(Phase.PUBLISHED, Phase.NEW_OWNER_GRANTED,
            "the new owner is granted once, after the publication");
    }

    /** Marks the unit faulty without pretending a completion; the recorded prefix stays. */
    public MigrationUnit fault(String reason) {
        if (phase == Phase.NEW_OWNER_GRANTED || phase == Phase.FAULT) {
            throw new IllegalStateException("a finished unit cannot fault");
        }
        phase = Phase.FAULT;
        fault = reason == null ? "" : reason;
        return this;
    }

    /** Whether one logical update still owes the moved state a tick. */
    public boolean pendingTick() {
        return phase == Phase.PLANNED || phase == Phase.OLD_OWNER_STOPPED
            || phase == Phase.PREFIX_RECORDED || phase == Phase.PUBLISHED;
    }

    /** Ticks the moved state once after the grant; a second tick of the same update is refused. */
    public int tickOnce() {
        if (phase != Phase.NEW_OWNER_GRANTED) {
            throw new IllegalStateException("the new owner is not granted yet");
        }
        if (ticks > 0) {
            throw new IllegalStateException("one logical update ticks the moved state once");
        }
        ticks++;
        return ticks;
    }

    public int ticks() {
        return ticks;
    }

    private MigrationUnit transition(Phase expected, Phase next, String message) {
        if (phase != expected) {
            throw new IllegalStateException(message + " (was " + phase + ")");
        }
        phase = next;
        return this;
    }
}
