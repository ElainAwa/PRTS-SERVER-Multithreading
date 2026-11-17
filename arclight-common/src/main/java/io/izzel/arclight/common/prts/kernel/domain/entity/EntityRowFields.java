/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The frozen read fields of one real entity row: plain numbers captured on the tick thread before
 * any worker can see the row. No Level, Entity, Brain, Goal or Path object is kept; every field is
 * bound from a real getter or explicitly left unbound with its reason. The declarations carry the
 * owner, source, version and unbound semantics of every field. */
public record EntityRowFields(boolean removed, boolean passenger, int tickCount, double health,
                              double maxHealth, int airSupply, int maxAirSupply,
                              boolean hasTarget, boolean noAi, int brainActiveMask,
                              int goalRunningMask, int goalPriorityMask, int sensorTick,
                              int lastSensorTick, boolean navPathPresent, boolean navInProgress,
                              double navProgress, Set<String> boundGroups) {

    /** One declared field: who captures it, from which real source, its version and unbound meaning. */
    public record Declaration(String group, String owner, String source, int version, String type,
                              String unbound) {
    }

    private static final List<Declaration> DECLARATIONS = List.of(
        new Declaration("LIFECYCLE", "server-tick-thread", "Entity.isRemoved/isPassenger/tickCount",
            1, "boolean/boolean/int", "state-refused"),
        new Declaration("HEALTH", "server-tick-thread", "LivingEntity.getHealth/getMaxHealth",
            1, "double/double", "not-a-living-row"),
        new Declaration("AIR", "server-tick-thread", "LivingEntity.getAirSupply/getMaxAirSupply",
            1, "int/int", "not-a-living-row"),
        new Declaration("MOB", "server-tick-thread", "Mob.getTarget/isNoAi",
            1, "boolean/boolean", "not-a-mob-row"),
        new Declaration("BRAIN", "server-tick-thread", "Brain.isActive(Activity) fixed mask",
            1, "int", "not-a-brain-row"),
        new Declaration("GOAL", "server-tick-thread",
            "GoalSelector.getRunningGoals priority mask", 1, "int/int", "not-a-mob-row"),
        new Declaration("SENSOR", "server-tick-thread", "no public sensor tick source", 1,
            "int/int", "sensor-tick-unbound"),
        new Declaration("NAVIGATION", "server-tick-thread",
            "PathNavigation.getPath/isInProgress", 1, "boolean/boolean/double",
            "not-a-path-row"));

    public EntityRowFields {
        Objects.requireNonNull(boundGroups, "boundGroups");
        boundGroups = Set.copyOf(boundGroups);
    }

    /** The row of no real entity: every group unbound and every numeric field the -1 sentinel. */
    public static EntityRowFields unbound() {
        return new EntityRowFields(false, false, -1, -1.0, -1.0, -1, -1, false, false, -1, -1, -1,
            -1, -1, false, false, -1.0, Set.of());
    }

    /** Whether one declared group was really captured. */
    public boolean bound(String group) {
        return boundGroups.contains(group);
    }

    /** Requires the captured groups before any effect; an unbound field is a named refusal. */
    public void requireBound(Set<String> required) {
        Objects.requireNonNull(required, "required");
        for (String group : required) {
            if (!bound(group)) {
                throw new IllegalStateException("unbound-read:" + group);
            }
        }
    }

    /** The named reason why one group is unbound, or the empty string when it is bound. */
    public String unboundReason(String group) {
        if (bound(group)) {
            return "";
        }
        for (Declaration declaration : DECLARATIONS) {
            if (declaration.group().equals(group)) {
                return declaration.unbound();
            }
        }
        return "unknown-field-group";
    }

    /** The internal declarative matrix of every field and its owner/source/version. */
    public static List<Declaration> declarations() {
        return DECLARATIONS;
    }

    /** The groups every extended read must carry before the first effect. */
    public static Set<String> requiredGroups() {
        List<String> groups = new ArrayList<>();
        for (Declaration declaration : DECLARATIONS) {
            groups.add(declaration.group());
        }
        return Set.copyOf(groups);
    }
}
