/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The re-scoping arm is inert until a process declares it, and then still only counts. */
class PrtsEntityRescopeTest {

    @Test
    void theArmIsOffUnlessTheProcessDeclaresIt() {
        assertFalse(PrtsEntityRescope.timed());
    }

    @Test
    void anUndeclaredArmTouchesNoRowAndCountsNothing() {
        PrtsEntityRescope.reset();
        PrtsEntityRescope.rowOffered(null);
        PrtsEntityRescope.hostEntry(null, null);
        PrtsEntityRescope.hostExit(null);
        PrtsEntityRescope.passengerRow(null);

        String census = PrtsEntityRescope.censusLine();
        assertTrue(census.contains("offered=0"));
        assertTrue(census.contains("entered=0"));
        assertTrue(census.contains("passengers=0"));
        assertTrue(census.contains("unpaired=0"));
        assertTrue(census.contains("timed=0"));
        assertEquals("[PRTS] entity-rescope-class:", PrtsEntityRescope.classLine());
        assertEquals("[PRTS] entity-rescope-reason:", PrtsEntityRescope.reasonLine());
    }
}
