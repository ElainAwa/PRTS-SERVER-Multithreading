/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;

/** Pure state mapping for one host-leg controlled write: prepare, execute inside the controller's
 * publish action, and account the outcome. It never runs the native body itself, so the caller keeps
 * the body on the server thread and can test the refusal/terminal mapping without a world. */
final class HostLegOwnerWrite {

    interface Preparation {
        OwnerControlledWrite.Admission prepare();
    }

    interface Execution {
        CommitController.SubmitResult execute(OwnerControlledWrite.Admission admission);
    }

    interface Accounting {
        void hostTick();

        void executed();

        void terminal();
    }

    enum State {
        HOST_TICK,
        EXECUTED,
        TERMINAL
    }

    record Outcome(State state) {
    }

    private HostLegOwnerWrite() {
    }

    static Outcome run(Preparation preparation, Execution execution, Accounting accounting) {
        OwnerControlledWrite.Admission admission = preparation.prepare();
        if (admission == null) {
            accounting.hostTick();
            return new Outcome(State.HOST_TICK);
        }
        try {
            CommitController.SubmitResult result = execution.execute(admission);
            if (result == null || (!result.admitted() && result.terminal() == null)) {
                accounting.hostTick();
                return new Outcome(State.HOST_TICK);
            }
            if (result.admitted() && result.terminal() == CommitTicket.Terminal.PUBLISHED) {
                accounting.executed();
                return new Outcome(State.EXECUTED);
            }
            accounting.terminal();
            return new Outcome(State.TERMINAL);
        } catch (RuntimeException | Error failure) {
            accounting.terminal();
            throw failure;
        }
    }
}
