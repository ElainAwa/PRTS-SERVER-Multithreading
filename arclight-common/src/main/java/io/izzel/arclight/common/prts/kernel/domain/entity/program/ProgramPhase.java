/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

/** The phases of one entity update program, in execution order. A phase that writes is only
 * reachable after the decision phase, and the phases after the first effect may observe the own
 * write; the phases before it read shared input only. */
public enum ProgramPhase {

    /** The frozen input of the update: identity, world incarnation and the state it was read at. */
    INPUT(false, false),
    /** The read-only computation over the shared input; no own effect exists yet. */
    READ_COMPUTE(false, false),
    /** The domain and plugin decisions of this update; the result is fixed but not written. */
    DECISION(false, false),
    /** The controlled write of the effects the decision allowed. */
    CONTROLLED_WRITE(true, true),
    /** The successor logic that may read the own write of the controlled phase. */
    SUCCESSOR(false, true),
    /** The publication of the effects to their declared visible exit. */
    PUBLISH(true, true);

    private final boolean writes;
    private final boolean afterEffect;

    ProgramPhase(boolean writes, boolean afterEffect) {
        this.writes = writes;
        this.afterEffect = afterEffect;
    }

    /** Whether the phase may write. */
    public boolean writes() {
        return writes;
    }

    /** Whether the phase runs before any effect of this update. */
    public boolean readOnly() {
        return !afterEffect;
    }

    /** Whether the phase runs after the first effect of this update. */
    public boolean afterEffect() {
        return afterEffect;
    }
}
