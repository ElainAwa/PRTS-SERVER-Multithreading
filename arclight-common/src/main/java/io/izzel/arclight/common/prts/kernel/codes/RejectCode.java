/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/**
 * Refusal codes of the kernel.
 *
 * <p>The set is closed: this batch adds no code and every trigger of the four pieces maps onto a
 * member of this enum. A code is a report, not a control flow: a count of zero is published as
 * well, and a code is never invented at a call site.</p>
 */
public enum RejectCode {

    WAIT_BOUND_EXCEEDED,
    READ_MATERIALIZE_DENIED,
    QUEUE_CAP_EXCEEDED,
    SCALE_BUDGET_EXCEEDED,
    RECOMPUTE_UNBOUNDED,
    QUOTA_EXCEEDED,
    NATIVE_UNDECLARED,
    NATIVE_MAINLANE_PARK,
    NATIVE_CALLBACK_DENIED,
    NATIVE_UNREPLAYABLE,
    PLAN_CLOCK_READ,
    COUNTER_MISSING,
    PROGRESS_UNOBSERVED,
    TICK_BUDGET_EXHAUSTED,
    WRITE_DENIED_NOT_OWNER,
    VERSION_MISMATCH,
    DAG_CYCLE,
    COMMIT_ORDER_VIOLATION,
    WORLD_LIFECYCLE_DENIED,
    CROSS_WORLD_WRITE_DENIED;

    /** @return the code text as it is published */
    public String text() {
        return name();
    }
}
