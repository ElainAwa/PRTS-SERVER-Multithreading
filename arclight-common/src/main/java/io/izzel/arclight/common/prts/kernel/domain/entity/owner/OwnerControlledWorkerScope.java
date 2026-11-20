/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** The reverse-order close of the resources one controlled worker body acquired. A failing close is
 * attached to the failure it interrupted instead of replacing it, and every later close still runs;
 * the caller keeps the aggregated failure as the record's cleanup fault. */
final class OwnerControlledWorkerScope {

    /** One close step of an acquired resource. */
    @FunctionalInterface
    interface Step {
        void close() throws Throwable;
    }

    private OwnerControlledWorkerScope() {
    }

    /** Runs every step in the given order, aggregating failures; a null entry is skipped. */
    static Throwable closeSteps(Throwable primary, Step... steps) {
        Throwable failure = primary;
        if (steps == null) {
            return failure;
        }
        for (Step step : steps) {
            if (step == null) {
                continue;
            }
            try {
                step.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }
}
