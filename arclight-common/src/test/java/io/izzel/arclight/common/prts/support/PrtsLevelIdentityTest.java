/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PrtsLevelIdentityTest {

    @Test
    void oneLevelIsReturned() {
        Object level = new Object();
        assertSame(level, PrtsLevelIdentity.sole("minecraft:overworld", 1, level));
    }

    @Test
    void aMissingWorldIdReturnsEmpty() {
        assertNull(PrtsLevelIdentity.sole("minecraft:overworld", 0, new Object()));
        assertNull(PrtsLevelIdentity.sole(null, 1, new Object()));
        assertNull(PrtsLevelIdentity.sole("", 1, new Object()));
        assertNull(PrtsLevelIdentity.sole("minecraft:overworld", 1, null));
    }

    @Test
    void aSharedWorldIdDoesNotPickTheFirstLevel() {
        Object first = new Object();
        assertNull(PrtsLevelIdentity.sole("dup:world", 2, first));
    }
}
