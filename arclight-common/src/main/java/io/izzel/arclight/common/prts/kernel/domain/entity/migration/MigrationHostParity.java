/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.migration;

import java.util.List;
import java.util.Objects;

/**
 * The host entry points one migration must stay comparable with: the dimension change, the riding
 * relation, the leash relation and the death-drop ladder. The parity check only orders the observed
 * host steps; it runs no host code, and a build without a planned migration unit keeps the host
 * path exactly as it is.
 */
public final class MigrationHostParity {

    /** One host step of a migration parity check. */
    public record Step(String name, String hostEntry) {

        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(hostEntry, "hostEntry");
        }
    }

    /** What one observed host sequence did. */
    public record Verdict(boolean matched, String offending, String detail) {

        public Verdict {
            Objects.requireNonNull(detail, "detail");
        }
    }

    private static final List<Step> STEPS = List.of(
        new Step("changeDimension", "Entity#changeDimension"),
        new Step("riding", "Entity#startRiding"),
        new Step("leash", "Entity#setLeashedTo"),
        new Step("dropAllDeathLoot", "LivingEntity#dropAllDeathLoot"));

    private MigrationHostParity() {
    }

    /** The host steps a migration stays comparable with, in the host order. */
    public static List<Step> hostSteps() {
        return STEPS;
    }

    /** Checks that one observed host sequence names the host steps in order. */
    public static Verdict verify(List<String> observed) {
        Objects.requireNonNull(observed, "observed");
        for (int index = 0; index < observed.size() && index < STEPS.size(); index++) {
            if (!STEPS.get(index).name().equals(observed.get(index))) {
                return new Verdict(false, observed.get(index), "host-step-order");
            }
        }
        if (observed.size() != STEPS.size()) {
            return new Verdict(false, observed.size() > STEPS.size()
                ? observed.get(STEPS.size()) : null, "host-step-count");
        }
        return new Verdict(true, null, "host-order-kept");
    }

    /** Whether this build keeps the host path for a row with no planned migration unit. */
    public static boolean defaultHostPath() {
        return true;
    }
}
