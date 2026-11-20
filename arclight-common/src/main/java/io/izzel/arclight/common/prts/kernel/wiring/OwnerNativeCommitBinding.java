/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;

/** The production publisher of the one commit chain for the native entity fragment: the controller
 * calls {@link #publish(CommitTicket)} only inside a submit the owner batch opened, and the fragment
 * bound for that submit is the real native action of the same ticket. It is not a queue and not a
 * second chain: a publish with no bound fragment is refused instead of writing a line for an action
 * that did not run. */
public final class OwnerNativeCommitBinding implements CommitController.Publisher,
    CommitController.PublisherName {

    /** The one native fragment a controlled effect may run for the ticket. */
    @FunctionalInterface
    public interface Fragment {
        CommitTicket.Outcome run(CommitTicket ticket);
    }

    private static final OwnerNativeCommitBinding INSTANCE = new OwnerNativeCommitBinding();

    private final ThreadLocal<Fragment> current = new ThreadLocal<>();

    private OwnerNativeCommitBinding() {
    }

    public static OwnerNativeCommitBinding instance() {
        return INSTANCE;
    }

    public CommitController.Publisher publisher() {
        return this;
    }

    @Override
    public String wiring() {
        return "NATIVE-ENTITY";
    }

    /** Runs one controlled write on the owner thread: the fragment is bound only for this submit,
     * so a publish outside it is refused and two tickets can never borrow one fragment. */
    public CommitController.SubmitResult submit(CommitController controller,
                                                CommitTicket.ReserveRequest request,
                                                Fragment fragment) {
        if (controller == null) {
            throw new IllegalStateException("the commit chain is not installed");
        }
        if (fragment == null) {
            throw new IllegalStateException("a controlled write needs its native fragment");
        }
        Fragment previous = current.get();
        current.set(fragment);
        try {
            return controller.submit(request);
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    @Override
    public CommitTicket.Outcome publish(CommitTicket ticket) {
        Fragment fragment = current.get();
        if (fragment == null) {
            throw new IllegalStateException(
                "a publish outside the native binding would write a line for no effect");
        }
        CommitTicket.Outcome outcome = fragment.run(ticket);
        if (outcome == null) {
            throw new IllegalStateException("the native fragment reported no outcome");
        }
        return outcome;
    }
}
