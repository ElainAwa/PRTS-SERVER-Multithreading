/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;


/**
 * Every refusal trigger the four pieces can raise, mapped onto the closed code set.
 *
 * <p>The table is data, not behaviour: the decision point, the share table and the wait registry
 * each raise the member that belongs to them, and this enum is the single place where a trigger is
 * tied to a code and to the disposition it produces. Adding a trigger without a code is not
 * possible here, and {@link #RESERVE_BORROWED} is the one row that deliberately carries no code:
 * a reserved pool taken by the wrong ring is a discipline violation, and the design gives it no
 * refusal code.</p>
 *
 * <p>Twelve rows belong to the write decision point, nine to the share table and eight to the wait
 * registry. A test pins the row count and the code usage so a row cannot disappear quietly.</p>
 */
public enum RejectTrigger {

    // write decision point
    OWNER_MISMATCH(Scope.WRITE, RejectCode.WRITE_DENIED_NOT_OWNER,
        WriteDisposition.DENY, ConflictClass.DEGRADE,
        "the holder clue does not match the holder registered for the domain"),
    UNREGISTERED_WRITE(Scope.WRITE, RejectCode.WRITE_DENIED_NOT_OWNER,
        WriteDisposition.INTENT, ConflictClass.QUEUE,
        "an unregistered writer holds no world write right and goes to the intent channel"),
    CROSS_WORLD_DIRECT_WRITE(Scope.WRITE, RejectCode.CROSS_WORLD_WRITE_DENIED,
        WriteDisposition.INTENT, ConflictClass.QUEUE,
        "a direct write targets a world other than the declared one"),
    DOMAIN_SET_NOT_SPLIT(Scope.WRITE, RejectCode.WRITE_DENIED_NOT_OWNER,
        WriteDisposition.DENY, ConflictClass.DEGRADE,
        "the observed domain set is not covered by the declared read-write set"),
    VERSION_SLOT_MISMATCH(Scope.WRITE, RejectCode.VERSION_MISMATCH,
        WriteDisposition.DENY, ConflictClass.REJECT,
        "the expected version does not match the version slot"),
    TOKEN_EXPIRED(Scope.WRITE, RejectCode.VERSION_MISMATCH,
        WriteDisposition.DENY, ConflictClass.REJECT,
        "the owner token expired or was already reclaimed"),
    INTENT_ORDER_VIOLATION(Scope.WRITE, RejectCode.COMMIT_ORDER_VIOLATION,
        WriteDisposition.DENY, ConflictClass.SKIP,
        "the intent segment does not follow the order frozen at planning time"),
    SITE_DECLARATION_MISSING(Scope.WRITE, RejectCode.NATIVE_UNDECLARED,
        WriteDisposition.DENY, ConflictClass.REJECT,
        "the site carries no complete admission record"),
    INTENT_QUEUE_FULL(Scope.WRITE, RejectCode.QUEUE_CAP_EXCEEDED,
        WriteDisposition.DENY, ConflictClass.SKIP,
        "the intent channel is at its explicit depth and refuses the attempt"),
    LEDGER_CLOSURE_BROKEN(Scope.WRITE, RejectCode.COUNTER_MISSING,
        null, null,
        "the write ledger does not close: attempts differ from the three dispositions"),
    WORLD_LIFECYCLE_VIOLATION(Scope.WRITE, RejectCode.WORLD_LIFECYCLE_DENIED,
        WriteDisposition.DENY, ConflictClass.REJECT,
        "a lifecycle change came from a holder that does not own the lifecycle"),
    PLAN_WALL_CLOCK_READ(Scope.WRITE, RejectCode.PLAN_CLOCK_READ,
        WriteDisposition.DENY, ConflictClass.REJECT,
        "a planning input read the wall clock"),

