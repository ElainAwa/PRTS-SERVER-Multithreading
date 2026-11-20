/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;

/** Whether the unique commit chain of this process can carry a controlled write: an installed
 * controller is one column, a wired publisher is the other. A candidate owner batch asks this
 * before it admits anything, so an uninstalled or non-executable chain refuses the owner path
 * before the first effect instead of writing first and logging afterwards. */
public final class OwnerCommitGate {

    public record Readiness(boolean ready, String reason) {
    }

    private OwnerCommitGate() {
    }

    public static Readiness read() {
        return of(KernelModule.instance().commitController());
    }

    public static Readiness of(CommitController controller) {
        if (controller == null) {
            return new Readiness(false, "commit-chain-not-installed");
        }
        if (!controller.executable()) {
            return new Readiness(false, "commit-publisher-not-wired");
        }
        return new Readiness(true, "");
    }
}
