/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobExecutionContextTest {

    @Test
    void undeclaredReadWriteOwnerAreRefusedAndShareIsMetered() {
        JobExecutionContext context = new JobExecutionContext(
            Set.of("entity.read"), Set.of("entity.write"), Set.of("entity.owner"), 7L, 2.0);
        assertTrue(context.allowsRead("entity.read"));
        assertFalse(context.allowsRead("world.raw"));
        assertTrue(context.allowsWrite("entity.write"));
        assertFalse(context.allowsWrite("world.write"));
        assertTrue(context.allowsOwner("entity.owner"));
        assertFalse(context.allowsOwner("other"));
        assertTrue(context.charge(1.0));
        assertFalse(context.charge(2.0));
    }
}