    // share table
    CLASS_SHARE_OVERRUN(Scope.SHARE, RejectCode.QUOTA_EXCEEDED, null, null,
        "a class spent more than its share in a world"),
    WORLD_FAIR_SHARE_OVERRUN(Scope.SHARE, RejectCode.QUOTA_EXCEEDED, null, null,
        "a world spent more than its fair share"),
    SCALE_BUDGET_OVERRUN(Scope.SHARE, RejectCode.SCALE_BUDGET_EXCEEDED, null, null,
        "the entity or block entity scale is over its budget"),
    UNBOUNDED_RECOMPUTE(Scope.SHARE, RejectCode.RECOMPUTE_UNBOUNDED, null, null,
        "a topology change asked for a full recompute without a budget"),
    TICK_BUDGET_EXHAUSTED_AFTER_DEGRADE(Scope.SHARE, RejectCode.TICK_BUDGET_EXHAUSTED, null, null,
        "the budget is still over after the fixed let-way order"),
    SHARE_QUEUE_DEPTH_AT_CAP(Scope.SHARE, RejectCode.QUEUE_CAP_EXCEEDED, null, null,
        "a queue reached its explicit depth on the time-budget side"),
    SHARE_TABLE_ROW_MISSING(Scope.SHARE, RejectCode.COUNTER_MISSING, null, null,
        "a share row or a zero value is missing, or the conservation is not reproducible"),
    SHARE_PLAN_WALL_CLOCK_READ(Scope.SHARE, RejectCode.PLAN_CLOCK_READ, null, null,
        "the planning side read the wall clock while the shares were built"),
    RESERVE_BORROWED(Scope.SHARE, null, null, null,
        "the time-budget ring drew from the reserved pool"),

    // wait registry
    WAIT_POINT_ELEMENT_MISSING(Scope.WAIT, RejectCode.PROGRESS_UNOBSERVED, null, null,
        "a wait point declaration is missing one of its four elements"),
    UNREGISTERED_WAIT_POINT(Scope.WAIT, RejectCode.PROGRESS_UNOBSERVED, null, null,
        "a wait was observed at a call site no wait point covers"),
    WAIT_BOUND_OVERRUN(Scope.WAIT, RejectCode.WAIT_BOUND_EXCEEDED, null, null,
        "one wait exceeded the configured upper bound"),
    MATERIALIZING_READ_DENIED(Scope.WAIT, RejectCode.READ_MATERIALIZE_DENIED, null, null,
        "a read of an unloaded domain asked for materialization"),
    WAIT_INTENT_QUEUE_AT_CAP(Scope.WAIT, RejectCode.QUEUE_CAP_EXCEEDED, null, null,
        "the intent channel is at its explicit depth for a cross-domain wait"),
    CROSS_WORLD_WITHOUT_WAIT_POINT(Scope.WAIT, RejectCode.CROSS_WORLD_WRITE_DENIED, null, null,
        "a cross-world write did not pass the cross-world wait point"),
    PROGRESS_READING_MISSING(Scope.WAIT, RejectCode.COUNTER_MISSING, null, null,
        "a progress reading that has to be published is missing, zero value included"),
    WAIT_TIME_IN_SELF_CLASS(Scope.WAIT, RejectCode.COUNTER_MISSING, null, null,
        "wait time was charged to a self class instead of the wait side");

    /** Which of the four pieces raises this trigger. */
    public enum Scope {
        /** The write decision point. */
        WRITE,
        /** The time-budget share table. */
        SHARE,
        /** The wait point registry. */
        WAIT
    }

    private final Scope scope;
    private final RejectCode code;
    private final WriteDisposition disposition;
    private final ConflictClass conflictClass;
    private final String description;

    RejectTrigger(Scope scope, RejectCode code, WriteDisposition disposition,
                  ConflictClass conflictClass, String description) {
        this.scope = scope;
        this.code = code;
        this.disposition = disposition;
        this.conflictClass = conflictClass;
        this.description = description;
    }

    /** @return the piece that raises this trigger */
    public Scope scope() {
        return scope;
    }

    /** @return the code this trigger raises, or {@code null} for the discipline row */
    public RejectCode code() {
        return code;
    }

    /** @return the disposition the write decision point returns, or {@code null} elsewhere */
    public WriteDisposition disposition() {
        return disposition;
    }

    /** @return the conflict class of the disposition, or {@code null} elsewhere */
    public ConflictClass conflictClass() {
        return conflictClass;
    }

    /** @return one sentence about the condition this trigger observes */
    public String description() {
        return description;
    }
}
