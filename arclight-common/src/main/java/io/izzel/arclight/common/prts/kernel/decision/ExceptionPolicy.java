/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** The exception policy of the decision entries, one entry at a time: the platform entry records and
 * continues, a native hook throws to its caller, and an unnamed entry is a fault. */
public final class ExceptionPolicy {

    /** What an entry does with one thrown exception. */
    public enum Action {
        /** The entry catches, records and lets the original call continue. */
        RECORD_AND_CONTINUE,
        /** The entry hands the exception back to its caller. */
        PROPAGATE_TO_CALLER,
        /** The entry keeps the applied prefix, records the fault and dispatches nothing further. */
        FAULT_KEEP_PREFIX
    }

    /** The platform entry that dispatches a plugin event chain. */
    public static final String BUKKIT_LISTENER_DISPATCH = "bukkit-listener-dispatch";
    /** The native entry whose caller expects an exception to propagate. */
    public static final String NATIVE_HOOK = "native-hook";
    /** The air entry of the entity update. */
    public static final String NATIVE_HOOK_AIR = "native-hook-air";
    /** The damage entry of the entity update. */
    public static final String NATIVE_HOOK_DAMAGE = "native-hook-damage";

    private final Map<String, Action> table = new HashMap<>();
    private final Action unknownAction;

    private ExceptionPolicy(Action unknownAction) {
        this.unknownAction = unknownAction;
    }

    /** The table of the entries this build names; an entry outside it is a fault. */
    public static ExceptionPolicy defaults() {
        ExceptionPolicy policy = new ExceptionPolicy(Action.FAULT_KEEP_PREFIX);
        policy.declare(BUKKIT_LISTENER_DISPATCH, Action.RECORD_AND_CONTINUE);
        policy.declare(NATIVE_HOOK, Action.PROPAGATE_TO_CALLER);
        policy.declare(NATIVE_HOOK_AIR, Action.PROPAGATE_TO_CALLER);
        policy.declare(NATIVE_HOOK_DAMAGE, Action.PROPAGATE_TO_CALLER);
        return policy;
    }

    /** Overrides or adds one entry; the caller names the entry it is responsible for. */
    public ExceptionPolicy declare(String entry, Action action) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(action, "action");
        table.put(entry, action);
        return this;
    }

    /** What one entry does with one thrown exception. */
    public Action decide(String entry, Throwable thrown) {
        if (entry == null) {
            return unknownAction;
        }
        Action action = table.get(entry);
        if (action != null) {
            return action;
        }
        // The entry may name its class as the entry: the root entry keeps its policy.
        int kind = entry.indexOf('/');
        if (kind > 0) {
            action = table.get(entry.substring(0, kind));
            if (action != null) {
                return action;
            }
        }
        return unknownAction;
    }

    /** The action the table answers for an entry the caller did not name. */
    public Action unknownAction() {
        return unknownAction;
    }
}
