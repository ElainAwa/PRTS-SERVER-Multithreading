/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Old-red gate: on the pre-fix tree these reflective lookups fail because the old tree has no
 * claimed-row dispatch hook and no shared-executor binding. On the fixed tree they pass. The test
 * is deliberately reflection-only so it compiles against the old production tree. */
class OwnershipTakeoverOldRedTest {

    @Test
    void oldRedCandidateCannotExcludeAClaimedRowFromDispatch() {
        assertDoesNotThrow(() -> EntityTickOwnership.class.getMethod("claimedForDispatch", int.class));
    }

    @Test
    void oldRedCandidateCannotBindASharedExecutor() {
        assertDoesNotThrow(() -> EntityTickOwnership.class.getMethod("bindSharedExecutor",
            java.util.concurrent.Executor.class));
    }
}
