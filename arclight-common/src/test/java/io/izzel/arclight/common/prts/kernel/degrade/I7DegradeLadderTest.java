/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I7DegradeLadderTest {

    @Test
    void b1ToB5EnterAndEffectiveWithDefaultOff() {
        DegradeLadder off = new DegradeLadder(() -> false);
        off.noteEntered(DegradeLevel.B5, 10L);
        for (DegradeLevel level : DegradeLevel.values()) {
            if (level != DegradeLevel.NONE) off.noteEffective(level);
        }
        assertEquals(DegradeLevel.B5, off.sign().deepest());
        assertEquals(0L, off.effectiveTotal());
        assertEquals(5L, off.enteredTotal());

        DegradeLadder on = new DegradeLadder(() -> true);
        on.noteEntered(DegradeLevel.B5, 10L);
        int effective = 0;
        for (DegradeLevel level : DegradeLevel.values()) {
            if (level != DegradeLevel.NONE && on.noteEffective(level)) effective++;
        }
        assertEquals(5, effective);
        assertEquals(5L, on.effectiveTotal());
        assertEquals(5L, on.enteredTotal());
        assertTrue(on.counters().size() >= 5);
    }
}
