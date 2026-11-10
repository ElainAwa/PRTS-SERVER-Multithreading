/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLifecycleTest {

    @Test
    void sevenStatesAndUnloadFourStepsStayOrdered() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertTrue(owner.requestCreate("w"));
        assertEquals(WorldLifecycleOwner.State.CREATING, owner.stateOf("w"));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.LOADING));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.READY));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.PINNED));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.READY));
        assertTrue(owner.requestUnload("w"));
        assertEquals(WorldLifecycleOwner.State.UNLOADING, owner.stateOf("w"));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.RECLAIMING));
        assertTrue(owner.transition("w", WorldLifecycleOwner.State.GONE));
        assertFalse(owner.transition("w", WorldLifecycleOwner.State.READY));
        assertEquals(WorldLifecycleOwner.State.GONE, owner.stateOf("w"));
    }

    @Test
    void illegalTransitionAndForeignThreadAreDenied() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertTrue(owner.requestCreate("w"));
        assertFalse(owner.transition("w", WorldLifecycleOwner.State.READY), "CREATE->READY is illegal");
        assertFalse(owner.requestUnload("w"), "CREATING is not unloadable");
        assertTrue(owner.deniedCount() >= 2L);
    }
}
